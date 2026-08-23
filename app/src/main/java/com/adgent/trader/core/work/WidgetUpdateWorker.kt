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
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.notifications.Notifications
import com.adgent.trader.core.service.AlertEngine
import com.adgent.trader.ui.widgets.TickerWidget
import com.adgent.trader.ui.widgets.WatchlistWidget
import com.adgent.trader.ui.widgets.WidgetConfigStore
import com.adgent.trader.ui.widgets.configuredTickerSymbols
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Aggiorna i widget home screen: refresh dei prezzi (preferiti + simboli
 * configurati nei widget), ridisegno dalla cache Room e — in modalità
 * Risparmio o comunque a cadenza worker — valutazione degli avvisi sui dati
 * REST freschi. Il ridisegno avviene anche a rete assente: il widget mostra
 * l'ultimo dato noto invece di vuotarsi, con l'ora dell'aggiornamento.
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        runCatching {
            val container = context.appContainer
            val symbols = (container.watchlistRepo.all().map { it.symbol } +
                configuredTickerSymbols(context)).distinct()
                .ifEmpty { listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT") }
            container.tickerRepo.refreshTickers(symbols, force = false)
        }
        evaluateAlerts(context)
        WidgetConfigStore.stampUpdate(context)
        runCatching { TickerWidget().updateAll(context) }
        runCatching { WatchlistWidget().updateAll(context) }
        return Result.success()
    }

    /**
     * Valuta le regole avviso attive sull'ultima cache prezzi: è la rete di
     * sicurezza che fa scattare gli avvisi anche senza servizio foreground
     * (modalità Risparmio, processo ucciso dall'OEM).
     */
    private suspend fun evaluateAlerts(context: Context) {
        runCatching {
            val container = context.appContainer
            val rules = container.alertRepo.enabledRules()
            if (rules.isEmpty()) return
            val cached = container.tickerRepo.observeCached(limit = 500).first()
                .associateBy { it.symbol }
            val now = System.currentTimeMillis()
            rules.forEach { rule ->
                val row = cached[rule.symbol] ?: return@forEach
                // Ricostruisce l'apertura 24h dallo scarto percentuale noto.
                val open24h = if (row.changePercent24h != 0.0)
                    row.price / (1.0 + row.changePercent24h / 100.0) else row.price
                val tick = PriceTick(
                    symbol = row.symbol,
                    price = row.price,
                    open24h = open24h,
                    high24h = row.high24h,
                    low24h = row.low24h,
                    quoteVolume24h = row.quoteVolume24h,
                )
                when (AlertEngine.evaluate(rule, tick, now)) {
                    AlertEngine.Verdict.SKIP -> Unit
                    AlertEngine.Verdict.FIRE_ONCE -> {
                        Notifications.notifyAlert(context.applicationContext, rule, tick)
                        container.alertRepo.markTriggered(rule.id, now, enabled = false)
                    }
                    AlertEngine.Verdict.FIRE_REPEATABLE -> {
                        Notifications.notifyAlert(context.applicationContext, rule, tick)
                        container.alertRepo.markTriggered(rule.id, now, enabled = true)
                    }
                }
            }
        }
    }

    companion object {
        private const val PERIODIC_NAME = "adgent-widget-periodic"
        private const val ON_OPEN_NAME = "adgent-widget-on-open"

        /**
         * Lavoro periodico all'intervallo scelto in configurazione (minimo 15
         * minuti, limite Android per WorkManager con app chiusa), più un
         * refresh immediato ogni volta che l'app viene aperta. [UPDATE]:
         * cambia l'intervallo senza perdere la finestra corrente.
         */
        fun schedule(context: Context, refreshMinutes: Int = WidgetConfigStore.getRefreshMinutes(context)) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
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
