package io.github.thyagoohiggins.listeningtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/** Balão flutuante (arrastável) com o cronômetro, desenhado por cima do vídeo. */
class OverlayController(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(text: String) {
        if (!AppSettings.overlayEnabled(ctx) || !Settings.canDrawOverlays(ctx)) {
            hide(); return
        }
        view?.let { it.text = text; return }

        val dp = { v: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
        }
        val tv = TextView(ctx).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundResource(R.drawable.overlay_bg)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            alpha = 0.85f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = AppSettings.overlayX(ctx)
            y = AppSettings.overlayY(ctx)
        }

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; touchX = ev.rawX; touchY = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (ev.rawX - touchX).toInt()
                    lp.y = startY + (ev.rawY - touchY).toInt()
                    runCatching { wm.updateViewLayout(v, lp) }; true
                }
                MotionEvent.ACTION_UP -> {
                    AppSettings.setOverlayPos(ctx, lp.x, lp.y); true
                }
                else -> false
            }
        }

        try {
            wm.addView(tv, lp)
            view = tv; params = lp
        } catch (_: Exception) {
            view = null
        }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
