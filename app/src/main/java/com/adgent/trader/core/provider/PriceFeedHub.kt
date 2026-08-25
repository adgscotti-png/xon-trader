package com.adgent.trader.core.provider

import com.adgent.trader.core.database.TickerCacheDao
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.service.AlertBoundaryIndex
import com.adgent.trader.core.service.AlertTrigger
import com.adgent.trader.data.AlertRepository
import com.adgent.trader.data.DataMode
import com.adgent.trader.data.SettingsRepository
import com.adgent.trader.data.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Stato di apertura del live feed: quali stream tenere aperti e cosa farne dei tick. */
enum class HubMode {
    /** App in primo piano: watchlist completa + Room/liveVersion per la UI. */
    FULL,

    /** REALTIME in background: SOLO i simboli con regole avviso attive, niente
     *  scritture Room/liveVersion, solo valutazione alert (network-driven). */
    ALERT_ONLY,

    /** SAVER in background: tutto chiuso (alert via worker WorkManager 15-min). */
    CLOSED,
}

/**
 * Live hub della WATCHLIST (max 20 coppie): sostituisce `TickerRepository.ensureLive`.
 * Combina watchlist + impostazioni (default/override) + salute provider per risolvere
 * il provider effettivo di ogni coppia, poi apre UN WebSocket per exchange con ≥1
 * coppia e fa delta subscribe/unsubscribe. I tick aggiornano la mappa live e, in
 * [HubMode.FULL], la cache Room (sparkline preservata) + liveVersion.
 *
 * In background la modalità scende a [HubMode.ALERT_ONLY] (REALTIME) o [HubMode.CLOSED]
 * (SAVER): niente connessioni inutili, batteria rispettata. In ALERT_ONLY i tick dei
 * simboli con avvisi alimentano [AlertBoundaryIndex] e l'evento emesso su
 * [alertTriggers] viene solo notificato (nessuna scrittura Room per tick).
 *
 * Tutte le riconciliazioni passano dal mutex (fix race: prima `setForeground` si
 * sincronizzava ma il collector di `start()` chiamava `syncWith` senza mutex).
 */
