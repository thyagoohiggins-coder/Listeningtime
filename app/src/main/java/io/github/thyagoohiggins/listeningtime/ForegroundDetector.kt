package io.github.thyagoohiggins.listeningtime

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** Descobre qual app está na tela usando o "Acesso ao uso" (opcional). */
class ForegroundDetector(private val ctx: Context) {
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private var lastQuery = System.currentTimeMillis() - 60 * 60 * 1000L
    private var foreground: String? = null

    @Suppress("DEPRECATION")
    fun current(): String? {
        if (!hasPermission(ctx)) return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now) ?: return foreground
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> foreground = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND ->
                    if (foreground == e.packageName) foreground = null
            }
        }
        lastQuery = now
        return foreground
    }

    companion object {
        fun hasPermission(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
