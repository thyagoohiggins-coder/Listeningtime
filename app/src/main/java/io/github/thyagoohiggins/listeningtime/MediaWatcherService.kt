package io.github.thyagoohiggins.listeningtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.widget.RemoteViews
import java.util.Calendar

/**
 * Ligado pelo sistema quando o "Acesso a notificações" está ativo.
 * A cada segundo verifica se algum app de streaming está reproduzindo (MediaSession);
 * se estiver, soma o tempo e mostra uma notificação com cronômetro.
 */
class MediaWatcherService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    @Volatile private var controllers: List<MediaController> = emptyList()
    private lateinit var fg: ForegroundDetector
    private var connected = false

    private var activePkg: String? = null
    private var sessionSeconds = 0L
    private var lastTick = 0L
    private var lastSeenPlaying = 0L
    private var carry = 0L
    private var lastNotifUpdate = 0L
    private var lastNotifKey = ""
    private var notifShowing = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> controllers = list ?: emptyList() }

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onListenerConnected() {
        connected = true
        instance = this
        fg = ForegroundDetector(this)
        createChannel()
        val cn = ComponentName(this, MediaWatcherService::class.java)
        msm = getSystemService(MediaSessionManager::class.java)
        try {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            controllers = msm?.getActiveSessions(cn) ?: emptyList()
        } catch (_: SecurityException) {
        }
        lastTick = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.post(tick)
        running = true
    }

    override fun onListenerDisconnected() {
        stopAll()
        requestRebind(ComponentName(this, MediaWatcherService::class.java))
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun stopAll() {
        if (!connected) return
        connected = false
        running = false
        if (instance === this) instance = null
        handler.removeCallbacks(tick)
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        endSession()
    }

    /** Pacote de streaming que está tocando agora, ou null. */
    /** Como o vídeo foi detectado no último segundo (para o diagnóstico). */
    @Volatile var lastMethod: String = "—"
        private set

    private fun enabledTarget(pkg: String?) =
        pkg != null && StreamingApps.isTarget(pkg) && AppSettings.isAppEnabled(this, StreamingApps.nameOf(pkg))

    /**
     * Pacote de streaming que está tocando agora, ou null. Três formas de detectar:
     * 1) sessão de mídia do app em "tocando" (a mais precisa);
     * 2) app de streaming na tela + áudio de mídia ativo (para apps que não informam a sessão);
     * 3) opcional: app de streaming na tela, mesmo sem áudio.
     * (2) e (3) precisam do "Acesso ao uso".
     */
    private fun playingPackage(): String? {
        if (!getSystemService(PowerManager::class.java).isInteractive) { lastMethod = "tela desligada"; return null }
        for (c in controllers) {
            val pkg = c.packageName
            if (enabledTarget(pkg) && c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                lastMethod = "sessão de mídia"; return pkg
            }
        }
        val f = fg.current()
        if (enabledTarget(f)) {
            if (getSystemService(AudioManager::class.java).isMusicActive) { lastMethod = "app na tela + áudio"; return f }
            if (AppSettings.foregroundFallback(this)) { lastMethod = "app na tela"; return f }
        }
        lastMethod = "nada tocando"
        return null
    }

    /** Texto do diagnóstico mostrado no painel. */
    fun diagnostics(): List<String> {
        val out = mutableListOf<String>()
        val sessions = controllers.map { c ->
            val st = when (c.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "tocando"
                PlaybackState.STATE_PAUSED -> "pausado"
                PlaybackState.STATE_BUFFERING -> "carregando"
                PlaybackState.STATE_STOPPED -> "parado"
                null -> "sem estado"
                else -> "estado ${c.playbackState?.state}"
            }
            val name = if (StreamingApps.isTarget(c.packageName)) StreamingApps.nameOf(c.packageName) else c.packageName
            "$name: $st"
        }
        out += "Sessões de mídia: " + (sessions.ifEmpty { listOf("nenhuma") }.joinToString(" · "))
        out += "App na tela: " + if (!ForegroundDetector.hasPermission(this)) "sem \"Acesso ao uso\""
            else (fg.current()?.let { if (StreamingApps.isTarget(it)) StreamingApps.nameOf(it) else it } ?: "desconhecido")
        out += "Áudio de mídia ativo: " + if (getSystemService(AudioManager::class.java).isMusicActive) "sim" else "não"
        out += "Detecção agora: $lastMethod"
        out += "Última detecção: " + if (lastDetectedAt == 0L) "nenhuma desde que o monitor iniciou"
            else "$lastDetected, há ${(System.currentTimeMillis() - lastDetectedAt) / 1000} s"
        return out
    }

    private var lastRefresh = 0L
    private var lastDetected = ""
    private var lastDetectedAt = 0L

    /** Recarrega a lista de sessões a cada 10 s, caso o aviso do sistema tenha se perdido. */
    private fun refreshSessions(now: Long) {
        if (now - lastRefresh < 10_000) return
        lastRefresh = now
        runCatching {
            controllers = msm?.getActiveSessions(ComponentName(this, MediaWatcherService::class.java)) ?: controllers
        }
    }

    /** Chamado quando o usuário pausa/retoma: atualiza a notificação na hora. */
    fun onPauseChanged() {
        handler.post {
            lastNotifKey = ""
            step(countTime = false)
        }
    }

    private fun step(countTime: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        val delta = if (countTime) (now - lastTick).coerceIn(0, 5000) else 0
        if (countTime) lastTick = now
        val paused = PauseState.isPaused(this)
        livePaused = paused

        if (countTime) { SyncManager.maybeSync(this); refreshSessions(now) }

        // App desligado no meio da sessão: encerra na hora (sem esperar a carência de 1 min).
        activePkg?.let { if (!AppSettings.isAppEnabled(this, StreamingApps.nameOf(it))) endSession() }

        val pkg = playingPackage()
        if (pkg != null) {
            val continuing = pkg == activePkg && now - lastSeenPlaying < GRACE_MS
            if (!continuing) {
                activePkg = pkg
                sessionSeconds = 0
                carry = 0
            } else if (!paused) {
                carry += delta
            }
            lastSeenPlaying = now
            if (paused) carry = 0
            val whole = carry / 1000
            if (whole > 0) {
                carry -= whole * 1000
                sessionSeconds += whole
                Tracker.add(this, pkg, whole)
            }
            val name = StreamingApps.nameOf(pkg)
            lastDetected = "$name via $lastMethod"
            lastDetectedAt = System.currentTimeMillis()
            liveApp = name
            liveBase = now - sessionSeconds * 1000
            updateNotification(name, videoPlaying = true, paused = paused)
        } else {
            liveApp = null
            val last = activePkg
            if (last != null) {
                if (now - lastSeenPlaying >= GRACE_MS) endSession()
                else updateNotification(StreamingApps.nameOf(last), videoPlaying = false, paused = paused)
            }
        }
    }

    /** Só recria a notificação quando o estado muda, ou a cada 30 s (o cronômetro anda sozinho). */
    private fun updateNotification(name: String, videoPlaying: Boolean, paused: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val key = "$name|$videoPlaying|$paused"
        if (notifShowing && key == lastNotifKey && now - lastNotifUpdate < 30_000) return
        lastNotifKey = key
        lastNotifUpdate = now
        showNotification(name, videoPlaying, paused)
    }

    private fun endSession() {
        activePkg = null
        liveApp = null
        carry = 0
        notifShowing = false
        lastNotifKey = ""
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    // ---- Notificação ----

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Tempo de tela", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Cronômetro enquanto você assiste"
            }
        )
    }

    private fun buildViews(layout: Int, name: String, videoPlaying: Boolean, paused: Boolean, big: Boolean): RemoteViews {
        val color = StreamingApps.colorOf(name)
        val today = Tracker.todayFor(this, name)
        val month = Tracker.monthFor(this, name)
        return RemoteViews(packageName, layout).apply {
            setInt(R.id.accent, "setColorFilter", color)
            setTextViewText(
                R.id.label,
                when {
                    paused -> "⏸ ${name.uppercase()} · CONTAGEM PAUSADA"
                    videoPlaying -> "● ${name.uppercase()}"
                    else -> "❚❚ ${name.uppercase()} · VÍDEO PAUSADO"
                }
            )
            setTextColor(R.id.label, if (paused) 0xFF8B98AE.toInt() else color)
            setInt(R.id.accent, "setColorFilter", if (paused) 0xFF5B6880.toInt() else color)
            setChronometer(
                R.id.chrono, SystemClock.elapsedRealtime() - sessionSeconds * 1000, null, videoPlaying && !paused
            )
            setTextViewText(R.id.today, "Hoje ${Tracker.hm(today)}")
            setTextViewText(R.id.month, "Mês ${Tracker.hm(month)}")
            if (big) {
                val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
                val avg = (month - today).coerceAtLeast(0) / maxOf(1, day - 1)
                val pct = if (avg > 0) (today * 100 / avg).toInt() else 100
                setProgressBar(R.id.progress, 1000, (pct * 10).coerceAtMost(1000), false)
                setTextViewText(
                    R.id.progress_label,
                    if (avg > 0) "Hoje vs. sua média diária no mês (${Tracker.hm(avg)}): $pct%"
                    else "Primeiro dia registrado no mês"
                )
                setTextViewText(R.id.total, "Sessão atual: ${Tracker.hm(sessionSeconds)}  ·  Todos os apps hoje: ${Tracker.hm(StreamingApps.apps.sumOf { Tracker.todayFor(this@MediaWatcherService, it.name) })}")
            }
        }
    }

    private fun showNotification(name: String, videoPlaying: Boolean, paused: Boolean) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setColor(StreamingApps.colorOf(name))
            .setSubText(name)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(buildViews(R.layout.notif_small, name, videoPlaying, paused, false))
            .setCustomBigContentView(buildViews(R.layout.notif_big, name, videoPlaying, paused, true))
            .addAction(pauseAction(paused))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(pi)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
            notifShowing = true
        }
    }

    private fun pauseAction(paused: Boolean): Notification.Action {
        val action = if (paused) PauseReceiver.ACTION_RESUME else PauseReceiver.ACTION_PAUSE
        val pi = PendingIntent.getBroadcast(
            this, if (paused) 2 else 1,
            Intent(action).setPackage(packageName).setClass(this, PauseReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, if (paused) R.drawable.ic_play else R.drawable.ic_pause),
            if (paused) "▶ Retomar contagem" else "⏸ Pausar contagem",
            pi
        ).build()
    }

    companion object {
        private const val CHANNEL = "screen_time"
        private const val NOTIF_ID = 42
        /** Pausas menores que isso continuam a mesma sessão. */
        private const val GRACE_MS = 60_000L

        @Volatile var running = false
        /** App sendo assistido agora (para o painel ao vivo). */
        @Volatile var liveApp: String? = null
        /** Base do cronômetro em elapsedRealtime. */
        @Volatile var liveBase: Long = 0L
        /** Contagem pausada pelo usuário. */
        @Volatile var livePaused: Boolean = false
        @Volatile var instance: MediaWatcherService? = null

        fun isEnabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(ctx, MediaWatcherService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
