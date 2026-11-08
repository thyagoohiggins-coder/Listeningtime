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

/**
 * Ligado pelo sistema quando o "Acesso a notificações" está ativo.
 * A cada segundo verifica se algum app de streaming está reproduzindo (MediaSession)
 * e, se estiver, conta o tempo e mostra o cronômetro por cima da tela.
 */
class MediaWatcherService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    @Volatile private var controllers: List<MediaController> = emptyList()
    private lateinit var overlay: OverlayController
    private lateinit var fg: ForegroundDetector
    private var connected = false

    // Estado da sessão atual
    private var activePkg: String? = null
    private var sessionSeconds = 0L
    private var lastTick = 0L
    private var lastSeenPlaying = 0L
    private var carry = 0L // milissegundos ainda não somados

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
        overlay = OverlayController(this)
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
        flush()
        overlay.hide()
        cancelNotification()
    }

    /** Pacote de streaming que está tocando agora, ou null. */
    private fun playingPackage(): String? {
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        if (!screenOn) return null
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
                flush()
                activePkg = pkg
                sessionSeconds = 0
                carry = 0
            } else {
                carry += delta
            }
            lastSeenPlaying = now
            val whole = carry / 1000
            if (whole > 0) {
                carry -= whole * 1000
                sessionSeconds += whole
                Tracker.add(this, pkg, whole)
            }
            val name = StreamingApps.nameOf(pkg)
            val total = Tracker.todayFor(this, name)
            overlay.show("⏱ $name  ${Tracker.format(sessionSeconds)}  ·  hoje ${Tracker.formatShort(total)}")
            if (!continuing) showNotification(name)
        } else {
            if (overlay.isShowing) overlay.hide()
            if (activePkg != null && now - lastSeenPlaying >= GRACE_MS) {
                flush()
                activePkg = null
                cancelNotification()
            }
        }
    }

    private fun flush() { carry = 0 }

    // ---- Notificação com cronômetro do sistema ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tempo de tela", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun showNotification(name: String) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("Assistindo $name")
            .setContentText("Tempo de tela contando em segundo plano")
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - sessionSeconds * 1000)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    private fun cancelNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    companion object {
        private const val CHANNEL = "screen_time"
        private const val NOTIF_ID = 42
        /** Pausas menores que isso continuam a mesma sessão. */
        private const val GRACE_MS = 60_000L

        @Volatile var running = false

        fun isEnabled(ctx: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(ctx, MediaWatcherService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
