package io.github.thyagoohiggins.listeningtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Tokens do visual HUD (os mesmos da extensão de navegador). */
object Ui {
    const val BG = 0xFF07090D.toInt()
    const val PANEL = 0xFF0C1118.toInt()
    const val PANEL2 = 0xFF111821.toInt()
    const val LINE = 0xFF1B2633.toInt()
    const val LINE_HI = 0xFF2A3A4D.toInt()
    const val TEXT = 0xFFE6EDF7.toInt()
    const val TEXT2 = 0xFF8B98AE.toInt()
    const val MUTED = 0xFF5B6880.toInt()
    const val ACCENT = 0xFF3DD6F5.toInt()
    const val LIVE = 0xFF34D399.toInt()
    const val WARN = 0xFFF5B841.toInt()
    const val DARK_INK = 0xFF07090D.toInt()
    /** Rampa sequencial (azul) do mapa de calor: menos -> mais. */
    val HEAT = intArrayOf(PANEL2, 0xFF173A63.toInt(), 0xFF1C5CAB.toInt(), 0xFF2A78D6.toInt(), 0xFF5598E7.toInt(), 0xFF9EC5F4.toInt())
    val MONO: Typeface = Typeface.MONOSPACE
    val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

fun Context.px(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
private fun Context.sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Fundo com grade sutil (fixo atrás do conteúdo). */
class GridDrawable(private val ctx: Context) : Drawable() {
    private val p = Paint().apply { color = 0x093DD6F5; strokeWidth = 1f }
    override fun draw(c: Canvas) {
        c.drawColor(Ui.BG)
        val step = ctx.px(24f)
        var x = 0f
        while (x < bounds.width()) { c.drawLine(x, 0f, x, bounds.height().toFloat(), p); x += step }
        var y = 0f
        while (y < bounds.height()) { c.drawLine(0f, y, bounds.width().toFloat(), y, p); y += step }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Painel HUD: borda fina + colchetes nos cantos superior-esquerdo e inferior-direito. */
class HudPanel(ctx: Context, var bracket: Int = Ui.ACCENT) : LinearLayout(ctx) {
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(1f); color = Ui.LINE }
    private val br = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(2f); strokeCap = Paint.Cap.SQUARE }
    private val len = ctx.px(12f)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setBackgroundColor(Ui.PANEL)
        val p = ctx.px(16f).toInt()
        setPadding(p, p, p, p)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat(); val o = br.strokeWidth / 2
        c.drawRect(border.strokeWidth / 2, border.strokeWidth / 2, w - border.strokeWidth / 2, h - border.strokeWidth / 2, border)
        br.color = bracket
        c.drawLine(o, o, len, o, br); c.drawLine(o, o, o, len, br)
        c.drawLine(w - len, h - o, w - o, h - o, br); c.drawLine(w - o, h - len, w - o, h - o, br)
    }
}

/** Mostrador circular: o dia dividido por app, com marcações de relógio. */
class RingView(ctx: Context, private val sizeDp: Float = 200f) : View(ctx) {
    var values: Map<String, Long> = emptyMap()
        set(v) { field = v; invalidate() }
    var title: String = "HOJE"
    var sub: String = "de streaming"

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onMeasure(w: Int, h: Int) {
        val s = context.px(sizeDp).toInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat(); val cx = s / 2
        val r = s * 0.383f; val sw = s * 0.058f
        for (i in 0 until 48) {
            val a = Math.toRadians(i * 7.5 - 90).toFloat()
            val major = i % 12 == 0
            val r1 = r + sw / 2 + s * 0.03f
            val r2 = r1 + if (major) s * 0.05f else s * 0.025f
            tick.color = if (major) Ui.ACCENT else Ui.LINE_HI
            tick.strokeWidth = context.px(if (major) 1.5f else 0.8f)
            c.drawLine(cx + r1 * cos(a), cx + r1 * sin(a), cx + r2 * cos(a), cx + r2 * sin(a), tick)
        }
        arc.strokeWidth = sw
        arc.color = Ui.LINE
        val box = RectF(cx - r, cx - r, cx + r, cx + r)
        c.drawArc(box, 0f, 360f, false, arc)
        val total = values.values.sum()
        if (total > 0) {
            val items = StreamingApps.apps.filter { (values[it.name] ?: 0L) > 0 }
            val gapDeg = if (items.size > 1) Math.toDegrees((context.px(3f) / r).toDouble()).toFloat() else 0f
            var start = -90f
            for (a in items) {
                val sweep = 360f * (values[a.name]!!.toFloat() / total)
                arc.color = a.color
                c.drawArc(box, start, max(0.5f, sweep - gapDeg), false, arc)
                start += sweep
            }
        }
        txt.typeface = Ui.MONO; txt.color = Ui.TEXT2; txt.textSize = s * 0.05f; txt.letterSpacing = 0.15f
        c.drawText(title, cx, cx - s * 0.09f, txt)
        txt.typeface = Ui.MONO_BOLD; txt.color = Ui.TEXT; txt.textSize = s * 0.125f; txt.letterSpacing = 0f
        c.drawText(Tracker.hm(total), cx, cx + s * 0.05f, txt)
        if (sub.isNotEmpty()) {
            txt.typeface = Ui.MONO; txt.color = Ui.MUTED; txt.textSize = s * 0.045f
            c.drawText(sub, cx, cx + s * 0.14f, txt)
        }
    }
}

/** Fileira de blocos (medidor segmentado). */
class BlocksView(ctx: Context) : View(ctx) {
    var states: List<Boolean> = emptyList()
        set(v) { field = v; invalidate() }
    private val p = Paint()
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.px(8f).toInt())
    override fun onDraw(c: Canvas) {
        if (states.isEmpty()) return
        val gap = context.px(3f)
        val bw = (width - gap * (states.size - 1)) / states.size
        states.forEachIndexed { i, on ->
            p.color = if (on) Ui.ACCENT else Ui.PANEL2
            val x = i * (bw + gap)
            c.drawRect(x, 0f, x + bw, height.toFloat(), p)
        }
    }
}

