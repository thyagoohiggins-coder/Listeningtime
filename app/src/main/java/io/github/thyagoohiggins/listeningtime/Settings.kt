package io.github.thyagoohiggins.listeningtime

import android.content.Context

object AppSettings {
    private const val PREFS = "settings"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Conta também quando o app está aberto na tela (para apps que não informam a reprodução). */
    fun foregroundFallback(ctx: Context) = p(ctx).getBoolean("fg_fallback", false)
    fun setForegroundFallback(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("fg_fallback", v).apply()

    /** Apps cuja contagem foi desligada pelo usuário (pelo nome, ex.: "YouTube"). */
    fun disabledApps(ctx: Context): Set<String> = p(ctx).getStringSet("disabled_apps", emptySet()) ?: emptySet()

    fun isAppEnabled(ctx: Context, name: String) = name !in disabledApps(ctx)

    fun setAppEnabled(ctx: Context, name: String, enabled: Boolean) {
        val set = disabledApps(ctx).toMutableSet()
        if (enabled) set.remove(name) else set.add(name)
        p(ctx).edit().putStringSet("disabled_apps", set).apply()
        MediaWatcherService.instance?.onPauseChanged()
    }
}

/** Pausa manual da contagem (botão no app e na notificação). */
object PauseState {
    private const val PREFS = "settings"
    fun isPaused(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("paused", false)
    fun set(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("paused", v).apply()
        MediaWatcherService.instance?.onPauseChanged()
    }
    fun toggle(ctx: Context) = set(ctx, !isPaused(ctx))
}