class PriceFeedHub(
    private val registry: ProviderRegistry,
    private val watchlistRepo: WatchlistRepository,
    private val settingsRepo: SettingsRepository,
    private val alertRepo: AlertRepository,
    private val router: AutoProviderRouter,
    private val mapper: SymbolMapper,
    private val tickerCacheDao: TickerCacheDao,
    private val scope: CoroutineScope,
) {
    private val live = ConcurrentHashMap<String, PriceTick>() // "$provider:$symbol"
    private val desiredKeys = ConcurrentHashMap.newKeySet<String>()
    private val subscribed = ConcurrentHashMap<ProviderId, Set<String>>()
    private val syncMutex = Mutex()
    private val alertIndex = AlertBoundaryIndex()

    private val _liveVersion = MutableStateFlow(0L)
    val liveVersion = _liveVersion.asStateFlow()

    /** Emette SOLO a valicamento di una regola (il servizio notifica; DROP_OLDEST:
     *  se il notificatore è lento, gli alert più vecchi cadono senza bloccarsi). */
    private val _alertTriggers = MutableSharedFlow<AlertTrigger>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alertTriggers = _alertTriggers.asSharedFlow()

    // Default ALERT_ONLY: un processo avviato senza UI (es. boot, REALTIME) non
    // deve aprire la watchlist piena; onStart promuove a FULL appena c'è un'attività.
    @Volatile private var mode: HubMode = HubMode.ALERT_ONLY
    @Volatile private var isRealtime = true
    @Volatile private var lastDesired: Map<ProviderId, Set<String>> = emptyMap()
    @Volatile private var alertDesired: Map<ProviderId, Set<String>> = emptyMap()
    @Volatile private var syncRequested = false
    private var job: Job? = null

    fun liveTick(provider: ProviderId, symbol: String): PriceTick? =
        live["${provider.name}:$symbol"]

    /** Cambia lo stato di apertura (chiamato dal ciclo di vita del processo). */
    fun setMode(newMode: HubMode) {
        if (mode == newMode) return
        mode = newMode
        reconcile()
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(
                watchlistRepo.observe(),
                settingsRepo.settings,
                registry.health,
                alertRepo.observeAll(),
            ) { watchlist, settings, health, rules ->
                val perCoin = settings.perCoinProviders
                val default = settings.defaultProvider.providerId
                val desired = HashMap<ProviderId, MutableSet<String>>()
                for (item in watchlist) {
                    val pair = mapper.fromCompact(item.symbol) ?: continue
                    val provider = router.resolve(pair, perCoin, default, health)
                    desired.getOrPut(provider) { mutableSetOf() }.add(item.symbol)
                }
                val enabledRules = rules.filter { it.enabled }
                val aDesired = HashMap<ProviderId, MutableSet<String>>()
                for (rule in enabledRules) {
                    val p = ProviderId.fromName(rule.provider) ?: continue
                    aDesired.getOrPut(p) { mutableSetOf() }.add(rule.symbol)
                }
                // Stato volatile letto a esecuzione da sync(): il combine non riconcilia più.
                isRealtime = settings.dataMode == DataMode.REALTIME
                lastDesired = desired
                alertDesired = aDesired
                alertIndex.rebuild(enabledRules)
            }.collect { reconcile() }
        }
        registry.all().forEach { p ->
            scope.launch {
                p.tickFlow().collect { batch -> onTicks(p.id, batch) }
            }
        }
    }

    /** Richiede una riconciliazione coalescente: la sync successiva legge lo stato più recente. */
    private fun reconcile() {
        if (syncRequested) return
        syncRequested = true
        scope.launch {
            syncMutex.withLock {
                syncRequested = false
                sync()
            }
        }
    }

    private fun effectiveDesired(): Map<ProviderId, Set<String>> = when (mode) {
        HubMode.CLOSED -> emptyMap()
        HubMode.FULL -> lastDesired
        HubMode.ALERT_ONLY -> if (isRealtime) alertDesired else emptyMap()
    }

    /** Apre/chiude gli stream per convergere sullo stato corrente (mode + desiderata). */
    private suspend fun sync() {
        val desired = effectiveDesired()
        desiredKeys.clear()
        desired.forEach { (p, syms) -> syms.forEach { desiredKeys.add("${p.name}:$it") } }

        for (p in registry.enabledIds()) {
            val syms = desired[p].orEmpty()
            val adapter = registry.get(p)
            if (syms.isEmpty()) {
                adapter.disconnectStream()
                subscribed.remove(p)
                continue
            }
            if (!subscribed.containsKey(p)) {
                adapter.connectStream()
                subscribed[p] = emptySet()
            }
            val cur = subscribed[p] ?: emptySet()
            val toAdd = syms - cur
            val toDrop = cur - syms
            if (toAdd.isNotEmpty()) adapter.subscribeStream(toAdd)
            if (toDrop.isNotEmpty()) adapter.unsubscribeStream(toDrop)
            subscribed[p] = syms
        }
        for (p in subscribed.keys - desired.keys) {
            registry.get(p).disconnectStream()
            subscribed.remove(p)
        }
    }

    private suspend fun onTicks(provider: ProviderId, batch: List<PriceTick>) {
        val full = mode == HubMode.FULL
        val alerts = isRealtime
        var any = false
        val now = System.currentTimeMillis()
        for (t in batch) {
            val key = "${provider.name}:${t.symbol}"
            if (key !in desiredKeys) continue
            live[key] = t
            if (full) {
                tickerCacheDao.updateTick(
                    provider = provider.name,
                    symbol = t.symbol,
                    price = t.price,
                    change = t.changePercent24h,
                    high = t.high24h,
                    low = t.low24h,
                    volume = t.quoteVolume24h,
                    updatedAt = System.currentTimeMillis(),
                )
                any = true
            }
            if (alerts) {
                // Fuori dal lock dell'index: persist e notifica restano chiamate suspend.
                alertIndex.evaluate(provider, t, now).forEach { trig ->
                    runCatching {
                        alertRepo.markTriggered(trig.rule.id, trig.nowMs, enabled = trig.rule.repeatable)
                    }
                    _alertTriggers.tryEmit(trig)
                }
            }
        }
        if (full && any) _liveVersion.value += 1
    }
}
