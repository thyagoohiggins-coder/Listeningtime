package io.github.thyagoohiggins.listeningtime

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService

/**
 * Mantém o monitor vivo: o sistema (ou a economia de bateria do fabricante) às vezes
 * desliga o NotificationListener, principalmente depois de atualizar o APK.
 */
object Watchdog {
    private const val JOB_ID = 4242

    /** Pede ao sistema para religar o monitor, se ele estiver habilitado e desconectado. */
    fun ensureRunning(ctx: Context) {
        if (MediaWatcherService.isEnabled(ctx) && !MediaWatcherService.running) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(ctx, MediaWatcherService::class.java))
            }
        }
    }

    /** Agenda a verificação periódica (a cada 15 min, sobrevive a reinícios). */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchdogJob::class.java))
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(job) }
    }
}

class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Watchdog.ensureRunning(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}

/** Depois de ligar o celular ou de atualizar o app: religa o monitor e reagenda o vigia. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Watchdog.ensureRunning(ctx)
                Watchdog.schedule(ctx)
            }
        }
    }
}
