package app.gesturetv

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Minimal client for the Philips JointSpace API v6 (port 1926, HTTPS + digest auth).
 * The TV uses a self-signed certificate, so this trusts any cert — it only ever talks to the TV.
 */
class JointSpace(private val host: String, private val user: String, private val pass: String) {

    private val ssl: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), SecureRandom())
    }.socketFactory

    private var challenge: Map<String, String>? = null
    private var nc = 0

    fun key(k: String) = post("/6/input/key", """{"key":"$k"}""")

    fun launch(pkg: String, cls: String) = post(
        "/6/activities/launch",
        """{"intent":{"component":{"packageName":"$pkg","className":"$cls"},"action":"android.intent.action.MAIN"}}"""
    )

    data class Volume(val muted: Boolean, val current: Int)

    fun getVolume(): Volume? {
        val (code, body) = request("GET", "/6/audio/volume", null)
        if (code != 200 || body == null) return null
        return runCatching { JSONObject(body).let { Volume(it.getBoolean("muted"), it.getInt("current")) } }.getOrNull()
    }

    fun setMuted(muted: Boolean, current: Int) =
        post("/6/audio/volume", """{"muted":$muted,"current":$current}""")

    /** Returns the HTTP status, or -1 if the TV could not be reached. */
    fun post(path: String, body: String): Int = request("POST", path, body).first

    @Synchronized
    private fun request(method: String, path: String, body: String?): Pair<Int, String?> {
        for (attempt in 1..3) {
            try {
                val c = URL("https://$host:1926$path").openConnection() as HttpsURLConnection
                c.sslSocketFactory = ssl
                c.setHostnameVerifier { _, _ -> true }
                c.connectTimeout = 3000; c.readTimeout = 5000
                c.requestMethod = method
                authHeader(method, path)?.let { c.setRequestProperty("Authorization", it) }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                if (code == 401) {
                    challenge = parse(c.getHeaderField("WWW-Authenticate") ?: "")
                    c.disconnect(); continue
                }
                val text = runCatching { c.inputStream.use { String(it.readBytes()) } }.getOrNull()
                c.disconnect()
                return code to text
            } catch (e: IOException) {
                Log.w(GestureService.TAG, "JointSpace $method $path attempt $attempt: $e")
            }
        }
        return -1 to null
    }

    private fun authHeader(method: String, path: String): String? {
        val ch = challenge ?: return null
        val realm = ch["realm"] ?: ""; val nonce = ch["nonce"] ?: ""
        val qop = ch["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val ha1 = md5("$user:$realm:$pass"); val ha2 = md5("$method:$path")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$path\", algorithm=MD5")
        if (qop != null) {
            nc++
            val ncs = "%08x".format(nc)
            val cnonce = md5(System.nanoTime().toString()).take(16)
            sb.append(", qop=auth, nc=$ncs, cnonce=\"$cnonce\", response=\"${md5("$ha1:$nonce:$ncs:$cnonce:auth:$ha2")}\"")
        } else {
            sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
        }
        ch["opaque"]?.let { sb.append(", opaque=\"$it\"") }
        return sb.toString()
    }

    private fun parse(h: String): Map<String, String> =
        Regex("""(\w+)=(?:"([^"]*)"|([^,\s]+))""").findAll(h.removePrefix("Digest").trim())
            .associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
