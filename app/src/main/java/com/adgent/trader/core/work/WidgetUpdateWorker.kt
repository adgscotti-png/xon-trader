package com.adgent.trader.core.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adgent.trader.appContainer
import com.adgent.trader.ui.widgets.TickerWidget
import com.adgent.trader.ui.widgets.WatchlistWidget
import java.util.concurrent.TimeUnit

/**
 * Aggiorna i widget home screen: refresh dei prezzi (preferiti, fallback top
 * volume) e ridisegno dalla cache Room. Il ridisegno avviene comunque anche a
 * rete assente — il widget mostra l'ultimo dato noto invece di vuotarsi.
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        runCatching {
            val container = context.appContainer
            val symbols = container.watchlistRepo.all().map { it.symbol }
                .ifEmpty { listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT") }
            container.tickerRepo.refreshTickers(symbols, force = false)
        }
        runCatching { TickerWidget().updateAll(context) }
        runCatching { WatchlistWidget().updateAll(context) }
        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "adgent-widget-periodic"
        private const val ON_OPEN_NAME = "adgent-widget-on-open"

        /**
         * Lavoro periodico ogni 15 minuti (minimo consentito da WorkManager),
         * più un refresh immediato ogni volta che l'app viene aperta.
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetUpdateWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
            enqueueNow(context)
        }

        /** Refresh immediato dei widget (apertura app o modifica preferiti). */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_OPEN_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build(),
            )
        }
    }
}
