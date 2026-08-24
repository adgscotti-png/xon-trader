package com.adgent.trader.core.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.adgent.trader.core.provider.PriceFeedHub
import com.adgent.trader.data.DataMode
import com.adgent.trader.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Gap batteria in modalità RISPARMIO: gli stream live della watchlist
 * ([PriceFeedHub]) aperti dalla UI restavano collegati anche con l'app in
 * background finché il processo viveva. Qui, in SAVER, al passaggio in
 * background tutti i WebSocket vengono chiusi; al ritorno in foreground
 * vengono riaperti (solo se eravamo stati noi a chiuderli), così la UI live
 * riparte senza che i ViewModel debbano ricollegarsi. In REALTIME gli stream
 * li tiene aperti il foreground service → nessun intervento. La modalità è
 * tenuta in cache (non letta a ogni callback) per mantenere onStop sincrono.
 */
class LiveFeedLifecycleController(
    private val settingsRepo: SettingsRepository,
    private val hub: PriceFeedHub,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    @Volatile private var mode: DataMode = DataMode.SAVER
    @Volatile private var closedForBackground = false

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
        if (mode == DataMode.SAVER) {
            hub.setForeground(false)
            closedForBackground = true
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (closedForBackground) {
            hub.setForeground(true)
            closedForBackground = false
        }
    }
}
