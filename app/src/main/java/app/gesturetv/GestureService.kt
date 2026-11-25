package app.gesturetv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class GestureService : LifecycleService() {

    companion object {
        const val TAG = "GestureTV"
        const val ACTION_MIC_TEST = "app.gesturetv.MIC_TEST"
        @Volatile var running = false
        @Volatile var status = "stopped"
        /** Set by MainActivity while it is on screen: receives annotated camera frames. */
        @Volatile var previewSink: ((Bitmap) -> Unit)? = null
        val HAND = intArrayOf(0,1, 1,2, 2,3, 3,4, 0,5, 5,6, 6,7, 7,8, 5,9, 9,10, 10,11, 11,12,
            9,13, 13,14, 14,15, 15,16, 13,17, 0,17, 17,18, 18,19, 19,20)
        const val ARMED_INTERVAL_MS = 33L   // up to the camera's 30 fps while gestures are on
        const val IDLE_INTERVAL_MS = 125L   // 8 fps when nobody has shown a hand for IDLE_AFTER_MS
        const val IDLE_AFTER_MS = 3000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var analysisExec: ExecutorService
    private val netExec = Executors.newSingleThreadExecutor()
    private var recognizer: GestureRecognizer? = null
    private var js: JointSpace? = null
    private lateinit var cfg: Config
    private var homePkg = ""
    private lateinit var engine: GestureEngine

    private var lastProc = 0L
    private var lastHandT = 0L
    private var idle = false
    private var cameraProvider: ProcessCameraProvider? = null

    /** Camera off while the TV screen is off (standby), back on when it wakes. */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            when (i.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    cameraProvider?.unbindAll(); camera = null; event("screen off: camera stopped")
                }
                android.content.Intent.ACTION_SCREEN_ON -> {
                    screenOn = true; event("screen on: camera started"); startCamera() }
            }
        }
    }
    private var frames = 0; private var fpsT = 0L

    // ---- on-screen state (main thread) ----
    private var pill: TextView? = null      // small icon top-right
    private var clock: TextView? = null     // kids' countdown, top centre
    private var banner: TextView? = null    // big message, centre
    @Volatile private var flash: String? = null
    @Volatile private var flashUntil = 0L
    private var bannerUntil = 0L
    private var timerEnd = 0L               // elapsedRealtime; 0 = no timer
    private var timeUp = false              // "TV time is over" is showing; 👍 clears it
    private var foreground = ""
    private var eventsSince = System.currentTimeMillis() - 3_600_000
    private var ticks = 0
    private var wasArmed = false

    override fun onCreate() {
        super.onCreate()
        running = true
        goForeground()
        registerReceiver(screenReceiver, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_SCREEN_ON)
        })
        cfg = Config.load(this)
        js = if (cfg.paired) JointSpace(cfg.tvHost, cfg.tvUser!!, cfg.tvPassword!!) else null
        homePkg = cfg.homePackage.ifEmpty {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName ?: ""
        }
        engine = GestureEngine(cfg.mirror, cfg.armHoldMs, cfg.activeMs) { a -> main.post { onAction(a) } }
        engine.log = { event(it) }
        event("service started (TV remote API: ${if (js != null) cfg.tvHost else "not paired"}, home: $homePkg)")
        addOverlays()
        main.post(uiTick)
        analysisExec = Executors.newSingleThreadExecutor()
        analysisExec.execute {
            recognizer = createRecognizer()
            main.post { startCamera() }
        }
        startVoice()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_MIC_TEST) {
            val secs = intent.getIntExtra("seconds", 10).coerceIn(1, 60)
            Thread {
                val r = runCatching { MicTest.run(this, secs) }.getOrElse { "mic test failed: $it" }
                File(getExternalFilesDir(null), "mic_test.txt").writeText(r)
                event(r)
            }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; status = "stopped"
        runCatching { unregisterReceiver(screenReceiver) }
        main.removeCallbacksAndMessages(null)
        voice?.stop()
        if (wantDuckPrev) { wantDuckPrev = false; duck(false) }
        analysisExec.execute { recognizer?.close(); recognizer = null }
        analysisExec.shutdown(); netExec.shutdown(); logExec.shutdown()
        val wm = getSystemService(WindowManager::class.java)
        listOf(pill, clock, banner).forEach { v -> v?.let { runCatching { wm.removeView(it) } } }
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("gesture", "Gesture control", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "gesture")
            .setContentTitle("GestureTV is watching for gestures")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun createRecognizer(): GestureRecognizer? {
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val opts = GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").setDelegate(d).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumHands(1)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build()
                val r = GestureRecognizer.createFromOptions(this, opts)
                Log.i(TAG, "Recognizer ready on $d")
                status = "running ($d)"
                return r
            } catch (t: Throwable) {
                Log.w(TAG, "Recognizer on $d failed: $t")
            }
        }
        status = "error: gesture model failed to load"
        return null
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val builder = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // Keep the camera's native YUV: frames we skip are then never colour-converted;
                    // toBitmap() converts only the ones we actually analyse.
                // Let auto-exposure drop to 5 fps in the dark (longer exposure per frame).
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(5, 30))
                val analysis = builder.build()
                analysis.setAnalyzer(analysisExec) { analyze(it) }
                // The USB webcam is the only camera; take whatever is there.
                val selector = CameraSelector.Builder().addCameraFilter { it.take(1) }.build()
                cameraProvider = provider
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, analysis)
                Log.i(TAG, "Camera bound")
            } catch (t: Throwable) {
                Log.e(TAG, "Camera failed", t)
                status = "error: no camera ($t)"
                showFlash("⚠ no camera", 5000)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- camera frames (analysis thread) ----

    private fun analyze(img: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        val r = recognizer
        // Slow down when nobody is gesturing (unless the camera view is open in the app).
        val nowIdle = previewSink == null && !engine.armed(now) && now - lastHandT > IDLE_AFTER_MS
        if (nowIdle != idle) { idle = nowIdle; event(if (idle) "idle: 8 fps" else "active: full speed") }
        // As soon as a hand is in view, go full speed so the palm-to-arm is quick.
        val interval = if (idle) IDLE_INTERVAL_MS else ARMED_INTERVAL_MS
        if (r == null || now - lastProc < interval) { img.close(); return }
        lastProc = now
        val bmp: Bitmap
        try {
            val raw = img.toBitmap()
            val rot = img.imageInfo.rotationDegrees
            bmp = if (rot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        } finally {
            img.close()
        }
        val img2 = lowLight(bmp, now)
        val res = try { r.recognizeForVideo(BitmapImageBuilder(img2).build(), now) } catch (t: Throwable) {
            Log.w(TAG, "recognize failed: $t"); return
        }
        val lm = res.landmarks().firstOrNull()
        var label = "None"
        var cx = 0f; var cy = 0f
        if (lm != null) {
            val top = res.gestures().firstOrNull()?.firstOrNull()
            if (top != null && top.score() >= 0.55f) label = top.categoryName()
            if (label.isEmpty()) label = "None"
            if (label != "Closed_Fist" && okSign(lm)) label = "Pinch"
            else if (label != "Open_Palm" && threeUp(lm)) label = "Three"
            val palm = listOf(0, 5, 9, 13, 17).map { lm[it] }
            cx = palm.map { it.x() }.average().toFloat()
            cy = palm.map { it.y() }.average().toFloat()
        }
        if (lm != null) lastHandT = now
        engine.feed(Frame(now, label, lm != null, cx, cy))
        previewSink?.let { sink -> if (frames % 2 == 0) sink(annotate(img2, lm, label, engine.armed(now))) }

        frames++
        if (now - fpsT >= 1000) {
            Log.d(TAG, "fps=$frames label=$label armed=${engine.armed(now)}")
            frames = 0; fpsT = now
        }
    }

    // ---- low light ----
    private var camera: Camera? = null
    @Volatile private var brightness = 128f   // smoothed mean luma 0..255 (before boost)
    @Volatile private var expIndex = 0
    @Volatile private var gain = 1f
    private var lastExpAdjust = 0L
    private var boosting = false

    /**
     * Measures frame brightness; in the dark it raises the camera's exposure compensation
     * and brightens a copy of the frame before the model sees it.
     */
    private fun lowLight(bmp: Bitmap, now: Long): Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w)
        var sum = 0L; var n = 0
        for (y in 0 until h step 12) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w step 12) {
                val c = px[x]
                sum += (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
                n++
            }
        }
        val mean = if (n > 0) sum.toFloat() / n else 128f
        brightness = brightness * 0.8f + mean * 0.2f

        // Camera exposure compensation: step it up in the dark, back down when bright.
        val cam = camera
        if (cam != null && now - lastExpAdjust > 1500) {
            val range = cam.cameraInfo.exposureState.exposureCompensationRange
            val target = when {
                brightness < 60 && expIndex < range.upper -> expIndex + 3
                brightness > 150 && expIndex > 0 -> expIndex - 3
                else -> expIndex
            }.coerceIn(0, range.upper)
            if (target != expIndex) {
                expIndex = target; lastExpAdjust = now
                main.post { cam.cameraControl.setExposureCompensationIndex(target) }
            }
        }

        // Software brightening when still dark.
        gain = if (brightness < 70) (110f / brightness.coerceAtLeast(10f)).coerceAtMost(4f) else 1f
        val nowBoost = gain > 1.05f
        if (nowBoost != boosting) { boosting = nowBoost; event(if (nowBoost) "low light: boost on (light ${brightness.toInt()})" else "low light: boost off") }
        if (!nowBoost) return bmp
        val cm = ColorMatrix(floatArrayOf(gain, 0f, 0f, 0f, 8f, 0f, gain, 0f, 0f, 8f, 0f, 0f, gain, 0f, 8f, 0f, 0f, 0f, 1f, 0f))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** Mirrored copy of the frame (so it behaves like a mirror) with the hand skeleton drawn on. */
    private fun annotate(src: Bitmap, lm: List<NormalizedLandmark>?, label: String, armed: Boolean): Bitmap {
        val w = src.width.toFloat(); val h = src.height.toFloat()
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { preScale(-1f, 1f) }, false)
            .copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val col = if (armed) Color.rgb(60, 220, 90) else Color.rgb(255, 200, 0)
        if (lm != null) {
            val line = Paint().apply { color = col; strokeWidth = 3f; isAntiAlias = true }
            for (i in HAND.indices step 2) {
                val a = lm[HAND[i]]; val b = lm[HAND[i + 1]]
                c.drawLine((1 - a.x()) * w, a.y() * h, (1 - b.x()) * w, b.y() * h, line)
            }
            val dot = Paint().apply { color = Color.WHITE; isAntiAlias = true }
            lm.forEach { c.drawCircle((1 - it.x()) * w, it.y() * h, 3.5f, dot) }
        }
        val txt = Paint().apply { color = col; textSize = 18f; isAntiAlias = true; isFakeBoldText = true }
        c.drawText((if (armed) "ON  " else "off  ") + (if (lm == null) "no hand" else label), 8f, 24f, txt)
        val small = Paint().apply { color = Color.WHITE; textSize = 13f; isAntiAlias = true }
        c.drawText("light %d  exp %+d  boost x%.1f".format(brightness.toInt(), expIndex, gain), 8f, h - 8f, small)
        return out
    }

    // ---- small event log in the app's files dir (logcat is flooded by the camera driver) ----
    private val logExec = Executors.newSingleThreadExecutor()
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
    private fun event(msg: String) {
        val line = "${stamp.format(java.util.Date())} $msg\n"
        Log.i(TAG, msg)
        logExec.execute {
            runCatching {
                val f = File(getExternalFilesDir(null), "events.log")
                if (f.length() > 256_000) f.writeText(f.readLines().takeLast(500).joinToString("\n") + "\n")
                f.appendText(line)
            }
        }
    }

    private fun d(lm: List<NormalizedLandmark>, a: Int, b: Int) = hypot(lm[a].x() - lm[b].x(), lm[a].y() - lm[b].y())
    private fun extended(lm: List<NormalizedLandmark>, tip: Int, pip: Int) = d(lm, 0, tip) > d(lm, 0, pip) * 1.15f

    /** Index, middle and ring up, pinky folded (thumb either way). Used for Home. */
    private fun threeUp(lm: List<NormalizedLandmark>) =
        extended(lm, 8, 6) && extended(lm, 12, 10) && extended(lm, 16, 14) && !extended(lm, 20, 18)

    /** 👌: thumb tip touching index tip, middle/ring/pinky stretched out. */
    private fun okSign(lm: List<NormalizedLandmark>): Boolean {
        val hand = d(lm, 0, 9)                 // wrist → middle knuckle, for scale
        return d(lm, 4, 8) < 0.35f * hand &&
            extended(lm, 12, 10) && extended(lm, 16, 14) && extended(lm, 20, 18)
    }

    // ---- actions (main thread) ----

    private fun onAction(a: Action) {
        event("action ${a.name}")
        if (a == Action.TIMER) { toggleTimer(); return }
        val j = js
        val label = when (a) {
            Action.APP2 -> "▶ ${cfg.twoFingers.name}"
            Action.APP3 -> "▶ ${cfg.threeFingers.name}"
            else -> a.label
        }
        // Without the Philips remote API, navigation keys can't be sent to other apps.
        if (j == null && a in setOf(Action.UP, Action.DOWN, Action.LEFT, Action.RIGHT, Action.OK, Action.BACK)) {
            showFlash("$label – needs TV pairing", 1500); return
        }
        showFlash(label, 1200)
        if (a == Action.VOL_UP || a == Action.VOL_DOWN || a == Action.MUTE) unduckForVolumeChange()
        when (a) {
            Action.APP2 -> { onAppOpened(cfg.twoFingers.pkg); launch(cfg.twoFingers.pkg); return }
            Action.APP3 -> { onAppOpened(cfg.threeFingers.pkg); launch(cfg.threeFingers.pkg); return }
            else -> {}
        }
        if (j == null) {          // fallbacks that work on any Android TV
            val am = getSystemService(AudioManager::class.java)
            when (a) {
                Action.VOL_UP -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                Action.VOL_DOWN -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                Action.MUTE -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                Action.PAUSE, Action.PLAY -> mediaKey(a)
                Action.HOME -> startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> {}
            }
            return
        }
        netExec.execute {
            val code = when (a) {
                Action.VOL_UP -> j.key("VolumeUp")
                Action.VOL_DOWN -> j.key("VolumeDown")
                Action.MUTE -> j.key("Mute")
                Action.UP -> j.key("CursorUp")
                Action.DOWN -> j.key("CursorDown")
                Action.LEFT -> j.key("CursorLeft")
                Action.RIGHT -> j.key("CursorRight")
                Action.OK -> j.key("Confirm")
                Action.BACK -> j.key("Back")
                Action.HOME -> j.key("Home")
                Action.PAUSE, Action.PLAY -> { mediaKey(a); 0 }
                else -> 0
            }
            event("${a.name} -> HTTP $code")
        }
    }

    /** Play/pause through Android's media session, so it works on any TV and with any player app. */
    private fun mediaKey(a: Action) {
        val code = if (a == Action.PAUSE) android.view.KeyEvent.KEYCODE_MEDIA_PAUSE else android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        val am = getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    // ---- voice: "Jarvis" (or palm up) + command ----

    private var voice: VoiceControl? = null
    @Volatile private var screenOn = true

    private fun voicePhrases(): Map<String, String> {
        val n = mapOf(1 to "one", 2 to "two", 3 to "three", 4 to "four", 5 to "five", 10 to "ten", 15 to "fifteen", 20 to "twenty")
        val m = linkedMapOf(
            "volume up" to "VOL_UP", "louder" to "VOL_UP", "volume down" to "VOL_DOWN", "quieter" to "VOL_DOWN",
            "mute" to "MUTE_ON", "unmute" to "MUTE_OFF",
            "pause" to "PAUSE", "play" to "PLAY", "resume" to "PLAY",
            "go home" to "HOME", "home" to "HOME", "go back" to "BACK", "back" to "BACK",
            "left" to "LEFT", "right" to "RIGHT", "up" to "UP", "down" to "DOWN", "ok" to "OK", "select" to "OK",
            "open" to "OK", "enter" to "OK", "click" to "OK", "open it" to "OK",
            "thank you" to VoiceControl.END, "thanks" to VoiceControl.END, "stop listening" to VoiceControl.END,
            "start timer" to "TIMER_START", "cancel timer" to "TIMER_CANCEL", "stop timer" to "TIMER_CANCEL",
        )
        n[cfg.timerMinutes]?.let { m["$it minutes left"] = "TIMER_START" }
        for ((sc, id) in listOf(cfg.twoFingers to "APP2", cfg.threeFingers to "APP3")) {
            m["open ${sc.spoken}"] = id; m[sc.spoken] = id
        }
        return m
    }

    private fun startVoice() {
        if (cfg.wakeWord == "off" && !cfg.voiceWithPalm) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            event("voice off: no microphone permission"); return
        }
        Thread {
            val dir = VoskModel.prepare(this) { event(it) }
            if (dir == null) { event("voice off: voice model not available (no internet on first start?)"); return@Thread }
            val v = VoiceControl(this, dir, voicePhrases(), cfg.listenMs,
                wakeEnabled = { screenOn && (cfg.wakeWord == "always" || (cfg.wakeWord == "dark" && brightness < 45)) },
                commandMode = { screenOn && palmVoice(SystemClock.uptimeMillis()) },
                listener = object : VoiceControl.Listener {
                    override fun onWake() = main.post { event("voice: wake"); main.post(uiTick) }.let { }
                    override fun onCommand(id: String, heard: String) = main.post { voiceCommand(id, heard) }.let { }
                    override fun onListenEnd(gotCommand: Boolean) = main.post { if (!gotCommand) event("voice: no command") }.let { }
                    override fun log(msg: String) = event(msg)
                })
            voice = v
            v.start()
        }.start()
    }

    private fun voiceCommand(id: String, heard: String) {
        event("voice: \"$heard\" -> $id")
        when (id) {
            "MUTE_ON" -> { setMute(true); showFlash("🎤 🔇 Mute", 1500); return }
            "MUTE_OFF" -> { setMute(false); showFlash("🎤 🔊 Unmute", 1500); return }
            "TIMER_START" -> { if (timerEnd == 0L && !timeUp) toggleTimer(); return }
            "TIMER_CANCEL" -> { if (timerEnd != 0L || timeUp) toggleTimer(); return }
        }
        val a = runCatching { Action.valueOf(id) }.getOrNull() ?: return
        val times = if (a == Action.VOL_UP || a == Action.VOL_DOWN) cfg.volumeSteps else 1
        repeat(times) { onAction(a) }
        flash = "🎤 " + (flash ?: ""); main.post(uiTick)
    }

    // ---- turning the TV down while listening ("ducking") ----
    // All volume state lives on netExec, so changes happen in order.

    private var ducked = false            // netExec
    private var origVolume = -1           // netExec: level to restore
    private var origMuted = false         // netExec
    /** Set when the user changes the volume while ducked: stop ducking until listening ends. */
    /** armedAt of the palm session whose voice was closed because an app opened. */
    @Volatile private var palmVoiceClosedFor = -1L

    /** Palm up = voice commands too, until an app is opened (raise the palm again for more). */
    private fun palmVoice(now: Long) =
        cfg.voiceWithPalm && engine.armed(now) && engine.armedAt != palmVoiceClosedFor

    /** An app other than the launcher came to the front: stop listening so its sound can't trigger commands. */
    private fun onAppOpened(pkg: String) {
        val v = voice ?: return
        val palm = palmVoice(SystemClock.uptimeMillis())
        if (!v.listening && !palm) return
        v.finish()
        palmVoiceClosedFor = engine.armedAt
        event("voice: closed, $pkg opened")
    }

    @Volatile private var duckSuppressed = false
    @Volatile private var wantDuckPrev = false

    /** Called from the UI tick: duck while "Jarvis" is listening or (palm up + voice). */
    private fun updateDuck(now: Long) {
        val listeningNow = voice?.listening == true || (voice != null && palmVoice(now))
        if (!listeningNow) duckSuppressed = false
        val want = listeningNow && !duckSuppressed && cfg.duckPercent < 100
        if (want != wantDuckPrev) { wantDuckPrev = want; duck(want) }
    }

    private fun duck(on: Boolean) {
        val j = js
        netExec.execute {
            if (on == ducked) return@execute
            if (on) {
                if (j != null) {
                    val v = j.getVolume() ?: return@execute
                    origVolume = v.current; origMuted = v.muted
                    if (!v.muted) j.setMuted(false, v.current * cfg.duckPercent / 100)
                } else {
                    val am = getSystemService(AudioManager::class.java)
                    origVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, origVolume * cfg.duckPercent / 100, 0)
                }
                ducked = true
                event("volume lowered for listening ($origVolume → ${origVolume * cfg.duckPercent / 100})")
            } else {
                if (j != null) j.setMuted(origMuted, origVolume)
                else getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, origVolume, 0)
                ducked = false
                event("volume restored ($origVolume)")
            }
        }
    }

    /** Before a volume/mute change: restore the normal level first, and don't duck again this round. */
    private fun unduckForVolumeChange() {
        if (!wantDuckPrev) return
        duckSuppressed = true; wantDuckPrev = false
        duck(false)
    }

    private fun setMute(m: Boolean) {
        unduckForVolumeChange()
        val j = js
        netExec.execute {
            if (j != null) j.getVolume()?.let { if (it.muted != m) j.setMuted(m, it.current) }
            else getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, if (m) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        }
    }

    /**
     * Opens an app by package name. On a paired Philips TV this goes through the TV's own API
     * (tested reliable); otherwise the app is started directly, which Android allows because
     * GestureTV has the "display over other apps" permission.
     */
    private fun launch(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        val cls = intent?.component?.className
        if (intent == null || cls == null) { showFlash("⚠ $pkg not installed", 2500); event("launch $pkg: not installed"); return }
        val j = js
        if (j != null) {
            netExec.execute { event("launch $pkg via TV API -> HTTP ${j.launch(pkg, cls)}") }
            return
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            event("launch $pkg: started")
        } catch (t: Throwable) {
            event("launch $pkg: failed ($t)")
            showFlash("⚠ couldn't open ${pkg}", 2500)
        }
    }

    private fun toggleTimer() {
        if (timerEnd != 0L || timeUp) {
            timerEnd = 0L; bannerUntil = 0L; timeUp = false
            event("kids timer cancelled")
            showFlash("⏰ off", 1500)
        } else {
            timerEnd = SystemClock.elapsedRealtime() + cfg.timerMinutes * 60_000L
            event("kids timer started")
            showBanner("⏰  " + cfg.timerStartMessage.replace("{minutes}", cfg.timerMinutes.toString()), 8000)
        }
    }

    private fun showFlash(text: String, ms: Long) {
        flash = text; flashUntil = SystemClock.uptimeMillis() + ms
        main.post(uiTick)
    }

    private fun showBanner(text: String, ms: Long) {
        banner?.text = text
        bannerUntil = SystemClock.uptimeMillis() + ms
        main.post(uiTick)
    }

    // ---- overlays ----

    private val GREEN = Color.argb(230, 46, 160, 67)
    private val DIM = Color.argb(110, 40, 40, 40)

    private val uiTick: Runnable = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            val now = SystemClock.uptimeMillis()
            if (ticks++ % 8 == 0) pollForeground()
            val armedNow = engine.armed(now)
            updateDuck(now)
            if (armedNow != wasArmed) { wasArmed = armedNow; event(if (armedNow) "gestures ON" else "gestures off") }

            // Small icon: hidden unless gestures are on, an action just happened, or we're on the home screen.
            pill?.let { p ->
                val f = flash
                when {
                    f != null && now < flashUntil -> p.style(f, GREEN, 20f)
                    voice?.listening == true -> p.style("🎤 listening…", GREEN, 18f)
                    engine.armed(now) -> p.style(if (voice != null && cfg.voiceWithPalm) "✋ 🎤" else "✋", GREEN, 16f)
                    foreground == homePkg -> p.style("✋", DIM, 16f)
                    else -> p.visibility = View.GONE
                }
            }

            // Kids' countdown.
            clock?.let { c ->
                if (timerEnd != 0L) {
                    val left = timerEnd - SystemClock.elapsedRealtime()
                    if (left > 0) {
                        val s = (left + 999) / 1000
                        c.text = "⏰ %d:%02d".format(s / 60, s % 60)
                        c.visibility = View.VISIBLE
                    } else {
                        timerEnd = 0L
                        c.visibility = View.GONE
                        timeUp = true
                        event("kids timer: time is over")
                        showBanner("⏰  " + cfg.timerEndMessage, Long.MAX_VALUE / 2)  // stays until 👍
                    }
                } else c.visibility = View.GONE
            }
            banner?.visibility = if (now < bannerUntil) View.VISIBLE else View.GONE

            main.postDelayed(this, 250)
        }
    }

    private fun TextView.style(t: String, color: Int, size: Float) {
        if (text != t) text = t
        textSize = size
        (background as GradientDrawable).setColor(color)
        visibility = View.VISIBLE
    }

    /** Which app is on screen, from usage events (needs GET_USAGE_STATS, granted via adb). */
    private fun pollForeground() {
        try {
            val usm = getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(eventsSince, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val p = e.packageName
                    if (p != foreground && p != homePkg && p != packageName) onAppOpened(p)
                    foreground = p
                }
            }
            eventsSince = now
        } catch (t: Throwable) { /* no permission: treat as "not home" */ }
    }

    private fun addOverlays() {
        if (!Settings.canDrawOverlays(this)) { Log.w(TAG, "No overlay permission"); return }
        pill = overlay(Gravity.TOP or Gravity.END, 32, 32, 16f, 18, 8, 60f, GREEN)
        clock = overlay(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 32, 26f, 32, 12, 60f, Color.argb(220, 230, 120, 0))
        banner = overlay(Gravity.CENTER, 0, 0, 44f, 80, 50, 50f, Color.argb(235, 230, 120, 0)).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun overlay(g: Int, x: Int, y: Int, size: Float, padH: Int, padV: Int, radius: Float, color: Int): TextView {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = size
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply { cornerRadius = radius; setColor(color) }
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = g; this.x = x; this.y = y }
        getSystemService(WindowManager::class.java).addView(tv, lp)
        return tv
    }
}
