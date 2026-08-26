package com.adgent.trader.core.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adgent.trader.appContainer
import com.adgent.trader.core.database.TickerCacheEntity
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.notifications.Notifications
import com.adgent.trader.core.provider.HubMode
import com.adgent.trader.core.provider.ProviderId
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Verifica gli avvisi in background (catena adattiva). Fa UNA richiesta REST
 * batch per exchange SOLO sui simboli con regole attive, valuta le soglie col
 * cooldown condiviso [com.adgent.trader.core.service.AlertBoundaryIndex]
 * (stesso anti-doppio-fine del path WS del hub), notifica i valicamenti e
 * programma la prossima verifica in base alla distanza dalla soglia più vicina.
 * In primo piano ([HubMode.FULL]) il hub valuta già via WS: il worker salta il
 * lavoro (nessun REST duplicato) e ri-arma la cadenza.
 */
class AlertCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val container = context.appContainer
        val mode = runCatching { container.settingsRepo.settings.first().dataMode }
            .getOrDefault(com.adgent.trader.data.DataMode.SAVER)
        val rules = runCatching { container.alertRepo.enabledRules() }.getOrNull().orEmpty()

        if (rules.isEmpty()) {
            // Zero regole attive → la catena si spegne: nessun altro wakeup.
            AlertScheduler.cancel(context)
            return Result.success()
        }

        if (container.priceFeedHub.mode.value == HubMode.FULL) {
            // App in primo piano: gli avvisi li valida il hub via WS. Niente
            // REST duplicato, ri-arma la cadenza (si fermerà all'onStop).
            if (!isStopped) {
                AlertScheduler.schedule(context, AlertScheduler.nextDelayMs(Double.MAX_VALUE, mode))
            }
            return Result.success()
        }

        val index = container.alertBoundaryIndex
        index.rebuild(rules)
        val now = System.currentTimeMillis()
        var minGap = Double.MAX_VALUE

        rules.filter { ProviderId.fromName(it.provider) != null }
            .groupBy { ProviderId.fromName(it.provider)!! }
            .forEach { (provider, provRules) ->
                val symbols = provRules.map { it.symbol }.distinct()
                container.marketDataRepo.refreshTickers(provider, symbols, force = true)
                for (rule in provRules) {
                    val row = container.marketDataRepo.observeCachedSymbol(provider, rule.symbol).first() ?: continue
                    val tick = row.toPriceTick(provider)
                    minGap = minOf(minGap, gapFromThreshold(rule, tick))
                    index.evaluate(provider, tick, now).forEach { trig ->
                        runCatching {
                            container.alertRepo.markTriggered(trig.rule.id, trig.nowMs, enabled = trig.rule.repeatable)
                        }
                        Notifications.notifyAlert(context, trig.rule, trig.tick)
                    }
                }
            }

        if (!isStopped) {
            // Guardia: un REPLACE esterno (es. scheduleIfRules da UI) ha già
            // messo in coda la prossima verifica; non programmare due volte.
            AlertScheduler.schedule(context, AlertScheduler.nextDelayMs(minGap, mode))
        }
        return Result.success()
    }

    /** Distanza percentuale (frazione 0..1) dal prezzo corrente alla soglia della regola. */
    private fun gapFromThreshold(rule: com.adgent.trader.core.database.AlertRuleEntity, tick: PriceTick): Double =
        when (rule.type) {
            "PRICE_ABOVE", "PRICE_BELOW" ->
                if (tick.price > 0.0) abs(tick.price - rule.threshold) / rule.threshold else Double.MAX_VALUE
            "PERCENT_UP", "PERCENT_DOWN" ->
                if (rule.threshold != 0.0) abs(tick.changePercent24h - rule.threshold) / abs(rule.threshold)
                else Double.MAX_VALUE
            else -> Double.MAX_VALUE
        }

    private fun TickerCacheEntity.toPriceTick(provider: ProviderId): PriceTick {
        val open24h = if (changePercent24h != 0.0) price / (1.0 + changePercent24h / 100.0) else price
        return PriceTick(
            symbol = symbol,
            price = price,
            open24h = open24h,
            high24h = high24h,
            low24h = low24h,
            quoteVolume24h = quoteVolume24h,
            provider = provider,
        )
    }
}
