package app.gesturetv

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline voice commands with a wake word, using Vosk restricted to a fixed phrase list
 * (a small grammar is far more accurate than free dictation, and cheap enough for a TV).
 *
 *  "Hello Jarvis" → onWake() (the service turns the TV down), then every
 *  recognised phrase → onCommand(). The wake phrases come from the config (default "hello jarvis"). Each command restarts the listenMs window, so "Jarvis … right …
 *  right … ok" works; "thank you" ends it early. "Jarvis, pause" in one breath also works.
 *  Command phrases heard WITHOUT the wake word are ignored (so dialogue on TV can't trigger actions),
 *  except while commandMode() is true, i.e. after a palm switched gestures on.
 */
class VoiceControl(
    private val ctx: Context,
    private val modelDir: File,
    /** spoken phrase → command id */
    private val phrases: Map<String, String>,
    private val listenMs: Long,
    /** Wake phrases, e.g. "hello jarvis". Two words are much harder to hit by accident than one. */
    private val wakePhrases: List<String>,
    /** Minimum recogniser confidence (0..1) for the wake phrase's words. */
    private val wakeConfidence: Double,
    /** Whether the wake word should be listened for right now (e.g. only when dark). */
    private val wakeEnabled: () -> Boolean,
    /** True while commands are accepted without the wake word (gestures switched on with a palm). */
    private val commandMode: () -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onWake()
        fun onCommand(id: String, heard: String)
        fun onListenEnd(gotCommand: Boolean)
        fun log(msg: String)
    }

    companion object {
        /**
         * Everyday words added to the grammar. Without them, any TV dialogue is forced into the nearest
         * command or wake phrase; with them, it lands on these words instead and is ignored.
         */
        val FILLER = """
            a about after all also am an and any are as at be because been but by can come could day did do
            does don't even every first for from get give good got great had has have he her here him his how
            i if in into is it its just know like look make man many me more most much my new no not now of
            on one only or other our out over people really said say see she so some take tell than that the
            their them then there these they thing think this time to two us very want was way we well went
            were what when where which who why will with work would yeah year yes you your
            hi hello hey jar jarred jars service nervous harvest marvel davis travis
        """.split(Regex("\\s+")).filter { it.isNotBlank() }
        const val RATE = 16000
        /** Chunk loudness (RMS) above which the recogniser runs. Room silence is ~50–300 on the StreamCam. */
        const val SPEECH_RMS = 500.0
        /** Command id that ends the listening window early ("thank you"). */
        const val END = "END"
        /** Commands that open an app: listening stops right after, so the app's sound can't trigger anything. */
        val FINAL = setOf("APP2", "APP3")
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var listening = false
        private set
    private var listenUntil = 0L
    @Volatile private var finishRequested = false
    private var rec: Recognizer? = null

    /** Stop listening now (e.g. an app was opened: its sound must not trigger commands). */
    fun finish() { finishRequested = true }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runCatching { loop() }.onFailure { listener.log("voice stopped: $it") } }, "voice").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    private fun loop() {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = Model(modelDir.path)
        val grammar = JSONArray((wakePhrases + phrases.keys + FILLER + "[unk]").distinct()).toString()
        val rec = Recognizer(model, RATE.toFloat(), grammar).also { this.rec = it }
        rec.setWords(true)              // per-word confidence, to reject unsure wake words

        val am = ctx.getSystemService(AudioManager::class.java)
        val usb = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            listener.log("voice: microphone not available"); rec.close(); model.close(); return
        }
        if (usb != null) ar.preferredDevice = usb
        // Use the TV's echo canceller / noise suppressor when the platform offers them.
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(ar.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } else null
        ar.startRecording()
        listener.log("voice ready (mic: ${ar.routedDevice?.productName ?: usb?.productName}, echo cancel: ${aec != null}, noise suppress: ${ns != null})")

        val buf = ShortArray(RATE / 10)          // 100 ms chunks
        val preRoll = ArrayDeque<ShortArray>()
        var loudUntil = 0L
        var fed = 0; var skipped = 0; var statT = SystemClock.uptimeMillis()
        var paused = false
        try {
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = SystemClock.uptimeMillis()

                if (finishRequested) {
                    finishRequested = false
                    if (listening) { endListening(false); rec.reset(); preRoll.clear() }
                }
                if (listening && now > listenUntil) endListening(false)
                val open = listening || commandMode()
                if (!open && !wakeEnabled()) {
                    if (!paused) { rec.reset(); paused = true }
                    continue
                }
                paused = false

                // Skip silence: only run the recogniser while there is sound loud enough to be speech.
                // Keep ~300 ms of audio before the sound starts, so the start of "Jarvis" isn't cut off.
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val rms = kotlin.math.sqrt(sum / n)
                if (rms > SPEECH_RMS) loudUntil = now + 1200
                if (!open && now > loudUntil) {
                    preRoll.addLast(buf.copyOf(n)); if (preRoll.size > 3) preRoll.removeFirst()
                    skipped++
                    continue
                }
                while (preRoll.isNotEmpty()) { val p = preRoll.removeFirst(); rec.acceptWaveForm(p, p.size) }
                fed++
                if (now - statT > 60_000) { listener.log("voice: analysed ${fed * 100 / maxOf(1, fed + skipped)}% of the last minute"); fed = 0; skipped = 0; statT = now }

                if (rec.acceptWaveForm(buf, n)) handle(rec.result, now)
            }
        } finally {
            ar.stop(); ar.release(); aec?.release(); ns?.release(); rec.close(); model.close()
        }
    }

    private data class Word(val w: String, val conf: Double)

    private fun handle(json: String, now: Long) {
        val o = JSONObject(json)
        val heard = o.optString("text").replace("[unk]", "").trim()
        if (heard.isEmpty()) return
        val arr = o.optJSONArray("result")
        val words = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
            .map { Word(it.optString("word"), it.optDouble("conf", 1.0)) }

        var rest = heard
        val wake = findWake(words)
        if (wake != null) {
            val (end, conf) = wake
            if (conf < wakeConfidence) {
                listener.log("voice: ignored \"$heard\" (unsure, ${"%.2f".format(conf)})")
                if (!listening && !commandMode()) return
            } else if (!listening) {
                startListening(now)
            }
            rest = words.drop(end).joinToString(" ") { it.w }.replace("[unk]", "").trim()
            if (rest.isEmpty()) return            // just the wake phrase: wait for the command
        } else if (!listening && !commandMode()) {
            return                                 // no wake phrase: ignore (could be the TV talking)
        }
        // Several commands can be said in one go ("right right ok"); run them in order.
        val said = pattern.findAll(rest).map { it.value }.toList()
        if (said.isEmpty()) return
        for (p in said) {
            if (phrases.getValue(p) == END) { endListening(gotCommand); return }
            listener.onCommand(phrases.getValue(p), p)
            gotCommand = true
            if (phrases.getValue(p) in FINAL) { endListening(true); rec?.reset(); return }
        }
        // Keep listening for more commands: each command restarts the window.
        if (listening) listenUntil = now + listenMs
    }

    /** Longest phrases first, so "open netflix" wins over "open", "go home" over "home". */
    private val pattern by lazy {
        Regex("\\b(" + phrases.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b")
    }
    private var gotCommand = false

    /** Last wake phrase in the words: index just after it, and the lowest word confidence in it. */
    private fun findWake(words: List<Word>): Pair<Int, Double>? {
        var best: Pair<Int, Double>? = null
        for (p in wakePhrases) {
            val pw = p.split(" ")
            for (i in 0..words.size - pw.size) {
                if ((pw.indices).all { words[i + it].w == pw[it] })
                    best = Pair(i + pw.size, (pw.indices).minOf { words[i + it].conf })
            }
        }
        return best
    }

    private fun startListening(now: Long) {
        listenUntil = now + listenMs
        if (!listening) {
            listening = true
            gotCommand = false
            listener.onWake()
        }
    }

    private fun endListening(got: Boolean) {
        if (!listening) return
        listening = false
        listener.onListenEnd(got || gotCommand)
    }
}

/**
 * The Vosk English model (~40 MB, Apache-2.0) is downloaded once on first start instead of being
 * bundled, which keeps the APK small. Stored in the app's private storage.
 */
object VoskModel {
    const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun prepare(ctx: Context, log: (String) -> Unit): File? {
        val dest = File(ctx.filesDir, "vosk-model")
        val marker = File(dest, ".ready")
        if (marker.exists()) return dest
        return try {
            log("voice model: downloading (~40 MB, once)…")
            dest.deleteRecursively(); dest.mkdirs()
            val conn = java.net.URL(URL).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
            java.util.zip.ZipInputStream(conn.getInputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val rel = e.name.substringAfter('/')          // drop the top folder
                    if (rel.isEmpty() || rel.contains("..")) continue
                    val out = File(dest, rel)
                    if (e.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
            marker.writeText("ok")
            log("voice model: ready")
            dest
        } catch (t: Throwable) {
            log("voice model download failed: $t")
            dest.deleteRecursively(); null
        }
    }
}
