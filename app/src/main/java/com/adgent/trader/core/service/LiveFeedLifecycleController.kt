package com.adgent.trader.core.service

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.adgent.trader.core.provider.HubMode
import com.adgent.trader.core.provider.PriceFeedHub
import com.adgent.trader.core.work.AlertScheduler
import com.adgent.trader.data.DataMode
import com.adgent.trader.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Il live feed segue SOLO il ciclo di vita del processo: in primo piano tutti
 * gli stream del focus restano aperti ([HubMode.FULL]), in background si
 * chiudono ([HubMode.CLOSED]) e gli avvisi passano al worker WorkManager.
 * Niente servizio in foreground, niente notifica persistente: la cadenza
 * [AlertScheduler] è ri-armata subito all'uscita per non perdere minuti di
 * latenza sul primo ciclo.
 */
class LiveFeedLifecycleController(
    private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val hub: PriceFeedHub,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    @Volatile private var mode: DataMode = DataMode.SAVER

    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            settingsRepo.settings.collect { mode = it.dataMode }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        hub.setMode(HubMode.FULL)
    }

    override fun onStop(owner: LifecycleOwner) {
        hub.setMode(HubMode.CLOSED)
        // Riprende subito la catena di verifica alert in background (2-15 min).
        scope.launch {
            AlertScheduler.scheduleIfRules(appContext, AlertScheduler.initialDelayMs(mode))
        }
    }
}
