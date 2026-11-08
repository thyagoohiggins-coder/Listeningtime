package io.github.thyagoohiggins.listeningtime

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guarda os segundos assistidos por dia e por app (agrupado pelo nome do serviço). */
object Tracker {
    private const val PREFS = "usage"

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun key(day: String, app: String) = "t|$day|$app"

    fun add(ctx: Context, pkg: String, seconds: Long) {
        if (seconds <= 0) return
        val app = StreamingApps.nameOf(pkg)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(today(), app)
        p.edit().putLong(k, p.getLong(k, 0) + seconds).apply()
    }

    fun todayFor(ctx: Context, appName: String): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key(today(), appName), 0)

    fun todayAll(ctx: Context): Map<String, Long> =
        StreamingApps.displayNames.associateWith { todayFor(ctx, it) }

    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun formatShort(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
}
