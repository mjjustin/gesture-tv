package app.gesturetv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Start/stop screen with a live camera view. Gesture detection itself runs in GestureService,
 * so it keeps going behind YouTube/Netflix; this screen just borrows its frames.
 */
class MainActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var preview: ImageView
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info = TextView(this).apply { textSize = 15f; setTextColor(Color.LTGRAY) }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            adjustViewBounds = true
        }
        val start = Button(this).apply { text = "Start gesture control"; setOnClickListener { startIt() } }
        val stop = Button(this).apply { text = "Stop"; setOnClickListener { stopService(Intent(this@MainActivity, GestureService::class.java)) } }
        val help = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); setLineSpacing(6f, 1f)
            val c = Config.load(this@MainActivity)
            text = """
                ✋  Hold open palm ${c.armHoldMs / 1000.0} s → gestures ON
                     (stays on ${c.activeMs / 1000} s after your last gesture)
                ✋  Then move open palm slowly ⬅ ➡ ⬆ ⬇ → one tile per step
                👌  OK sign + drag ⬆ ⬇ → volume
                ✊  Fist → OK / open          🤟  → Back
                👎  Thumbs down (hold 1 s) → mute / unmute
                ☝  1 finger → Home     ✌  2 fingers → ${c.twoFingers.name}     3 fingers → ${c.threeFingers.name}
                👍  Thumbs up (hold 2 s) → kids' ${c.timerMinutes}-minute timer (again = cancel)
                🎤  Say "${c.wakePhrases.first().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }}" (or raise your palm) → TV turns down → say: volume up · mute · pause · play ·
                     open ${c.twoFingers.spoken} · open ${c.threeFingers.spoken} · go home · go back · left/right/up/down · ok/open · ${c.timerMinutes} minutes left
                     Keep going: several commands in a row work ("right… right… open"); "thank you" ends
            """.trimIndent()
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 0, 0, 0)
            addView(TextView(context).apply { text = "GestureTV"; textSize = 30f; setTextColor(Color.WHITE) })
            addView(help); addView(info)
            addView(LinearLayout(context).apply { addView(start); addView(stop) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18)); setPadding(60, 40, 60, 40)
            addView(preview, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        start.requestFocus()
        intent.getIntExtra("mic_test", 0).takeIf { it > 0 }?.let { secs ->
            info.text = "🎤 Recording ${secs} s from the webcam mic… speak now"
            Thread {
                val r = runCatching { MicTest.run(this, secs) }.getOrElse { "mic test failed: $it" }
                File(getExternalFilesDir(null), "mic_test.txt").writeText(r)
                runOnUiThread { info.text = r }
            }.start()
        }
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        } else if (!GestureService.running) startIt()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        startIt()   // camera is required; without the mic, voice commands are simply off
    }

    private fun startIt() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        startForegroundService(Intent(this, GestureService::class.java))
    }

    override fun onResume() {
        super.onResume()
        GestureService.previewSink = { bmp -> runOnUiThread { preview.setImageBitmap(bmp) } }
        tick()
    }

    override fun onPause() {
        super.onPause()
        GestureService.previewSink = null
        h.removeCallbacksAndMessages(null)
    }

    private fun tick() {
        val c = Config.load(this)
        val paired = c.paired
        info.text = "\nStatus: ${GestureService.status}" +
            "   ·   TV pairing: ${if (paired) "OK" else "not paired (arrows/OK/Back off)"}" +
            "   ·   Overlay: ${if (Settings.canDrawOverlays(this)) "OK" else "no permission"}\n"
        h.postDelayed({ tick() }, 1000)
    }
}
