package io.github.thyagoohiggins.listeningtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.max

/** Cores do painel (tema escuro "tech"). */
object Ui {
    const val BG = 0xFF0B0F17.toInt()
    const val CARD = 0xFF121826.toInt()
    const val STROKE = 0xFF1F2A3C.toInt()
    const val GRID = 0xFF1C2535.toInt()
    const val HILITE = 0xFF1A2436.toInt()
    const val TEXT = 0xFFE6EDF7.toInt()
    const val TEXT2 = 0xFF8B98AE.toInt()
    const val MUTED = 0xFF5B6880.toInt()
    const val ACCENT = 0xFF3DD6F5.toInt()
    const val LIVE = 0xFF34D399.toInt()
}

private fun Context.px(dp: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

/** Desenha um retângulo com apenas os cantos de cima arredondados. */
private fun Canvas.topRounded(r: RectF, radius: Float, p: Paint) {
    val rad = minOf(radius, r.height() / 2, r.width() / 2)
    val path = Path().apply {
        addRoundRect(r, floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW)
    }
    drawPath(path, p)
}

/**
 * Barras empilhadas: horas por dia do mês, cada segmento é um app.
 * Toque numa barra para selecionar o dia.
 */
class DayBarChart(ctx: Context) : View(ctx) {
    var stats: Tracker.Stats? = null
        set(v) { field = v; invalidate() }
    var selectedDay: Int = 0
        set(v) { field = v; invalidate() }
    var todayDay: Int = 0
    var onSelect: ((Int) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = Ui.GRID; strokeWidth = ctx.px(1f) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT2; textSize = ctx.px(10f); typeface = Typeface.MONOSPACE
    }
    private val padL = ctx.px(30f)
    private val padB = ctx.px(18f)
    private val padT = ctx.px(8f)
    private val gap = ctx.px(1f) // ~2px em telas comuns
    private val radius = ctx.px(3f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.px(190f).toInt())
    }

    private fun slotWidth(s: Tracker.Stats) = (width - padL) / s.daysInMonth

    /** Teto do eixo em horas "redondas". */
    private fun yMaxHours(s: Tracker.Stats): Int = max(1, ceil(s.maxDayTotal / 3600.0).toInt())

    override fun onDraw(c: Canvas) {
        val s = stats ?: return
        val plotH = height - padB - padT
        val baseY = height - padB
        val yMax = yMaxHours(s)
        val step = when { yMax <= 4 -> 1; yMax <= 8 -> 2; yMax <= 16 -> 4; else -> 6 }

        // grade e rótulos do eixo Y (recessivos)
        label.textAlign = Paint.Align.LEFT
        var h = 0
        while (h <= yMax) {
            val y = baseY - plotH * h / yMax
            c.drawLine(padL, y, width.toFloat(), y, grid)
            c.drawText("${h}h", 0f, y + label.textSize / 3, label)
            h += step
        }

        val slot = slotWidth(s)
        val barW = max(context.px(3f), slot * 0.62f)
        label.textAlign = Paint.Align.CENTER

        for (d in 1..s.daysInMonth) {
            val cx = padL + slot * (d - 0.5f)
            if (d == selectedDay) {
                fill.color = Ui.HILITE
                c.drawRect(cx - slot / 2, padT, cx + slot / 2, baseY, fill)
            }
            // segmentos na ordem fixa dos apps, de baixo para cima
            var top = baseY
            val segs = StreamingApps.apps.mapNotNull { a ->
                s.byDay[d][a.name]?.takeIf { it > 0 }?.let { a.color to it }
            }
            segs.forEachIndexed { i, (color, secs) ->
                val segH = plotH * (secs / 3600f) / yMax
                val r = RectF(cx - barW / 2, top - segH, cx + barW / 2, top)
                fill.color = color
                if (i == segs.lastIndex) c.topRounded(r, radius, fill) else c.drawRect(r, fill)
                top -= segH + gap
            }
            // rótulos do eixo X: 1, 5, 10, 15... + hoje + selecionado
            if (d == 1 || d % 5 == 0 || d == todayDay || d == selectedDay) {
                label.color = when (d) { selectedDay -> Ui.TEXT; todayDay -> Ui.ACCENT; else -> Ui.MUTED }
                c.drawText("$d", cx, height - context.px(4f), label)
                label.color = Ui.TEXT2
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = stats ?: return false
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE || e.action == MotionEvent.ACTION_UP) {
            parent?.requestDisallowInterceptTouchEvent(e.action != MotionEvent.ACTION_UP)
            val d = ((e.x - padL) / slotWidth(s)).toInt() + 1
            if (d in 1..s.daysInMonth && d != selectedDay) {
                selectedDay = d
                onSelect?.invoke(d)
            }
            return true
        }
        return super.onTouchEvent(e)
    }
}

/** Barra horizontal empilhada (um dia, ou um "medidor" de um app). */
class StackBar(ctx: Context, heightDp: Float = 8f) : View(ctx) {
    /** pares (cor, valor) */
    var segments: List<Pair<Int, Long>> = emptyList()
        set(v) { field = v; invalidate() }
    /** valor que corresponde à largura total */
    var maxValue: Long = 1
        set(v) { field = v; invalidate() }

    private val h = ctx.px(heightDp)
    private val gap = ctx.px(1f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = ctx.px(2f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        p.color = Ui.GRID
        c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, p)
        if (maxValue <= 0) return
        var x = 0f
        for ((color, v) in segments) {
            if (v <= 0) continue
            val w = width * (v.toFloat() / maxValue)
            p.color = color
            c.drawRoundRect(x, 0f, minOf(width.toFloat(), x + w), h, r, r, p)
            x += w + gap
        }
    }
}
