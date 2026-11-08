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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/** Painel estilo HUD — mesmo visual da extensão de navegador. */
class MainActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var year = 0
    private var month = 0 // 1..12
    private var selectedDay = 0
    private var lastLive: String? = "?"

    /** Redesenha ao abrir, quando começa/para/pausa um vídeo e a cada 10 s durante a reprodução. */
    private val refresher = object : Runnable {
        var ticks = 0
        override fun run() {
            val live = liveKey()
            if (live != lastLive || (MediaWatcherService.liveApp != null && ++ticks % 10 == 0)) {
                lastLive = live
                render()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun liveKey() = MediaWatcherService.liveApp + "|" + PauseState.isPaused(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = Calendar.getInstance()
        year = now.get(Calendar.YEAR)
        month = now.get(Calendar.MONTH) + 1
        selectedDay = now.get(Calendar.DAY_OF_MONTH)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(40))
        }
        scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            background = GridDrawable(this@MainActivity)
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
        lastLive = liveKey()
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

    private fun todayDay() = if (isCurrentMonth()) Calendar.getInstance().get(Calendar.DAY_OF_MONTH) else 0

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
        val now = Calendar.getInstance()
        val cur = if (isCurrentMonth()) s else Tracker.stats(this, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
        if (selectedDay !in 1..s.daysInMonth) selectedDay = 1

        header()
        permissionsPanel()
        todayPanel(cur)
        totalsPanel(s)
        calendarPanel(s)
        timelinePanel(s)
        rankPanel(s)
        logPanel(s)
        systemPanel()

        scroll.post { scroll.scrollTo(0, y) }
    }

    // ---- cabeçalho ------------------------------------------------------

    private fun header() {
        val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val col = vbox()
        val name = hbox()
        name.addView(text("LISTENING", 17f, Ui.TEXT, bold = true, spacing = 0.22f))
        name.addView(text(" TIME", 17f, Ui.ACCENT, bold = true, spacing = 0.22f))
        col.addView(name)
        col.addView(text("// telemetria de tempo de tela", 11f, Ui.MUTED), lp(top = 2))
        row.addView(col, weight())
        content.addView(row, lp(bottom = 6))
        content.addView(statusLine(), lp(bottom = 8))
    }

    private fun statusLine(): View {
        val app = MediaWatcherService.liveApp
        val paused = PauseState.isPaused(this)
        val on = MediaWatcherService.isEnabled(this)
        val (color, label) = when {
            paused -> Ui.WARN to ("CONTAGEM PAUSADA" + (app?.let { " · ${it.uppercase()}" } ?: ""))
            app != null -> Ui.LIVE to "AO VIVO · ${app.uppercase()}"
            on -> Ui.TEXT2 to "MONITORANDO · NENHUM VÍDEO"
            else -> 0xFFE34948.toInt() to "DESATIVADO · CONCEDA O ACESSO"
        }
        val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        r.addView(dot(if (color == Ui.TEXT2) Ui.MUTED else color))
        r.addView(text(label, 11f, color, spacing = 0.16f), lp(left = 8))
        return r
    }

    // ---- 01 hoje --------------------------------------------------------

    private fun todayPanel(s: Tracker.Stats) {
        panel("01", "HOJE") { p ->
            val ring = RingView(this, 210f).apply { values = s.todayByApp }
            p.addView(FrameLayout(this).apply {
                addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }, lp(top = 4))
            val items = StreamingApps.apps.filter { (s.todayByApp[it.name] ?: 0) > 0 }
                .sortedByDescending { s.todayByApp[it.name] }
            if (items.isEmpty()) p.addView(text("Nada assistido hoje ainda.", 12f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, lp(top = 8))
            for (a in items) {
                val v = s.todayByApp[a.name] ?: 0
                val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
                r.addView(dot(a.color))
                r.addView(text(a.name, 12f, Ui.TEXT), weight(left = 8))
                r.addView(text(Tracker.hm(v), 12f, Ui.TEXT))
                r.addView(text("${v * 100 / maxOf(1, s.todayTotal)}%", 11f, Ui.MUTED).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
                p.addView(r, lp(top = 8))
            }

            // medidores
            p.addView(dashedRule(), lp(top = 16, bottom = 14))
            val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            val prev = (1 until today).map { s.dayTotal(it) }.filter { it > 0 }
            val avg = if (prev.isNotEmpty()) prev.sum() / prev.size else 0L
            val pct = if (avg > 0) (s.todayTotal * 100 / avg).toInt() else -1
            val g = hbox()
            g.addView(gauge("HOJE VS. MÉDIA", if (pct < 0) "—" else "$pct%",
                List(10) { it < (if (pct < 0) 0 else minOf(10, (pct + 5) / 10)) }, "média ${Tracker.hm(avg)}/dia"), weight())
            g.addView(gauge("SEQUÊNCIA", "${s.streak} ${if (s.streak == 1) "dia" else "dias"}", s.last7, "últimos 7 dias"), weight(left = 14))
            p.addView(g)

            // ao vivo
            p.addView(dashedRule(), lp(top = 16, bottom = 14))
            liveBox(p)
        }
    }

    private fun gauge(k: String, v: String, blocks: List<Boolean>, sub: String): View = vbox().apply {
        addView(text(k, 10f, Ui.TEXT2, spacing = 0.14f))
        addView(text(v, 20f, Ui.TEXT, bold = true), lp(top = 2, bottom = 6))
        addView(BlocksView(this@MainActivity).apply { states = blocks })
        addView(text(sub, 10f, Ui.MUTED), lp(top = 4))
    }

    private fun liveBox(p: LinearLayout) {
        val app = MediaWatcherService.liveApp
        val paused = PauseState.isPaused(this)
        p.addView(statusLine())
        val chrono = Chronometer(this).apply {
            base = if (app != null) MediaWatcherService.liveBase else android.os.SystemClock.elapsedRealtime()
            setTextColor(if (app == null || paused) Ui.MUTED else Ui.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Ui.MONO_BOLD
            if (app != null && !paused) {
                setShadowLayer(dp(14).toFloat(), 0f, 0f, (StreamingApps.colorOf(app) and 0x00FFFFFF) or 0x88000000.toInt())
                start()
            }
        }
        p.addView(chrono, lp(top = 4))
        p.addView(text(if (app != null) "sessão atual" else "o cronômetro inicia quando um vídeo começa a tocar", 11f, Ui.TEXT2))
        p.addView(pauseButton(paused), lp(top = 14))
    }

    private fun pauseButton(paused: Boolean): View =
        text(if (paused) "▶  RETOMAR CONTAGEM" else "❚❚  PAUSAR CONTAGEM", 12f, if (paused) 0xFF1A1205.toInt() else Ui.TEXT,
            bold = true, spacing = 0.12f).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                if (paused) setColor(Ui.WARN) else { setColor(0x00000000); setStroke(dp(1), Ui.LINE_HI) }
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { PauseState.toggle(this@MainActivity); render() }
        }

    // ---- 02 acumulado ---------------------------------------------------

    private fun totalsPanel(s: Tracker.Stats) {
        panel("02", "ACUMULADO") { p ->
            val nav = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            nav.addView(navButton("‹", true) { shiftMonth(-1) })
            nav.addView(text(monthTitle(), 13f, Ui.TEXT, bold = true, spacing = 0.14f).apply { gravity = Gravity.CENTER }, weight())
            nav.addView(navButton("›", !isCurrentMonth()) { shiftMonth(1) })
            p.addView(nav, lp(bottom = 14))
            val avg = if (s.activeDaysInMonth > 0) s.monthTotal / s.activeDaysInMonth else 0
            val r1 = hbox(); val r2 = hbox()
            r1.addView(readout("TOTAL GERAL", Tracker.hm(s.allTotal), "${s.activeDaysAll} dias com uso", Ui.ACCENT, false), weight())
            r1.addView(readout("NO MÊS", Tracker.hm(s.monthTotal), "${s.activeDaysInMonth} dias ativos", Ui.TEXT, true), weight())
            r2.addView(readout("MÉDIA/DIA", Tracker.hm(avg), "por dia ativo", Ui.TEXT, false), weight())
            r2.addView(readout("RECORDE", s.bestDay?.let { Tracker.hm(s.maxDayTotal) } ?: "—",
                s.bestDay?.let { "dia %02d".format(it) } ?: "sem dados", Ui.TEXT, true), weight())
            p.addView(r1); p.addView(r2, lp(top = 14))
        }
    }

    private fun readout(k: String, v: String, sub: String, color: Int, divider: Boolean): View = vbox().apply {
        if (divider) {
            setPadding(dp(14), 0, 0, 0)
            background = object : android.graphics.drawable.Drawable() {
                val paint = android.graphics.Paint().apply { this.color = Ui.LINE }
                override fun draw(c: android.graphics.Canvas) { c.drawRect(0f, 0f, dp(1).toFloat(), bounds.height().toFloat(), paint) }
                override fun setAlpha(a: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        }
        addView(text(k, 10f, Ui.TEXT2, spacing = 0.14f))
        addView(text(v, 22f, color, bold = true).apply { maxLines = 1 }, lp(top = 4))
        addView(text(sub, 10f, Ui.MUTED), lp(top = 2))
    }

    private fun navButton(label: String, enabled: Boolean, onClick: () -> Unit): View =
        text(label, 16f, if (enabled) Ui.TEXT else Ui.MUTED, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0); setStroke(dp(1), Ui.LINE_HI) }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            alpha = if (enabled) 1f else 0.35f
            if (enabled) setOnClickListener { onClick() }
        }

    private fun monthTitle(): String {
        val names = arrayOf("JANEIRO", "FEVEREIRO", "MARÇO", "ABRIL", "MAIO", "JUNHO", "JULHO", "AGOSTO",
            "SETEMBRO", "OUTUBRO", "NOVEMBRO", "DEZEMBRO")
        return "${names[month - 1]} $year"
    }

    // ---- 03 calendário --------------------------------------------------

    private fun calendarPanel(s: Tracker.Stats) {
        panel("03", "CALENDÁRIO · HORAS POR DIA") { p ->
            val cal = CalendarHeatView(this).apply {
                stats = s; selectedDay = this@MainActivity.selectedDay; todayDay = todayDay()
            }
            p.addView(cal, lp(top = 2))
            val scale = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            scale.addView(text("MENOS", 9f, Ui.MUTED, spacing = 0.12f))
            for (c in Ui.HEAT) scale.addView(View(this).apply { setBackgroundColor(c) },
                LinearLayout.LayoutParams(dp(14), dp(10)).apply { leftMargin = dp(4) })
            scale.addView(text("MAIS", 9f, Ui.MUTED, spacing = 0.12f), lp(left = 4).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
            scale.addView(View(this), weight())
            scale.addView(text("máx. ${Tracker.hm(s.maxDayTotal)}", 10f, Ui.MUTED))
            p.addView(scale, lp(top = 10))
            p.addView(dashedRule(), lp(top = 14, bottom = 12))
            val detail = vbox()
            p.addView(detail)
            dayDetail(detail, s, selectedDay)
            cal.onSelect = { d -> selectedDay = d; render() }
        }
    }

    private fun dayDetail(box: LinearLayout, s: Tracker.Stats, d: Int) {
        val wd = arrayOf("DOM", "SEG", "TER", "QUA", "QUI", "SEX", "SÁB")
        val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, d) }
        val head = hbox()
        head.addView(text("${wd[cal.get(Calendar.DAY_OF_WEEK) - 1]} %02d/%02d".format(d, month), 13f, Ui.TEXT, bold = true), weight())
        head.addView(text(Tracker.hm(s.dayTotal(d)), 13f, Ui.TEXT, bold = true))
        box.addView(head)
        val items = StreamingApps.apps.mapNotNull { a -> s.byDay[d][a.name]?.takeIf { it > 0 }?.let { a to it } }
        if (items.isEmpty()) { box.addView(text("Sem registros neste dia.", 12f, Ui.TEXT2), lp(top = 6)); return }
        box.addView(StackBar(this, 8f).apply {
            segments = items.map { it.first.color to it.second }; maxValue = s.dayTotal(d)
        }, lp(top = 8))
        for ((a, secs) in items) {
            val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(dot(a.color))
            r.addView(text(a.name, 12f, Ui.TEXT2), weight(left = 8))
            r.addView(text(Tracker.hm(secs), 12f, Ui.TEXT2))
            box.addView(r, lp(top = 8))
        }
    }

    // ---- 04 linha do tempo ----------------------------------------------

    private fun timelinePanel(s: Tracker.Stats) {
        panel("04", "LINHA DO TEMPO") { p ->
            val chart = DayBarChart(this).apply {
                stats = s; selectedDay = this@MainActivity.selectedDay; todayDay = todayDay()
            }
            p.addView(chart, lp(top = 4))
            val hint = text(selectedLabel(s), 11f, Ui.TEXT2)
            p.addView(hint, lp(top = 8))
            chart.onSelect = { d -> selectedDay = d; hint.text = selectedLabel(s) }
            legend(p, s.monthByApp.filterValues { it > 0 }.keys)
        }
    }

    private fun selectedLabel(s: Tracker.Stats) =
        "Dia %02d · %s".format(selectedDay, Tracker.hm(s.dayTotal(selectedDay))) +
            StreamingApps.apps.mapNotNull { a -> s.byDay[selectedDay][a.name]?.takeIf { it > 0 }?.let { " · ${a.name} ${Tracker.hm(it)}" } }.joinToString("")

    private fun legend(box: LinearLayout, present: Set<String>) {
        val apps = StreamingApps.apps.filter { it.name in present }
        if (apps.size < 2) return
        var line = hbox()
        val flow = vbox()
        apps.forEachIndexed { i, a ->
            if (i > 0 && i % 3 == 0) { flow.addView(line, lp(top = 6)); line = hbox() }
            val item = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            item.addView(dot(a.color))
            item.addView(text(a.name, 10f, Ui.TEXT2), lp(left = 6))
            line.addView(item, weight())
        }
        flow.addView(line, lp(top = 6))
        box.addView(flow, lp(top = 6))
    }

    // ---- 05 ranking -----------------------------------------------------

    private fun rankPanel(s: Tracker.Stats) {
        panel("05", "RANKING DO MÊS") { p ->
            val rows = StreamingApps.apps.sortedWith(
                compareByDescending<StreamApp> { s.monthByApp[it.name] ?: 0 }.thenByDescending { s.allByApp[it.name] ?: 0 }
            )
            val max = maxOf(1L, rows.maxOf { s.monthByApp[it.name] ?: 0 })
            rows.forEachIndexed { i, a ->
                val m = s.monthByApp[a.name] ?: 0
                val share = if (s.monthTotal > 0) m * 100 / s.monthTotal else 0
                val r = hbox().apply {
                    alpha = if (m > 0 || (s.allByApp[a.name] ?: 0) > 0) 1f else 0.4f
                    setPadding(0, dp(10), 0, dp(10))
                }
                r.addView(text("%02d".format(i + 1), 18f, Ui.MUTED, bold = true), LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
                val col = vbox()
                val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
                head.addView(dot(a.color))
                head.addView(text(a.name, 13f, Ui.TEXT, bold = true), weight(left = 8))
                head.addView(text(Tracker.hm(m), 13f, Ui.TEXT, bold = true))
                col.addView(head)
                col.addView(StackBar(this, 4f).apply { segments = listOf(a.color to m); maxValue = max }, lp(top = 6))
                col.addView(text("$share% · hoje ${Tracker.hm(s.todayByApp[a.name] ?: 0)} · total ${Tracker.hm(s.allByApp[a.name] ?: 0)}",
                    10f, Ui.MUTED), lp(top = 4))
                r.addView(col, weight())
                p.addView(r)
                if (i < rows.lastIndex) p.addView(rule())
            }
        }
    }

    // ---- 06 registro diário ---------------------------------------------

    private fun logPanel(s: Tracker.Stats) {
        panel("06", "REGISTRO DIÁRIO") { p ->
            val wd = arrayOf("DOM", "SEG", "TER", "QUA", "QUI", "SEX", "SÁB")
            val days = (s.daysInMonth downTo 1).filter { s.dayTotal(it) > 0 }
            if (days.isEmpty()) p.addView(text("Nenhum registro neste mês.", 12f, Ui.TEXT2))
            for (d in days) {
                val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, d) }
                val sel = d == selectedDay
                val r = hbox().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(8), dp(4), dp(8))
                    if (sel) background = GradientDrawable().apply { setColor(Ui.PANEL2) }
                    setOnClickListener { selectedDay = d; render() }
                }
                if (sel) r.addView(View(this).apply { setBackgroundColor(Ui.ACCENT) }, LinearLayout.LayoutParams(dp(2), dp(16)).apply { rightMargin = dp(6) })
                r.addView(text("%02d".format(d), 12f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT))
                r.addView(text(wd[cal.get(Calendar.DAY_OF_WEEK) - 1], 10f, Ui.MUTED), LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
                r.addView(StackBar(this, 6f).apply {
                    segments = StreamingApps.apps.map { a -> a.color to (s.byDay[d][a.name] ?: 0L) }
                    maxValue = maxOf(1, s.maxDayTotal)
                }, LinearLayout.LayoutParams(0, dp(6), 1f))
                r.addView(text(Tracker.hm(s.dayTotal(d)), 12f, Ui.TEXT, bold = true).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
                p.addView(r)
            }
        }
    }

    // ---- 07 sistema / permissões ----------------------------------------

    private fun systemPanel() {
        panel("07", "SISTEMA", bracket = Ui.LINE_HI) { p ->
            @Suppress("DEPRECATION")
            val sw = Switch(this).apply {
                text = "Contar com o app aberto na tela (para apps que não informam a reprodução)"
                setTextColor(Ui.TEXT2); textSize = 12f; typeface = Ui.MONO
                isChecked = AppSettings.foregroundFallback(this@MainActivity)
                setOnCheckedChangeListener { _, v ->
                    AppSettings.setForegroundFallback(this@MainActivity, v)
                    if (v && !ForegroundDetector.hasPermission(this@MainActivity)) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
            }
            p.addView(sw)
            val btns = hbox()
            btns.addView(outlineButton("REINICIAR MONITOR") {
                NotificationListenerService.requestRebind(ComponentName(this, MediaWatcherService::class.java))
            }, weight())
            btns.addView(outlineButton("ATUALIZAÇÕES") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
            }, weight(left = 8))
            p.addView(btns, lp(top = 14))
            p.addView(text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · dados guardados só neste aparelho", 10f, Ui.MUTED), lp(top = 12))
        }
    }

    private fun permissionsPanel() {
        val listenerOn = MediaWatcherService.isEnabled(this)
        val notifOn = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOn = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        if (listenerOn && notifOn && batteryOn) return

        panel("!!", "CONFIGURAÇÃO PENDENTE", bracket = Ui.WARN) { p ->
            if (!listenerOn) {
                permRow(p, "Acesso a notificações", "Obrigatório: é assim que o app sabe que um vídeo está tocando.") {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                p.addView(text("Android 13+: se aparecer \"Configuração restrita\", toque aqui → ⋮ → \"Permitir configurações restritas\".",
                    11f, Ui.ACCENT).apply {
                    setOnClickListener {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }, lp(top = 6, bottom = 4))
            }
            if (!notifOn) permRow(p, "Notificações", "Para mostrar o cronômetro na barra de notificações.") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            if (!batteryOn) permRow(p, "Sem restrição de bateria", "Evita que o sistema feche o contador.") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun permRow(box: LinearLayout, title: String, desc: String, onClick: () -> Unit) {
        val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val col = vbox()
        col.addView(text(title, 13f, Ui.TEXT, bold = true))
        col.addView(text(desc, 11f, Ui.TEXT2), lp(top = 2))
        r.addView(col, weight())
        r.addView(outlineButton("CONCEDER", onClick), lp(left = 10).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
        box.addView(r, lp(top = 10))
    }

    // ---- helpers --------------------------------------------------------

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lp(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun weight(left: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(left) }

    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false, spacing: Float = 0f) =
        TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (bold) Ui.MONO_BOLD else Ui.MONO
            letterSpacing = spacing
            includeFontPadding = false
        }

    private fun dot(color: Int) = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { gravity = Gravity.CENTER_VERTICAL }
    }

    private fun rule() = View(this).apply {
        setBackgroundColor(Ui.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun dashedRule() = View(this).apply {
        background = object : android.graphics.drawable.Drawable() {
            val paint = android.graphics.Paint().apply {
                color = Ui.LINE_HI; strokeWidth = dp(1).toFloat()
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(3).toFloat(), dp(3).toFloat()), 0f)
            }
            override fun draw(c: android.graphics.Canvas) { c.drawLine(0f, 0.5f, bounds.width().toFloat(), 0.5f, paint) }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        minimumHeight = dp(1)
    }

    /** Painel HUD com rótulo "01  TÍTULO ──────". */
    private fun panel(num: String, title: String, bracket: Int = Ui.ACCENT, build: (LinearLayout) -> Unit) {
        val p = HudPanel(this, bracket)
        val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(num, 11f, if (bracket == Ui.WARN) Ui.WARN else Ui.ACCENT, bold = true, spacing = 0.16f))
        head.addView(text(title, 11f, Ui.TEXT2, spacing = 0.16f), lp(left = 10).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
        head.addView(rule(), LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(10) })
        p.addView(head, lp(bottom = 14))
        build(p)
        content.addView(p, lp(top = 14))
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = text(label, 11f, Ui.TEXT, bold = true, spacing = 0.1f).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(0); setStroke(dp(1), Ui.LINE_HI) }
        setPadding(dp(12), dp(11), dp(12), dp(11))
        setOnClickListener { onClick() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    companion object {
        const val RELEASES_URL = "https://github.com/thyagoohiggins-coder/Listeningtime/releases/latest"
    }
}
