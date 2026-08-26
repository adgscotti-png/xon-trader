package com.adgent.trader.core.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adgent.trader.appContainer
import com.adgent.trader.core.provider.ProviderId
import com.adgent.trader.ui.widgets.TickerWidget
import com.adgent.trader.ui.widgets.WatchlistWidget
import com.adgent.trader.ui.widgets.WidgetConfigStore
import com.adgent.trader.ui.widgets.configuredTickerPairs
import java.util.concurrent.TimeUnit

/**
 * Aggiorna i widget home screen rifrescando SOLO i simboli dei widget piazzati
 * (simboli fissi configurati nei ticker + preferiti, che i widget mostrano), poi
 * ridisegna dalla cache Room. Niente alert-eval qui: la valutazione vive solo in
 * [AlertCheckWorker]. La cadenza è una CATENA DI ONE-SHOT con REPLACE — il
 * [PeriodicWorkRequest] ha un floor di 15 min, quindi i 5 minuti richiedono la
 * coda one-shot; ogni run programma il successivo con la guardia [isStopped].
 * Se non c'è nessun widget piazzato, il worker non rifresca e non ri-arma la
 * catena: la coda si estingue (zero wakeup inutili).
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val placed = runCatching { hasPlacedWidgets(context) }.getOrDefault(true)
        if (placed) {
            runCatching {
                val container = context.appContainer
                val pairs = configuredTickerPairs(context) +
                    container.watchlistRepo.all().map { it.provider to it.symbol }
                val defaults = if (pairs.isEmpty()) {
                    listOf(
                        "BINANCE" to "BTCUSDT", "BINANCE" to "ETHUSDT",
                        "BINANCE" to "SOLUSDT", "BINANCE" to "XRPUSDT", "BINANCE" to "DOGEUSDT",
                    )
                } else pairs
                defaults.groupBy { it.first }.forEach { (providerName, list) ->
                    val provider = ProviderId.fromName(providerName) ?: return@forEach
                    container.marketDataRepo.refreshTickers(provider, list.map { it.second }, force = false)
                }
            }
            WidgetConfigStore.stampUpdate(context)
            runCatching { TickerWidget().updateAll(context) }
            runCatching { WatchlistWidget().updateAll(context) }
        }
        if (!isStopped && placed) {
            // Guardia: un REPLACE esterno (enqueueNow/schedule da UI) ha già messo
            // in coda il prossimo refresh; non programmare due volte.
            schedule(context, WidgetConfigStore.getRefreshMinutes(context))
        }
        return Result.success()
    }

    private suspend fun hasPlacedWidgets(context: Context): Boolean =
        runCatching {
            val glance = GlanceAppWidgetManager(context)
            glance.getGlanceIds(TickerWidget::class.java).isNotEmpty() ||
                glance.getGlanceIds(WatchlistWidget::class.java).isNotEmpty()
        }.getOrDefault(true)

    companion object {
        private const val CHAIN_NAME = "adgent-widget-chain"
        private const val ON_OPEN_NAME = "adgent-widget-on-open"

        /**
         * Prossimo refresh della catena (REPLACE): un one-shot con delay di
         * [refreshMinutes]. Chiamato da schedule(), da ogni run (guardia isStopped)
         * e quando la configurazione cambia.
         */
        fun schedule(context: Context, refreshMinutes: Int = WidgetConfigStore.getRefreshMinutes(context)) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CHAIN_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                    .setInitialDelay(
                        refreshMinutes.coerceAtLeast(WidgetConfigStore.MIN_REFRESH_MINUTES).toLong(),
                        TimeUnit.MINUTES,
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build(),
            )
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