/** Calendário do mês como mapa de calor: cada célula mostra o dia e as horas. */
class CalendarHeatView(ctx: Context) : View(ctx) {
    var stats: Tracker.Stats? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var selectedDay = 0
        set(v) { field = v; invalidate() }
    var todayDay = 0
    var onSelect: ((Int) -> Unit)? = null

    private val gap = ctx.px(3f)
    private val headH = ctx.px(18f)
    private val fill = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wd = arrayOf("D", "S", "T", "Q", "Q", "S", "S")

    private fun firstWeekday(s: Tracker.Stats): Int =
        Calendar.getInstance().run { clear(); set(s.year, s.month - 1, 1); get(Calendar.DAY_OF_WEEK) - 1 }

    private fun cellW() = (width - gap * 6) / 7
    private fun cellH(cw: Float) = if (cw < context.px(56f)) cw else cw / 1.35f
    private fun rows(s: Tracker.Stats) = ceil((firstWeekday(s) + s.daysInMonth) / 7.0).toInt()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val s = stats
        val cw = (width - gap * 6) / 7
        val r = if (s != null) rows(s) else 5
        setMeasuredDimension(width, (headH + r * cellH(cw) + (r - 1) * gap).toInt())
    }

    private fun level(s: Tracker.Stats, secs: Long): Int =
        if (secs <= 0 || s.maxDayTotal <= 0) 0 else max(1, ceil(secs * 5.0 / s.maxDayTotal).toInt()).coerceAtMost(5)

    override fun onDraw(c: Canvas) {
        val s = stats ?: return
        val cw = cellW(); val ch = cellH(cw)
        val first = firstWeekday(s)
        val now = Calendar.getInstance()
        txt.typeface = Ui.MONO; txt.textSize = context.sp(9f); txt.color = Ui.MUTED; txt.textAlign = Paint.Align.CENTER
        for (i in 0 until 7) c.drawText(wd[i], i * (cw + gap) + cw / 2, headH - context.px(6f), txt)
        for (d in 1..s.daysInMonth) {
            val idx = first + d - 1
            val col = idx % 7; val row = idx / 7
            val x = col * (cw + gap); val y = headH + row * (ch + gap)
            val secs = s.dayTotal(d)
            val lv = level(s, secs)
            val future = Calendar.getInstance().apply { clear(); set(s.year, s.month - 1, d) }.after(now)
            fill.color = Ui.HEAT[lv]
            fill.alpha = if (future) 90 else 255
            c.drawRect(x, y, x + cw, y + ch, fill)
            val ink = if (lv >= 4) Ui.DARK_INK else Ui.TEXT
            txt.textAlign = Paint.Align.LEFT
            txt.typeface = Ui.MONO; txt.textSize = context.sp(9f)
            txt.color = if (lv >= 4) 0xFF1B2633.toInt() else Ui.TEXT2
            txt.alpha = if (future) 90 else 255
            c.drawText(String.format("%02d", d), x + context.px(4f), y + context.px(12f), txt)
            if (secs > 0) {
                txt.typeface = Ui.MONO_BOLD; txt.textSize = context.sp(if (cw < context.px(56f)) 9f else 11f); txt.color = ink
                txt.textAlign = Paint.Align.RIGHT
                c.drawText(Tracker.tiny(secs), x + cw - context.px(4f), y + ch - context.px(5f), txt)
            }
            if (d == todayDay) {
                stroke.color = Ui.ACCENT; stroke.strokeWidth = context.px(1f)
                c.drawRect(x + 0.5f, y + 0.5f, x + cw - 0.5f, y + ch - 0.5f, stroke)
            }
            if (d == selectedDay) {
                stroke.color = Ui.TEXT; stroke.strokeWidth = context.px(2f)
                val o = context.px(1.5f)
                c.drawRect(x - o, y - o, x + cw + o, y + ch + o, stroke)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = stats ?: return false
        if (e.action == MotionEvent.ACTION_UP) {
            val cw = cellW(); val ch = cellH(cw)
            if (e.y < headH) return true
            val col = (e.x / (cw + gap)).toInt().coerceIn(0, 6)
            val row = ((e.y - headH) / (ch + gap)).toInt()
            val d = row * 7 + col - firstWeekday(s) + 1
            val future = Calendar.getInstance().apply { clear(); set(s.year, s.month - 1, d) }.after(Calendar.getInstance())
            if (d in 1..s.daysInMonth && !future) { selectedDay = d; onSelect?.invoke(d) }
        }
        return true
    }
}

/** Linha do tempo: barras empilhadas por app, uma por dia do mês. Toque/arraste para selecionar. */
class DayBarChart(ctx: Context) : View(ctx) {
    var stats: Tracker.Stats? = null
        set(v) { field = v; invalidate() }
    var selectedDay: Int = 0
        set(v) { field = v; invalidate() }
    var todayDay: Int = 0
    var onSelect: ((Int) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = Ui.LINE; strokeWidth = ctx.px(1f) }
    private val dashed = Paint().apply {
        color = Ui.LINE; strokeWidth = ctx.px(1f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(ctx.px(2f), ctx.px(4f)), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.sp(9f); typeface = Ui.MONO }
    private val padL = ctx.px(28f)
    private val padB = ctx.px(18f)
    private val padT = ctx.px(6f)
    private val gap = ctx.px(1f)
    private val radius = ctx.px(2.5f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.px(180f).toInt())
    }

    private fun slot(s: Tracker.Stats) = (width - padL) / s.daysInMonth

    override fun onDraw(c: Canvas) {
        val s = stats ?: return
        val plotH = height - padB - padT
        val base = height - padB
        val yMax = max(1, ceil(s.maxDayTotal / 3600.0).toInt())
        val step = when { yMax <= 4 -> 1; yMax <= 8 -> 2; yMax <= 16 -> 4; else -> 6 }
        label.textAlign = Paint.Align.LEFT; label.color = Ui.MUTED
        var h = 0
        while (h <= yMax) {
            val y = base - plotH * h / yMax
            if (h == 0) c.drawLine(padL, y, width.toFloat(), y, grid)
            else c.drawPath(Path().apply { moveTo(padL, y); lineTo(width.toFloat(), y) }, dashed)
            c.drawText("${h}h", 0f, y + label.textSize / 3, label)
            h += step
        }
        val sl = slot(s)
        val bw = max(context.px(3f), sl * 0.56f)
        label.textAlign = Paint.Align.CENTER
        for (d in 1..s.daysInMonth) {
            val cx = padL + sl * (d - 0.5f)
            if (d == selectedDay) {
                fill.color = 0x123DD6F5
                c.drawRect(cx - sl / 2, padT, cx + sl / 2, base, fill)
            }
            var top = base
            val segs = StreamingApps.apps.mapNotNull { a -> s.byDay[d][a.name]?.takeIf { it > 0 }?.let { a.color to it } }
            segs.forEachIndexed { i, (color, secs) ->
                val segH = plotH * (secs / 3600f) / yMax
                val r = RectF(cx - bw / 2, top - segH, cx + bw / 2, top)
                fill.color = color
                if (i == segs.lastIndex) {
                    val rad = min(radius, min(r.height() / 2, r.width() / 2))
                    c.drawPath(Path().apply {
                        addRoundRect(r, floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW)
                    }, fill)
                } else c.drawRect(r, fill)
                top -= segH + gap
            }
            if (d == 1 || d % 5 == 0 || d == todayDay || d == selectedDay) {
                label.color = when (d) { selectedDay -> Ui.TEXT; todayDay -> Ui.ACCENT; else -> Ui.MUTED }
                c.drawText("$d", cx, height - context.px(4f), label)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = stats ?: return false
        parent?.requestDisallowInterceptTouchEvent(e.action != MotionEvent.ACTION_UP && e.action != MotionEvent.ACTION_CANCEL)
        val d = ((e.x - padL) / slot(s)).toInt() + 1
        val future = todayDay in 1 until d
        if (d in 1..s.daysInMonth && d != selectedDay && !future) {
            selectedDay = d
            onSelect?.invoke(d)
        }
        return true
    }
}

/** Barra horizontal segmentada, com 1dp de respiro entre segmentos. */
class StackBar(ctx: Context, heightDp: Float = 8f) : View(ctx) {
    var segments: List<Pair<Int, Long>> = emptyList()
        set(v) { field = v; invalidate() }
    var maxValue: Long = 1
        set(v) { field = v; invalidate() }

    private val h = ctx.px(heightDp)
    private val gap = ctx.px(1f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        p.color = Ui.PANEL2
        c.drawRect(0f, 0f, width.toFloat(), h, p)
        if (maxValue <= 0) return
        var x = 0f
        for ((color, v) in segments) {
            if (v <= 0) continue
            val w = max(context.px(2f), width * (v.toFloat() / maxValue))
            p.color = color
            c.drawRect(x, 0f, min(width.toFloat(), x + w), h, p)
            x += w + gap
        }
    }
}
