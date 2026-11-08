package io.github.thyagoohiggins.listeningtime

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.content.ComponentName
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()

        heading("Listening Time", 26f)
        para("Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 12f, 0xFF777777.toInt())
        para(
            "Conta o tempo de tela enquanto você assiste Netflix, Prime Video, YouTube, " +
                "HBO Max, Crunchyroll, Apple TV e Disney+, e mostra um cronômetro por cima do vídeo."
        )

        val listenerOn = MediaWatcherService.isEnabled(this)
        val overlayOn = Settings.canDrawOverlays(this)
        val notifOn = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val usageOn = ForegroundDetector.hasPermission(this)
        val batteryOn = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        section("Permissões")
        permission(
            "1. Acesso a notificações (obrigatório)",
            "Necessário para saber quando um vídeo está sendo reproduzido.\n" +
                "Se aparecer \"Configuração restrita\" (Android 13+), toque em \"Liberar restrição\" " +
                "abaixo, depois no menu ⋮ → \"Permitir configurações restritas\" e tente de novo.",
            listenerOn
        ) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!listenerOn) {
            button("Liberar restrição (informações do app)") { openAppDetails() }
        }
        permission("2. Sobrepor a outros apps", "Mostra o cronômetro flutuante por cima do vídeo.", overlayOn) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permission("3. Notificações", "Mostra o cronômetro também na barra de notificações.", notifOn) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        permission(
            "4. Sem restrição de bateria",
            "Evita que o sistema feche o contador em segundo plano.",
            batteryOn
        ) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        permission(
            "5. Acesso ao uso (opcional)",
            "Só é usado se você ligar a opção \"Contar com o app aberto\" abaixo.",
            usageOn
        ) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        section("Opções")
        switch("Mostrar cronômetro flutuante", AppSettings.overlayEnabled(this)) {
            AppSettings.setOverlayEnabled(this, it)
        }
        switch(
            "Contar com o app aberto na tela (para apps que não informam a reprodução)",
            AppSettings.foregroundFallback(this)
        ) {
            AppSettings.setForegroundFallback(this, it)
            if (it && !ForegroundDetector.hasPermission(this)) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        section("Hoje")
        val stats = Tracker.todayAll(this)
        val total = stats.values.sum()
        stats.forEach { (name, secs) -> para("$name: ${Tracker.format(secs)}") }
        heading("Total: ${Tracker.format(total)}", 18f)
        para(
            if (listenerOn) "Status: monitorando ✅" else "Status: desativado — conceda o acesso a notificações",
            13f, 0xFF555555.toInt()
        )
        button("Atualizar") { render() }
        if (listenerOn) {
            button("Reiniciar monitoramento") {
                NotificationListenerService.requestRebind(ComponentName(this, MediaWatcherService::class.java))
            }
        }

        section("Atualizações")
        para(
            "Baixe o APK mais novo nas Releases do GitHub e instale por cima — os dados são mantidos.",
            13f
        )
        button("Abrir página de Releases") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
        }
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    // ---- helpers de UI ----

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun heading(t: String, size: Float) {
        content.addView(TextView(this).apply {
            text = t; textSize = size; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4)); setTextColor(0xFF111111.toInt())
        })
    }

    private fun section(t: String) {
        content.addView(TextView(this).apply {
            text = t.uppercase(); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFE50914.toInt()); setPadding(0, dp(24), 0, dp(4))
        })
    }

    private fun para(t: String, size: Float = 15f, color: Int = 0xFF222222.toInt()) {
        content.addView(TextView(this).apply {
            text = t; textSize = size; setTextColor(color); setPadding(0, dp(2), 0, dp(2))
        })
    }

    private fun button(label: String, onClick: () -> Unit) {
        content.addView(Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }

    private fun permission(label: String, desc: String, granted: Boolean, onClick: () -> Unit) {
        heading((if (granted) "✅ " else "⚠️ ") + label, 16f)
        para(desc, 13f, 0xFF555555.toInt())
        if (!granted) button("Conceder") { onClick() }
    }

    @Suppress("DEPRECATION")
    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        content.addView(Switch(this).apply {
            text = label; isChecked = checked; textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    companion object {
        const val RELEASES_URL = "https://github.com/thyagoohiggins-coder/Listeningtime/releases/latest"
    }
}
