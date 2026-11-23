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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else if (!GestureService.running) startIt()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) startIt()
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
