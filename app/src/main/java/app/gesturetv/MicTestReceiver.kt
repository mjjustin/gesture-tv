package app.gesturetv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Developer check without leaving the current app (so a playing video keeps playing):
 *   adb shell am broadcast -n app.gesturetv/.MicTestReceiver --ei seconds 20
 * Protected by the DUMP permission, which only adb shell / the system hold.
 */
class MicTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val secs = intent.getIntExtra("seconds", 10)
        // Android only lets a foreground service (started while the app was visible) use the mic,
        // so the recording runs inside the running GestureService.
        if (!GestureService.running) {
            File(ctx.getExternalFilesDir(null), "mic_test.txt").writeText("mic test failed: GestureTV service not running")
            return
        }
        ctx.startService(Intent(ctx, GestureService::class.java).setAction(GestureService.ACTION_MIC_TEST).putExtra("seconds", secs))
    }
}
