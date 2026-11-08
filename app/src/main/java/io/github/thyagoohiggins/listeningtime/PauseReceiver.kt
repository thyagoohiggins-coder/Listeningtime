package io.github.thyagoohiggins.listeningtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Recebe o toque nos botões "Pausar / Retomar contagem" da notificação. */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE -> PauseState.set(ctx, true)
            ACTION_RESUME -> PauseState.set(ctx, false)
        }
    }

    companion object {
        const val ACTION_PAUSE = "io.github.thyagoohiggins.listeningtime.PAUSE"
        const val ACTION_RESUME = "io.github.thyagoohiggins.listeningtime.RESUME"
    }
}
