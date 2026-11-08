package io.github.thyagoohiggins.listeningtime

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Guarda os segundos assistidos por dia e por app.
 * Chave: "t|yyyy-MM-dd|Nome do app" -> segundos.
 */
object Tracker {
    private const val PREFS = "usage"

    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = fmt().format(Date())
    private fun key(day: String, app: String) = "t|$day|$app"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(ctx: Context, pkg: String, seconds: Long) {
        if (seconds <= 0) return
        val app = StreamingApps.nameOf(pkg)
        val p = prefs(ctx)
        val k = key(today(), app)
        p.edit().putLong(k, p.getLong(k, 0) + seconds).apply()
    }

    fun todayFor(ctx: Context, app: String): Long = prefs(ctx).getLong(key(today(), app), 0)

    /** Total do app no mês atual. */
    fun monthFor(ctx: Context, app: String): Long {
        val prefix = "t|" + today().substring(0, 8)
        val suffix = "|$app"
        return prefs(ctx).all.entries.sumOf { (k, v) ->
            if (k.startsWith(prefix) && k.endsWith(suffix)) (v as? Long) ?: 0L else 0L
        }
    }

    data class Entry(val day: String, val app: String, val secs: Long)

    fun entries(ctx: Context): List<Entry> = prefs(ctx).all.mapNotNull { (k, v) ->
        val parts = k.split("|")
        val secs = v as? Long
        if (parts.size == 3 && parts[0] == "t" && secs != null) Entry(parts[1], parts[2], secs) else null
    }

    /** Estatísticas de um mês (month = 1..12). */
    fun stats(ctx: Context, year: Int, month: Int): Stats = Stats(entries(ctx), year, month)

    class Stats(entries: List<Entry>, val year: Int, val month: Int) {
        val daysInMonth: Int = Calendar.getInstance().run {
            clear(); set(year, month - 1, 1); getActualMaximum(Calendar.DAY_OF_MONTH)
        }
        private val prefix = String.format(Locale.US, "%04d-%02d-", year, month)
        private val todayKey = today()

        /** byDay[d] = segundos por app no dia d (1..daysInMonth). */
        val byDay: Array<MutableMap<String, Long>> = Array(daysInMonth + 1) { mutableMapOf() }
        val monthByApp = mutableMapOf<String, Long>()
        val allByApp = mutableMapOf<String, Long>()
        val todayByApp = mutableMapOf<String, Long>()
        val allDays = mutableSetOf<String>()

        init {
            for (e in entries) {
                allByApp.merge(e.app, e.secs, { a, b -> a + b })
                if (e.secs > 0) allDays += e.day
                if (e.day == todayKey) todayByApp.merge(e.app, e.secs, { a, b -> a + b })
                if (e.day.startsWith(prefix)) {
                    val d = e.day.substring(8).toIntOrNull() ?: continue
                    if (d in 1..daysInMonth) {
                        byDay[d].merge(e.app, e.secs, { a, b -> a + b })
                        monthByApp.merge(e.app, e.secs, { a, b -> a + b })
                    }
                }
            }
        }

        fun dayTotal(d: Int): Long = byDay[d].values.sum()
        val monthTotal: Long get() = monthByApp.values.sum()
        val allTotal: Long get() = allByApp.values.sum()
        val todayTotal: Long get() = todayByApp.values.sum()
        val activeDaysInMonth: Int get() = (1..daysInMonth).count { dayTotal(it) > 0 }
        val activeDaysAll: Int get() = allDays.size
        val bestDay: Int? get() = (1..daysInMonth).maxByOrNull { dayTotal(it) }?.takeIf { dayTotal(it) > 0 }
        val maxDayTotal: Long get() = (1..daysInMonth).maxOfOrNull { dayTotal(it) } ?: 0L
    }

    // ---- formatação ----

    /** Relógio: 1:02:03 ou 02:03 */
    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Duração compacta: 2h 05m, 45m, <1m, 0m */
    fun hm(seconds: Long): String {
        if (seconds in 1..59) return "<1m"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) String.format(Locale.US, "%dh %02dm", h, m) else "${m}m"
    }
}
