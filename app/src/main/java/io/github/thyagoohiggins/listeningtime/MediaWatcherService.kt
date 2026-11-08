package io.github.thyagoohiggins.listeningtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        handler.removeCallbacks(tick)
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        endSession()
    }

    /** Pacote de streaming que está tocando agora, ou null. */
    private fun playingPackage(): String? {
        if (!getSystemService(PowerManager::class.java).isInteractive) return null
        for (c in controllers) {
            val pkg = c.packageName
            if (StreamingApps.isTarget(pkg) && c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                return pkg
            }
        }
        if (AppSettings.foregroundFallback(this)) {
            val f = fg.current()
            if (StreamingApps.isTarget(f)) return f
        }
        return null
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val delta = (now - lastTick).coerceIn(0, 5000)
        lastTick = now

        val pkg = playingPackage()
        if (pkg != null) {
            val continuing = pkg == activePkg && now - lastSeenPlaying < GRACE_MS
            if (!continuing) {
                activePkg = pkg
                sessionSeconds = 0
                carry = 0
                lastNotifUpdate = 0
            } else {
                carry += delta
            }
            val wasPaused = now - lastSeenPlaying > 2000
            lastSeenPlaying = now
            val whole = carry / 1000
            if (whole > 0) {
                carry -= whole * 1000
                sessionSeconds += whole
                Tracker.add(this, pkg, whole)
            }
            liveApp = StreamingApps.nameOf(pkg)
            liveBase = now - sessionSeconds * 1000
            // Atualiza ao começar, ao voltar de uma pausa e a cada 30 s (o cronômetro anda sozinho).
            if (!notifShowing || wasPaused || now - lastNotifUpdate > 30_000) {
                showNotification(StreamingApps.nameOf(pkg), playing = true)
                lastNotifUpdate = now
            }
        } else {
            if (liveApp != null && notifShowing && now - lastSeenPlaying in 1500..3500) {
                showNotification(liveApp!!, playing = false) // mostra "pausado"
            }
            liveApp = null
            if (activePkg != null && now - lastSeenPlaying >= GRACE_MS) endSession()
        }
    }

    private fun endSession() {
        activePkg = null
        liveApp = null
        carry = 0
        notifShowing = false
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

    private fun buildViews(layout: Int, name: String, playing: Boolean, big: Boolean): RemoteViews {
        val color = StreamingApps.colorOf(name)
        val today = Tracker.todayFor(this, name)
        val month = Tracker.monthFor(this, name)
        return RemoteViews(packageName, layout).apply {
            setInt(R.id.accent, "setColorFilter", color)
            setTextViewText(
                R.id.label,
                (if (playing) "● ${name.uppercase()}" else "❚❚ ${name.uppercase()} · PAUSADO")
            )
            setTextColor(R.id.label, color)
            setChronometer(R.id.chrono, SystemClock.elapsedRealtime() - sessionSeconds * 1000, null, playing)
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

    private fun showNotification(name: String, playing: Boolean) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setColor(StreamingApps.colorOf(name))
            .setSubText(name)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(buildViews(R.layout.notif_small, name, playing, false))
            .setCustomBigContentView(buildViews(R.layout.notif_big, name, playing, true))
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

        fun isEnabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(ctx, MediaWatcherService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
