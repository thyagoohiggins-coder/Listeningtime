package io.github.thyagoohiggins.listeningtime

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val ptBR = Locale("pt", "BR")

    // mês exibido
    private var year = 0
    private var month = 0 // 1..12
    private var selectedDay = 0

    private var lastLive: String? = "?"

    /** Redesenha ao abrir, quando começa/para um vídeo e a cada 10 s durante a reprodução. */
    private val refresher = object : Runnable {
        var ticks = 0
        override fun run() {
            val live = MediaWatcherService.liveApp
            if (live != lastLive || (live != null && ++ticks % 10 == 0)) {
                lastLive = live
                render()
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = Calendar.getInstance()
        year = now.get(Calendar.YEAR)
        month = now.get(Calendar.MONTH) + 1
        selectedDay = now.get(Calendar.DAY_OF_MONTH)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Ui.BG)
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
        lastLive = MediaWatcherService.liveApp
        handler.postDelayed(refresher, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun isCurrentMonth(): Boolean {
        val n = Calendar.getInstance()
        return n.get(Calendar.YEAR) == year && n.get(Calendar.MONTH) + 1 == month
    }

    private fun shiftMonth(delta: Int) {
        val c = Calendar.getInstance().apply { clear(); set(year, month - 1, 1); add(Calendar.MONTH, delta) }
        year = c.get(Calendar.YEAR); month = c.get(Calendar.MONTH) + 1
        selectedDay = if (isCurrentMonth()) Calendar.getInstance().get(Calendar.DAY_OF_MONTH) else 1
        render()
    }

    // =====================================================================

    private fun render() {
        val y = scroll.scrollY
        content.removeAllViews()
        val s = Tracker.stats(this, year, month)
        if (selectedDay !in 1..s.daysInMonth) selectedDay = 1

        header()
        liveCard()
        permissionsCard()

        // ---- totais gerais ----
        row(
            tile("HOJE", Tracker.hm(s.todayTotal), "todos os apps"),
            tile("TOTAL GERAL", Tracker.hm(s.allTotal), "${s.activeDaysAll} dias com uso"),
        )

        // ---- navegação do mês ----
        monthNav()
        val avg = if (s.activeDaysInMonth > 0) s.monthTotal / s.activeDaysInMonth else 0
        row(
            tile("NO MÊS", Tracker.hm(s.monthTotal), "${s.activeDaysInMonth} dias ativos"),
            tile("MÉDIA/DIA", Tracker.hm(avg), "dias com uso"),
            tile("RECORDE", s.bestDay?.let { Tracker.hm(s.dayTotal(it)) } ?: "—",
                s.bestDay?.let { "dia $it" } ?: "sem dados"),
        )

        // ---- gráfico por dia ----
        card {
            sectionTitle(it, "HORAS POR DIA DO MÊS")
            val chart = DayBarChart(this).apply {
                stats = s
                selectedDay = this@MainActivity.selectedDay
                todayDay = if (isCurrentMonth()) Calendar.getInstance().get(Calendar.DAY_OF_MONTH) else 0
            }
            it.addView(chart, lp(top = 8))
            val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            it.addView(detail, lp(top = 10))
            fun fillDetail(d: Int) {
                detail.removeAllViews()
                dayDetail(detail, s, d)
            }
            fillDetail(selectedDay)
            chart.onSelect = { d -> selectedDay = d; fillDetail(d) }
            legend(it, s.monthByApp.keys)
        }

        // ---- por app ----
        card {
            sectionTitle(it, "POR APP")
            val maxMonth = s.monthByApp.values.maxOrNull() ?: 0L
            val maxAll = s.allByApp.values.maxOrNull() ?: 0L
            val apps = StreamingApps.apps.sortedByDescending { a -> s.allByApp[a.name] ?: 0 }
            for (a in apps) appRow(it, a, s, maxMonth, maxAll)
        }

        // ---- tabela: dias do mês ----
        card {
            sectionTitle(it, "DIAS DO MÊS")
            val days = (s.daysInMonth downTo 1).filter { d -> s.dayTotal(d) > 0 }
            if (days.isEmpty()) {
                it.addView(text("Nenhum registro neste mês.", 13f, Ui.TEXT2), lp(top = 8))
            }
            for (d in days) dayRow(it, s, d)
        }

        optionsCard()

        scroll.post { scroll.scrollTo(0, y) }
    }

    // ---- blocos ----------------------------------------------------------

    private fun header() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text("LISTENING TIME  //  v${BuildConfig.VERSION_NAME}", 11f, Ui.ACCENT, mono = true, spacing = 0.15f))
        box.addView(text("Painel de tempo de tela", 24f, Ui.TEXT, bold = true), lp(top = 4))
        content.addView(box, lp(bottom = 12))
    }

    private fun liveCard() {
        val app = MediaWatcherService.liveApp
        card(border = if (app != null) StreamingApps.colorOf(app) else Ui.STROKE) {
            if (app != null) {
                val color = StreamingApps.colorOf(app)
                val head = hbox()
                head.addView(dot(Ui.LIVE))
                head.addView(text("AO VIVO", 11f, Ui.LIVE, mono = true, bold = true, spacing = 0.15f), lp(left = 8))
                head.addView(text("  ·  $app", 13f, Ui.TEXT2))
                it.addView(head)
                val chrono = Chronometer(this).apply {
                    base = MediaWatcherService.liveBase
                    setTextColor(Ui.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    start()
                }
                it.addView(chrono, lp(top = 4))
                it.addView(StackBar(this, 3f).apply { segments = listOf(color to 1L); maxValue = 1 }, lp(top = 6))
            } else {
                val on = MediaWatcherService.isEnabled(this)
                val head = hbox()
                head.addView(dot(if (on) Ui.MUTED else 0xFFE34948.toInt()))
                head.addView(
                    text(if (on) "MONITORANDO" else "DESATIVADO", 11f, Ui.TEXT2, mono = true, bold = true, spacing = 0.15f),
                    lp(left = 8)
                )
                it.addView(head)
                it.addView(
                    text(
                        if (on) "Nenhum vídeo em reprodução. Abra Netflix, Prime Video, YouTube, HBO Max, Crunchyroll, Apple TV ou Disney+."
                        else "Conceda o acesso a notificações abaixo para começar a contar.",
                        13f, Ui.TEXT2
                    ),
                    lp(top = 6)
                )
            }
        }
    }

    private fun permissionsCard() {
        val listenerOn = MediaWatcherService.isEnabled(this)
        val notifOn = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOn = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        if (listenerOn && notifOn && batteryOn) return

        card(border = 0xFF6B4E16.toInt()) {
            sectionTitle(it, "CONFIGURAÇÃO PENDENTE")
            if (!listenerOn) {
                permRow(it, "Acesso a notificações", "Obrigatório: é assim que o app sabe que um vídeo está tocando.") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                it.addView(
                    text(
                        "Android 13+: se aparecer \"Configuração restrita\", toque aqui → ⋮ → \"Permitir configurações restritas\" e tente de novo.",
                        12f, Ui.ACCENT
                    ).apply {
                        setOnClickListener {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }
                    },
                    lp(top = 2, bottom = 6)
                )
            }
            if (!notifOn) {
                permRow(it, "Notificações", "Para mostrar o cronômetro na barra de notificações.") {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
            }
            if (!batteryOn) {
                permRow(it, "Sem restrição de bateria", "Evita que o sistema feche o contador.") {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            }
        }
    }

    private fun monthNav() {
        val title = SimpleDateFormat("MMMM yyyy", ptBR).format(
            Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }.time
        ).uppercase(ptBR)
        val bar = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(chip("‹") { shiftMonth(-1) })
        bar.addView(
            text(title, 14f, Ui.TEXT, mono = true, bold = true, spacing = 0.1f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(chip("›") { if (!isCurrentMonth()) shiftMonth(1) }.apply { alpha = if (isCurrentMonth()) 0.3f else 1f })
        content.addView(bar, lp(top = 18, bottom = 8))
    }

    private fun dayDetail(box: LinearLayout, s: Tracker.Stats, d: Int) {
        val date = Calendar.getInstance().apply { clear(); set(year, month - 1, d) }.time
        val label = SimpleDateFormat("EEEE, dd/MM", ptBR).format(date)
        val head = hbox()
        head.addView(text(label.replaceFirstChar { it.uppercase() }, 13f, Ui.TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(Tracker.hm(s.dayTotal(d)), 13f, Ui.TEXT, mono = true, bold = true))
        box.addView(head)
        val items = StreamingApps.apps.mapNotNull { a -> s.byDay[d][a.name]?.takeIf { it > 0 }?.let { a to it } }
        if (items.isEmpty()) {
            box.addView(text("Sem registros neste dia.", 12f, Ui.TEXT2), lp(top = 4))
        }
        for ((a, secs) in items) {
            val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(dot(a.color))
            r.addView(text(a.name, 12f, Ui.TEXT2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            r.addView(text(Tracker.hm(secs), 12f, Ui.TEXT2, mono = true))
            box.addView(r, lp(top = 4))
        }
    }

    private fun legend(box: LinearLayout, present: Set<String>) {
        val apps = StreamingApps.apps.filter { it.name in present }
        if (apps.size < 2) return
        val flow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var line = hbox()
        apps.forEachIndexed { i, a ->
            if (i > 0 && i % 3 == 0) { flow.addView(line, lp(top = 4)); line = hbox() }
            val item = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            item.addView(dot(a.color))
            item.addView(text(a.name, 11f, Ui.TEXT2), lp(left = 6))
            line.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        flow.addView(line, lp(top = 4))
        box.addView(flow, lp(top = 12))
    }

    private fun appRow(box: LinearLayout, a: StreamApp, s: Tracker.Stats, maxMonth: Long, maxAll: Long) {
        val monthV = s.monthByApp[a.name] ?: 0
        val allV = s.allByApp[a.name] ?: 0
        val todayV = s.todayByApp[a.name] ?: 0
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; alpha = if (allV > 0) 1f else 0.45f }
        val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(dot(a.color))
        head.addView(text(a.name, 14f, Ui.TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        head.addView(text(Tracker.hm(monthV), 14f, Ui.TEXT, mono = true, bold = true))
        r.addView(head)
        r.addView(StackBar(this, 6f).apply {
            segments = listOf(a.color to monthV); maxValue = maxOf(1, maxMonth)
        }, lp(top = 6))
        val foot = hbox()
        foot.addView(text("hoje ${Tracker.hm(todayV)}", 11f, Ui.TEXT2, mono = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        foot.addView(text("total ${Tracker.hm(allV)}", 11f, Ui.TEXT2, mono = true))
        r.addView(foot, lp(top = 4))
        box.addView(r, lp(top = 14))
    }

    private fun dayRow(box: LinearLayout, s: Tracker.Stats, d: Int) {
        val date = Calendar.getInstance().apply { clear(); set(year, month - 1, d) }.time
        val r = hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { selectedDay = d; render() }
        }
        r.addView(text(String.format(Locale.US, "%02d", d), 13f, if (d == selectedDay) Ui.ACCENT else Ui.TEXT, mono = true, bold = true),
            LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(text(SimpleDateFormat("EEE", ptBR).format(date).take(3), 11f, Ui.TEXT2, mono = true),
            LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(StackBar(this, 8f).apply {
            segments = StreamingApps.apps.map { a -> a.color to (s.byDay[d][a.name] ?: 0L) }
            maxValue = maxOf(1, s.maxDayTotal)
        }, LinearLayout.LayoutParams(0, dp(8), 1f))
        r.addView(text(Tracker.hm(s.dayTotal(d)), 12f, Ui.TEXT, mono = true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(r)
    }

    private fun optionsCard() {
        card {
            sectionTitle(it, "OPÇÕES")
            @Suppress("DEPRECATION")
            val sw = Switch(this).apply {
                text = "Contar com o app aberto na tela (para apps que não informam a reprodução)"
                setTextColor(Ui.TEXT2); textSize = 13f
                isChecked = AppSettings.foregroundFallback(this@MainActivity)
                setOnCheckedChangeListener { _, v ->
                    AppSettings.setForegroundFallback(this@MainActivity, v)
                    if (v && !ForegroundDetector.hasPermission(this@MainActivity)) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
            }
            it.addView(sw, lp(top = 8))
            val btns = hbox()
            btns.addView(chip("Reiniciar monitoramento") {
                NotificationListenerService.requestRebind(ComponentName(this, MediaWatcherService::class.java))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            btns.addView(chip("Atualizações") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            it.addView(btns, lp(top = 12))
            it.addView(text("Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 11f, Ui.MUTED, mono = true), lp(top = 10))
        }
    }

    // ---- helpers de UI ---------------------------------------------------

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lp(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun bg(fill: Int, stroke: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke)
    }

    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun text(
        t: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false, spacing: Float = 0f,
    ) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = spacing
    }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    private fun card(border: Int = Ui.STROKE, build: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Ui.CARD, border)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        build(c)
        content.addView(c, lp(top = 10))
    }

    private fun sectionTitle(box: LinearLayout, t: String) {
        box.addView(text(t, 11f, Ui.TEXT2, mono = true, bold = true, spacing = 0.15f))
    }

    private fun tile(label: String, value: String, sub: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Ui.CARD, Ui.STROKE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(text(label, 10f, Ui.TEXT2, mono = true, bold = true, spacing = 0.12f))
            addView(text(value, 20f, Ui.TEXT, mono = true, bold = true).apply { maxLines = 1 }, lp(top = 4))
            addView(text(sub, 10f, Ui.MUTED).apply { maxLines = 1 }, lp(top = 2))
        }

    private fun row(vararg tiles: View) {
        val r = hbox()
        tiles.forEachIndexed { i, t ->
            r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
        content.addView(r, lp(top = 10))
    }

    private fun chip(label: String, onClick: () -> Unit) = text(label, 14f, Ui.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        background = bg(Ui.CARD, Ui.STROKE, 10)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
    }

    private fun permRow(box: LinearLayout, title: String, desc: String, onClick: () -> Unit) {
        val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 14f, Ui.TEXT, bold = true))
        col.addView(text(desc, 12f, Ui.TEXT2))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(chip("Conceder", onClick), lp(left = 8).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
        box.addView(r, lp(top = 10))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    companion object {
        const val RELEASES_URL = "https://github.com/thyagoohiggins-coder/Listeningtime/releases/latest"
    }
}
