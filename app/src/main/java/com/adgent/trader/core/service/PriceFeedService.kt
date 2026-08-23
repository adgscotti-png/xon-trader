package com.adgent.trader.core.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.adgent.trader.appContainer
import com.adgent.trader.core.notifications.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Servizio foreground "Realtime": mantiene il WebSocket Binance attivo anche
 * con l'app in background e valuta le regole avviso sui tick ricevuti.
 * Notifica persistente discreta (canale "Feed realtime", importanza MIN).
 */
class PriceFeedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(SERVICE_NOTIF_ID, Notifications.serviceNotification(this, "Price alerts active"))
        observeAndEvaluate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val ws = runCatching { appContainer.ws }.getOrNull()
        ws?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun observeAndEvaluate() {
        val container = appContainer
        container.tickerRepo.ensureLive()

        // Ogni volta che cambiano le regole o arrivano tick, rivaluta le regole attive.
        scope.launch {
            combine(
                container.alertRepo.observeAll(),
                container.tickerRepo.liveVersion,
            ) { rules, _ ->
                val enabled = rules.filter { it.enabled }
                if (enabled.isEmpty()) return@combine
                val now = System.currentTimeMillis()
                enabled.forEach { rule ->
                    val tick = container.tickerRepo.liveTick(rule.symbol) ?: return@forEach
                    when (AlertEngine.evaluate(rule, tick, now)) {
                        AlertEngine.Verdict.SKIP -> Unit
                        AlertEngine.Verdict.FIRE_ONCE -> {
                            Notifications.notifyAlert(applicationContext, rule, tick)
                            container.alertRepo.markTriggered(rule.id, now, enabled = false)
                        }
                        AlertEngine.Verdict.FIRE_REPEATABLE -> {
                            Notifications.notifyAlert(applicationContext, rule, tick)
                            container.alertRepo.markTriggered(rule.id, now, enabled = true)
                        }
                    }
                }
            }.collect { /* valutazione già eseguita nel combine */ }
        }
    }

    companion object {
        const val ACTION_STOP = "com.adgent.trader.action.STOP_FEED"
        const val SERVICE_NOTIF_ID = 42
    }
}
