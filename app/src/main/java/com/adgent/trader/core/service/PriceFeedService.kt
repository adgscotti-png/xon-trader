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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Servizio foreground "Realtime": mantiene attivi gli stream live in background
 * (ridotti ai soli simboli con avvisi via [com.adgent.trader.core.provider.PriceFeedHub])
 * e notifica SOLO i valicamenti emessi dal hub ([PriceFeedHub.alertTriggers]).
 * Consumatore passivo di eventi (network-driven): tra un tick e l'altro il
 * processo dorme (Deep Sleep). Notifica persistente discreta (canale "Feed
 * realtime", importanza MIN).
 */
class PriceFeedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(SERVICE_NOTIF_ID, Notifications.serviceNotification(this, "Price alerts active"))
        observeAlerts()
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

    private fun observeAlerts() {
        val container = appContainer
        scope.launch {
            container.priceFeedHub.alertTriggers.collect { trig ->
                Notifications.notifyAlert(applicationContext, trig.rule, trig.tick)
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.adgent.trader.action.STOP_FEED"
        const val SERVICE_NOTIF_ID = 42
    }
}
