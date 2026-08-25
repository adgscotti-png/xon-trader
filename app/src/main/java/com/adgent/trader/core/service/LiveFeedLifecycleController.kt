package com.adgent.trader.core.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.adgent.trader.core.provider.HubMode
import com.adgent.trader.core.provider.PriceFeedHub
import com.adgent.trader.data.DataMode
import com.adgent.trader.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Gestisce il livello di apertura del live feed al passaggio di vita del processo.
 * In background gli stream che la UI lasciava collegati venivano mantenuti finché
 * il processo viveva → batteria: qui al background gli stream scendono al minimo
 * necessario e al foreground tornano [HubMode.FULL]:
 * - SAVER → [HubMode.CLOSED] (niente connessioni; alert via worker WorkManager 15-min);
 * - REALTIME → [HubMode.ALERT_ONLY] (solo i simboli con avvisi attivi, valutati dal hub);
 * - al foreground → [HubMode.FULL] (watchlist completa: sia al ritorno in primo
 *   piano sia al primo avvio, dove il hub parte in ALERT_ONLY finché non c'è UI).
 * La modalità è tenuta in cache (non letta a ogni callback) per mantenere onStop sincrono.
 */
class LiveFeedLifecycleController(
    private val settingsRepo: SettingsRepository,
    private val hub: PriceFeedHub,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    @Volatile private var mode: DataMode = DataMode.SAVER

    fun attach() {
        // Seed sincrono: chiude la finestra tra l'avvio e la prima emissione
        // del DataStore, altrimenti un onStop in REALTIME (col default SAVER)
        // chiuderebbe gli stream che il foreground service sta avviando.
        mode = runBlocking {
            runCatching { settingsRepo.settings.first().dataMode }
                .getOrDefault(DataMode.SAVER)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            settingsRepo.settings.collect { mode = it.dataMode }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        when (mode) {
            DataMode.SAVER -> hub.setMode(HubMode.CLOSED)
            DataMode.REALTIME -> hub.setMode(HubMode.ALERT_ONLY)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        hub.setMode(HubMode.FULL)
    }
}
