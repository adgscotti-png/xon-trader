package com.adgent.trader.core.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.adgent.trader.appContainer
import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.database.TickerCacheEntity
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.notifications.Notifications
import com.adgent.trader.core.provider.HubMode
import com.adgent.trader.core.provider.ProviderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Servizio foreground "Realtime". In primo piano (FULL) colleziona i valicamenti
 * emessi dal hub via WebSocket (latenza sub-second). In background
 * ([HubMode.ALERT_ONLY]) NON usa WebSocket: fa un polling REST leggero e adattivo
 * sui soli simboli con regole avviso attive (una richiesta batch per provider,
 * connessione HTTP keep-alive riusata), valutando le regole su un
 * [AlertBoundaryIndex] condiviso col hub. L'intervallo di polling dipende dalla
 * regola più vicina alla soglia (5s a <1%, 10s a <3%, 20s a <10%, 30s lontano).
 * Tra una richiesta e l'altra il processo dorme (Deep Sleep).
 */
class PriceFeedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(SERVICE_NOTIF_ID, Notifications.serviceNotification(this, "Price alerts active"))
        observeAlerts()
        startAlertPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Fix START_STICKY: se l'utente è passato a RISPARMIO mentre il sistema
        // stava riavviando il servizio, NON resuscitarlo (gli alert tornano al worker).
        if (!PriceFeedController.isRealtime(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Il hub NON va toccato qui: spegnendo il feed in foreground (es.
        // REALTIME→SAVER con app in primo piano) chiuderebbe anche gli stream
        // UI della watchlist. Lo stato lo gestisce LiveFeedLifecycleController.
        scope.cancel()
        super.onDestroy()
    }

    /** Valicamenti dal hub in modalità FULL (WebSocket, latenza sub-second). */
    private fun observeAlerts() {
        val container = appContainer
        scope.launch {
            container.priceFeedHub.alertTriggers.collect { trig ->
                Notifications.notifyAlert(applicationContext, trig.rule, trig.tick)
            }
        }
    }

    /**
     * Polling adattivo: il primo giro è immediato, poi l'intervallo è calcolato
     * a ogni ciclo. Fuori da ALERT_ONLY il loop si limita a ricontrollare la
     * modalità (nessuna richiesta di rete); al ritorno in background il primo
     * poll parte al più tardi dopo [RETRY_MS].
     */
    private fun startAlertPolling() {
        val container = appContainer
        scope.launch {
            var interval = 0L
            while (true) {
                delay(interval)
                interval = if (container.priceFeedHub.mode.value == HubMode.ALERT_ONLY) {
                    // Un errore in un ciclo non deve uccidere il poller: si ritenta
                    // presto invece di lasciare gli alert senza valutazione.
                    runCatching { alertPollCycle() }.getOrDefault(RETRY_MS)
                } else {
                    RETRY_MS
                }
            }
        }
    }

    /** Un ciclo di polling: refresh + valutazione; ritorna il prossimo intervallo. */
    private suspend fun alertPollCycle(): Long {
        val container = appContainer
        val rules = container.alertRepo.enabledRules()
        if (rules.isEmpty()) return SLEEP_MS

        rules.map { it.provider }.distinct().forEach { name ->
            val provider = ProviderId.fromName(name) ?: return@forEach
            val symbols = rules.filter { it.provider == name }.map { it.symbol }.distinct()
            // Una sola richiesta batch per provider; il RateLimiter del provider
            // protegge e OkHttp riusa la connessione HTTP keep-alive.
            runCatching { container.marketDataRepo.refreshTickers(provider, symbols, force = true) }
        }

        val now = System.currentTimeMillis()
        val cached = container.tickerCacheDao.all().associateBy { "${it.provider}:${it.symbol}" }
        var minGap = Double.MAX_VALUE
        var found = false
        for (rule in rules) {
            val row = cached["${rule.provider}:${rule.symbol}"] ?: continue
            found = true
            val tick = row.toPriceTick()
            minGap = minOf(minGap, gapFromThreshold(rule, tick))
            val provider = ProviderId.fromName(rule.provider) ?: continue
            container.alertBoundaryIndex.evaluate(provider, tick, now).forEach { trig ->
                runCatching {
                    container.alertRepo.markTriggered(trig.rule.id, trig.nowMs, enabled = trig.rule.repeatable)
                }
                Notifications.notifyAlert(applicationContext, trig.rule, trig.tick)
            }
        }
        return if (!found) NEAR_MS else intervalFromGap(minGap)
    }

    /** Distanza (frazione ≥0) del prezzo corrente dalla soglia della regola. */
    private fun gapFromThreshold(rule: AlertRuleEntity, tick: PriceTick): Double {
        val base = rule.threshold
        if (base == 0.0) return Double.MAX_VALUE
        val gap = when (rule.type) {
            "PRICE_ABOVE" -> (tick.price - base) / base
            "PRICE_BELOW" -> (base - tick.price) / base
            "PERCENT_UP" -> (base - tick.changePercent24h) / base
            "PERCENT_DOWN" -> (tick.changePercent24h + base) / base
            else -> return Double.MAX_VALUE
        }
        return abs(gap)
    }

    /** Intervallo di polling: più il prezzo è vicino alla soglia, più frequente. */
    private fun intervalFromGap(gap: Double): Long = when {
        gap <= 0.01 -> NEAR_MS // soglia a <1%: aggiorna ogni 5s
        gap <= 0.03 -> MED_MS  // <3%: ogni 10s
        gap <= 0.10 -> FAR_MS  // <10%: ogni 20s
        else -> SLEEP_MS       // lontano: ogni 30s
    }

    private fun TickerCacheEntity.toPriceTick(): PriceTick {
        val open24h = if (changePercent24h != 0.0) price / (1.0 + changePercent24h / 100.0) else price
        return PriceTick(
            symbol = symbol,
            price = price,
            open24h = open24h,
            high24h = high24h,
            low24h = low24h,
            quoteVolume24h = quoteVolume24h,
            provider = ProviderId.fromName(provider) ?: ProviderId.BINANCE,
        )
    }

    companion object {
        const val ACTION_STOP = "com.adgent.trader.action.STOP_FEED"
        const val SERVICE_NOTIF_ID = 42

        private const val NEAR_MS = 5_000L // prezzo a <1% dalla soglia
        private const val MED_MS = 10_000L // <3%
        private const val FAR_MS = 20_000L // <10%
        private const val SLEEP_MS = 30_000L // lontano / nessuna regola
        private const val RETRY_MS = 2_000L // modalità non-ALERT_ONLY: ricontrolla presto
    }
}
