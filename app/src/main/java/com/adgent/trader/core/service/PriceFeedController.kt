package com.adgent.trader.core.service

import android.content.Context
import android.content.Intent
import com.adgent.trader.appContainer
import com.adgent.trader.data.DataMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * API statica per avviare/fermare il feed realtime (usata da UI, boot, widget).
 * La modalità è letta dalle impostazioni: REALTIME avvia il foreground service,
 * RISPARMIO lo ferma (gli alert passano al polling WorkManager a 15 min).
 */
object PriceFeedController {

    fun onBootCompleted(context: Context) {
        if (isRealtime(context)) start(context)
    }

    /** Applica la modalità corrente delle impostazioni (chiamato da Settings UI). */
    fun applyMode(context: Context, mode: DataMode) {
        if (mode == DataMode.REALTIME) start(context) else stop(context)
    }

    fun isRealtime(context: Context): Boolean = runBlocking {
        runCatching { context.appContainer.settingsRepo.settings.first().dataMode }
            .getOrDefault(DataMode.SAVER) == DataMode.REALTIME
    }

    fun start(context: Context) {
        val intent = Intent(context, PriceFeedService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, PriceFeedService::class.java).apply { action = PriceFeedService.ACTION_STOP }
        context.startService(intent)
    }
}
