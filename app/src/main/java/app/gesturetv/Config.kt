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
data class Shortcut(val name: String, val pkg: String, val spoken: String = name.lowercase())

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
    /** "always" = listen for "Jarvis" all the time, "dark" = only when the camera sees a dark room, "off". */
    val wakeWord: String = "always",
    /** How long to wait for a command after "Jarvis". */
    val listenMs: Long = 5000,
    /** Volume steps per "volume up" / "volume down". */
    val volumeSteps: Int = 3,
    /** While listening, turn the TV down to this % of its volume (0 = mute, 100 = don't change). */
    val duckPercent: Int = 30,
    /** Palm up also switches voice commands on (no "Jarvis" needed while gestures are on). */
    val voiceWithPalm: Boolean = true,
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
            val voice = j.optJSONObject("voice") ?: JSONObject()
            fun shortcut(key: String, def: Shortcut): Shortcut {
                val o = sc.optJSONObject(key) ?: return def
                val name = o.optString("name", def.name)
                return Shortcut(name, o.optString("package", def.pkg), o.optString("spoken", name.lowercase()))
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
                wakeWord = voice.optString("wakeWord", d.wakeWord),
                listenMs = (voice.optDouble("listenSeconds", d.listenMs / 1000.0) * 1000).toLong(),
                volumeSteps = voice.optInt("volumeSteps", d.volumeSteps),
                duckPercent = voice.optInt("duckPercent", d.duckPercent).coerceIn(0, 100),
                voiceWithPalm = voice.optBoolean("withPalm", d.voiceWithPalm),
            )
        }
    }
}
