package com.adgent.trader.core.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.adgent.trader.core.network.BinanceWebSocket
import com.adgent.trader.data.DataMode
import com.adgent.trader.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Gap batteria in modalità RISPARMIO: il WebSocket aperto dalla UI (lista
 * mercati / grafico) restava collegato anche con l'app in background, finché
 * il processo viveva. Qui, in SAVER, al passaggio in background il WS viene
 * chiuso; al ritorno in foreground viene riaperto (solo se eravamo stati noi
 * a chiuderlo), così la UI live riparte senza che i ViewModel debbano
 * ricollegarsi. In REALTIME il WS lo tiene aperto il foreground service →
 * nessun intervento. La modalità è tenuta in cache (non letto a ogni callback)
 * per mantenere onStop sincrono: chiudere il WS mentre l'app è già rientrata
 * in foreground sarebbe peggio di non chiuderlo affatto.
 */
class WsLifecycleController(
    private val settingsRepo: SettingsRepository,
    private val ws: BinanceWebSocket,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    @Volatile private var mode: DataMode = DataMode.SAVER
    @Volatile private var closedForBackground = false

    fun attach() {
        // Seed sincrono: chiude la finestra tra l'avvio e la prima emissione
        // del DataStore, altrimenti un onStop in REALTIME (col default SAVER)
        // chiuderebbe il WS che il foreground service sta avviando.
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
            ws.disconnect()
            closedForBackground = true
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (closedForBackground) {
            ws.connect()
            closedForBackground = false
        }
    }
}
