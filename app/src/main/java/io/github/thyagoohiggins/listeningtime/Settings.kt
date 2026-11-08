package io.github.thyagoohiggins.listeningtime

import android.content.Context

object AppSettings {
    private const val PREFS = "settings"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun overlayEnabled(ctx: Context) = p(ctx).getBoolean("overlay", true)
    fun setOverlayEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("overlay", v).apply()

    /** Conta também quando o app está aberto na tela (para apps que não informam a reprodução). */
    fun foregroundFallback(ctx: Context) = p(ctx).getBoolean("fg_fallback", false)
    fun setForegroundFallback(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("fg_fallback", v).apply()

    fun overlayX(ctx: Context) = p(ctx).getInt("ox", 24)
    fun overlayY(ctx: Context) = p(ctx).getInt("oy", 160)
    fun setOverlayPos(ctx: Context, x: Int, y: Int) = p(ctx).edit().putInt("ox", x).putInt("oy", y).apply()
}
