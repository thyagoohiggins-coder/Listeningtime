package io.github.thyagoohiggins.listeningtime

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Sincronização com o servidor Cloudflare (pasta sync-worker/).
 * Envia só o que este aparelho contou e recebe o que os outros contaram.
 */
object SyncManager {
    private const val PREFS = "sync"
    private const val INTERVAL_MS = 5 * 60 * 1000L
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastAttempt = 0L
    @Volatile var running = false
        private set

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Device(val name: String, val platform: String, val updatedAt: Long)

    fun url(ctx: Context) = p(ctx).getString("url", null)
    fun code(ctx: Context) = p(ctx).getString("code", null)
    fun name(ctx: Context) = p(ctx).getString("name", null) ?: Build.MODEL ?: "Celular"
    fun isConfigured(ctx: Context) = url(ctx) != null && code(ctx) != null
    fun link(ctx: Context): String? = if (isConfigured(ctx)) "${url(ctx)}/#${code(ctx)}" else null
    fun lastAt(ctx: Context) = p(ctx).getLong("last_at", 0)
    fun lastError(ctx: Context): String? = p(ctx).getString("last_error", null)

    fun devices(ctx: Context): List<Device> = runCatching {
        val a = JSONArray(p(ctx).getString("devices", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Device(o.optString("name"), o.optString("platform"), o.optLong("updatedAt"))
        }
    }.getOrDefault(emptyList())

    private fun deviceId(ctx: Context): String {
        p(ctx).getString("device_id", null)?.let { return it }
        val id = "android-" + newCode().take(12).lowercase()
        p(ctx).edit().putString("device_id", id).apply()
        return id
    }

    fun newCode(): String {
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..24).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** "https://servidor.workers.dev/#CODIGO" -> (url, código) */
    fun parseLink(link: String): Pair<String, String>? = runCatching {
        val u = URI(link.trim())
        val code = u.fragment ?: return null
        if (u.scheme !in listOf("https", "http") || u.host.isNullOrEmpty() || code.length < 16) return null
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port" to code
    }.getOrNull()

    fun configure(ctx: Context, link: String, deviceName: String): Boolean {
        val (url, code) = parseLink(link) ?: return false
        p(ctx).edit().putString("url", url).putString("code", code)
            .putString("name", deviceName.trim().take(40).ifEmpty { Build.MODEL ?: "Celular" })
            .remove("last_error").apply()
        lastAttempt = 0
        return true
    }

    fun disconnect(ctx: Context) {
        p(ctx).edit().remove("url").remove("code").remove("devices").remove("last_at").remove("last_error").apply()
        Tracker.clearRemote(ctx)
    }

    /** Sincroniza se estiver configurado e já passou o intervalo. */
    fun maybeSync(ctx: Context, done: (() -> Unit)? = null) {
        if (isConfigured(ctx) && System.currentTimeMillis() - lastAttempt > INTERVAL_MS) syncAsync(ctx, done)
    }

    fun syncAsync(ctx: Context, done: (() -> Unit)?) {
        if (!isConfigured(ctx) || running) return
        val app = ctx.applicationContext
        lastAttempt = System.currentTimeMillis()
        running = true
        exec.execute {
            try {
                syncBlocking(app)
                p(app).edit().putLong("last_at", System.currentTimeMillis()).remove("last_error").apply()
            } catch (e: Exception) {
                p(app).edit().putString("last_error", e.message ?: e.javaClass.simpleName).apply()
            } finally {
                running = false
                if (done != null) main.post(done)
            }
        }
    }

    private fun syncBlocking(ctx: Context) {
        val body = JSONObject().apply {
            put("device", deviceId(ctx))
            put("name", name(ctx))
            put("platform", "android")
            put("counts", JSONObject().apply { Tracker.ownCounts(ctx).forEach { (k, v) -> put(k, v) } })
            put("clears", JSONObject().apply { Tracker.clears(ctx).forEach { (k, v) -> put(k, v) } })
        }
        val conn = (URL(url(ctx) + "/v1/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000; readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + code(ctx))
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val res = runCatching { JSONObject(text) }.getOrNull()
            if (!ok || res == null) throw RuntimeException(res?.optString("error")?.ifEmpty { null } ?: "HTTP ${conn.responseCode}")

            val clears = mutableMapOf<String, Long>()
            res.optJSONObject("clears")?.let { o -> o.keys().forEach { k -> clears[k] = o.optLong(k) } }
            val sums = mutableMapOf<String, Long>()
            val devs = JSONArray()
            val arr = res.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                d.optJSONObject("counts")?.let { c -> c.keys().forEach { k -> sums[k] = (sums[k] ?: 0L) + c.optLong(k) } }
                devs.put(JSONObject().put("name", d.optString("name")).put("platform", d.optString("platform"))
                    .put("updatedAt", d.optLong("updatedAt")))
            }
            Tracker.applySync(ctx, clears, sums)
            p(ctx).edit().putString("devices", devs.toString()).apply()
        } finally {
            conn.disconnect()
        }
    }
}
