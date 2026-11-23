package app.gesturetv

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * User settings, read from config.json in the app's external files dir:
 *   /sdcard/Android/data/app.gesturetv/files/config.json
 * (push it with `adb push`, see README). Every key is optional; missing keys use the defaults below.
 */
data class Shortcut(val name: String, val pkg: String)

data class Config(
    /** Philips JointSpace API. null = not paired: arrow keys / OK / Back are unavailable. */
    val tvHost: String = "127.0.0.1",
    val tvUser: String? = null,
    val tvPassword: String? = null,
    /** Hold an open palm this long to switch gestures on. */
    val armHoldMs: Long = 1000,
    /** Gestures switch off this long after the last gesture. */
    val activeMs: Long = 10_000,
    /** Webcam faces the viewer (normal), so left/right are mirrored. */
    val mirror: Boolean = true,
    /** Home-screen app package for the small ✋ icon; empty = the TV's default launcher. */
    val homePackage: String = "",
    val twoFingers: Shortcut = Shortcut("Netflix", "com.netflix.ninja"),
    val threeFingers: Shortcut = Shortcut("YouTube", "com.google.android.youtube.tv"),
    val timerMinutes: Int = 5,
    val timerStartMessage: String = "Only {minutes} minutes left of TV time!",
    val timerEndMessage: String = "TV time is over!\nTime to switch off 😊",
) {
    val paired get() = !tvUser.isNullOrEmpty() && !tvPassword.isNullOrEmpty()

    companion object {
        const val FILE = "config.json"

        fun file(ctx: Context) = File(ctx.getExternalFilesDir(null), FILE)

        fun load(ctx: Context): Config {
            val f = file(ctx)
            if (!f.exists()) {
                Log.w(GestureService.TAG, "No ${f.path}; using defaults")
                return Config()
            }
            return try {
                parse(JSONObject(f.readText()))
            } catch (t: Throwable) {
                Log.e(GestureService.TAG, "Bad ${f.path}; using defaults", t)
                Config()
            }
        }

        private fun parse(j: JSONObject): Config {
            val d = Config()
            val tv = j.optJSONObject("tv") ?: JSONObject()
            val sc = j.optJSONObject("shortcuts") ?: JSONObject()
            val timer = j.optJSONObject("kidsTimer") ?: JSONObject()
            fun shortcut(key: String, def: Shortcut): Shortcut {
                val o = sc.optJSONObject(key) ?: return def
                return Shortcut(o.optString("name", def.name), o.optString("package", def.pkg))
            }
            return Config(
                tvHost = tv.optString("host", d.tvHost),
                tvUser = tv.optString("user", "").ifEmpty { null },
                tvPassword = tv.optString("password", "").ifEmpty { null },
                armHoldMs = (j.optDouble("armHoldSeconds", d.armHoldMs / 1000.0) * 1000).toLong(),
                activeMs = (j.optDouble("activeSeconds", d.activeMs / 1000.0) * 1000).toLong(),
                mirror = j.optBoolean("mirrorCamera", d.mirror),
                homePackage = j.optString("homeLauncherPackage", d.homePackage),
                twoFingers = shortcut("twoFingers", d.twoFingers),
                threeFingers = shortcut("threeFingers", d.threeFingers),
                timerMinutes = timer.optInt("minutes", d.timerMinutes),
                timerStartMessage = timer.optString("startMessage", d.timerStartMessage),
                timerEndMessage = timer.optString("endMessage", d.timerEndMessage),
            )
        }
    }
}
