package com.adgent.trader.core.provider

import com.adgent.trader.core.database.TickerCacheDao
import com.adgent.trader.core.database.TickerCacheEntity
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.service.AlertBoundaryIndex
import com.adgent.trader.core.service.AlertTrigger
import com.adgent.trader.data.AlertRepository
import com.adgent.trader.data.SettingsRepository
import com.adgent.trader.data.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Stato di apertura del live feed: quali stream tenere aperti e cosa farne dei tick. */
enum class HubMode {
    /** App in primo piano: ciò che guardi (focus) + simboli con regole attive. */
    FULL,

    /** App in background: tutto chiuso; gli avvisi passano al worker WorkManager. */
    CLOSED,
}

/**
 * Live hub GUIDATO DAL FOCUS: combina watchlist + impostazioni + salute provider +
 * regole attive + [LiveFocus] (cosa guardi) e apre UN WebSocket per exchange con
 * ≥1 coppia desiderata, facendo delta subscribe/unsubscribe. I tick aggiornano una
 * hot map in memoria (latest-wins, economica) e la cache Room viene scritta in
 * BATCH ogni [FLUSH_INTERVAL_MS] in un'unica transazione (preservando sparkline).
 *
 * In background ([HubMode.CLOSED]) non resta nulla di aperto: gli avvisi li valida
 * [com.adgent.trader.core.work.AlertCheckWorker] (WorkManager, cadenza adattiva).
 * In primo piano ([HubMode.FULL]) gli avvisi scattano SUBITO via WebSocket sui
 * simboli sottoscritti (ciò che guardi + i simboli con regole, così un avviso su
 * una coin fuori schermo resta istantaneo finché l'app è aperta).
 */
class PriceFeedHub(
    private val registry: ProviderRegistry,
    private val watchlistRepo: WatchlistRepository,
    private val settingsRepo: SettingsRepository,
    private val alertRepo: AlertRepository,
    private val router: AutoProviderRouter,
    private val mapper: SymbolMapper,
    private val tickerCacheDao: TickerCacheDao,
    private val alertIndex: AlertBoundaryIndex,
    private val liveFocus: LiveFocus,
    private val scope: CoroutineScope,
) {
    private val desiredKeys = ConcurrentHashMap.newKeySet<String>()
    private val subscribed = ConcurrentHashMap<ProviderId, Set<String>>()
    private val syncMutex = Mutex()

    private val _liveVersion = MutableStateFlow(0L)
    val liveVersion = _liveVersion.asStateFlow()

    /** Hot map dei tick live (key "$provider:$symbol"), latest-wins, in memoria.
     *  La UI può osservarla direttamente per la ricomposizione per-riga. */
    private val _liveTicks = MutableStateFlow<Map<String, PriceTick>>(emptyMap())
    val liveTicks: StateFlow<Map<String, PriceTick>> = _liveTicks.asStateFlow()

    /** Emette SOLO a valicamento di una regola (DROP_OLDEST: se il notificatore è
     *  lento, gli alert più vecchi cadono senza bloccare il ciclo). */
    private val _alertTriggers = MutableSharedFlow<AlertTrigger>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alertTriggers = _alertTriggers.asSharedFlow()

    // Default CLOSED: un processo avviato senza UI (es. boot, worker) non apre
    // nulla; onStart del processo promuove a FULL appena c'è un'attività.
    private val _mode = MutableStateFlow(HubMode.CLOSED)
    val mode: StateFlow<HubMode> = _mode.asStateFlow()

    @Volatile private var lastDesired: Map<ProviderId, Set<String>> = emptyMap()
    @Volatile private var syncRequested = false
    private var job: Job? = null

    fun liveTick(provider: ProviderId, symbol: String): PriceTick? =
        _liveTicks.value["${provider.name}:$symbol"]

    /** Tick live osservabili della coppia (per il coin detail fuori watchlist). */
    fun liveTickFlow(provider: ProviderId, symbol: String): Flow<PriceTick?> =
        _liveTicks.map { it["${provider.name}:$symbol"] }

    /** Cambia lo stato di apertura (chiamato dal ciclo di vita del processo). */
    fun setMode(newMode: HubMode) {
        if (_mode.value == newMode) return
        _mode.value = newMode
        if (newMode == HubMode.CLOSED) {
            // Svuota il batch pendente prima di chiudere gli stream.
            scope.launch { flushRoom() }
        }
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
                liveFocus.spec,
            ) { watchlist, settings, health, rules, focus ->
                val perCoin = settings.perCoinProviders
                val default = settings.defaultProvider.providerId
                val desired = HashMap<ProviderId, MutableSet<String>>()
                when (focus) {
                    LiveFocusSpec.Idle -> Unit
                    LiveFocusSpec.Favorites -> {
                        for (item in watchlist) {
                            val pair = mapper.fromCompact(item.symbol) ?: continue
                            val provider = router.resolve(pair, perCoin, default, health)
                            desired.getOrPut(provider) { mutableSetOf() }.add(item.symbol)
                        }
                    }
                    is LiveFocusSpec.Markets -> {
                        focus.viewport.forEach { (p, syms) ->
                            desired.getOrPut(p) { mutableSetOf() }.addAll(syms)
                        }
                    }
                    is LiveFocusSpec.Coin -> {
                        if (focus.symbol.isNotBlank()) {
                            desired.getOrPut(focus.provider) { mutableSetOf() }.add(focus.symbol)
                        }
                    }
                }
                // Alert-first: finché l'app è aperta gli avvisi devono scattare
                // subito anche su simboli non visibili. Si aggiungono i soli
                // simboli con regole attive (pochi) al set del focus.
                for (rule in rules) {
                    if (!rule.enabled) continue
                    ProviderId.fromName(rule.provider)?.let { p ->
                        desired.getOrPut(p) { mutableSetOf() }.add(rule.symbol)
                    }
                }
                lastDesired = desired
                alertIndex.rebuild(rules.filter { it.enabled })
            }.collect { reconcile() }
        }
        registry.all().forEach { p ->
            scope.launch {
                p.tickFlow().collect { batch -> onTicks(p.id, batch) }
            }
        }
        // Flush Room in batch: scrive la mappa live ogni ~5s in una transazione.
        scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flushRoom()
            }
        }
    }

    /** Riconciliazione coalescente: la sync successiva legge lo stato più recente. */
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

    private fun effectiveDesired(): Map<ProviderId, Set<String>> = when (_mode.value) {
        HubMode.CLOSED -> emptyMap()
        HubMode.FULL -> lastDesired
    }

    /** Apre/chiude gli stream per convergere sullo stato corrente (mode + focus). */
    private suspend fun sync() {
        val desired = effectiveDesired()
        desiredKeys.clear()
        desired.forEach { (p, syms) -> syms.forEach { desiredKeys.add("${p.name}:$it") } }

        // Pulisce i tick live dei simboli non più desiderati (focus cambiato).
        val liveNow = _liveTicks.value
        if (liveNow.keys.any { it !in desiredKeys }) {
            _liveTicks.value = liveNow.filterKeys { it in desiredKeys }
        }

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
        val full = _mode.value == HubMode.FULL
        val now = System.currentTimeMillis()
        var changed = false
        val update = HashMap<String, PriceTick>()
        for (t in batch) {
            val key = "${provider.name}:${t.symbol}"
            if (key !in desiredKeys) continue
            update[key] = t
            changed = true
            if (full) {
                // Valutazione avvisi istantanea via WS (cooldown condiviso col worker).
                alertIndex.evaluate(provider, t, now).forEach { trig ->
                    runCatching {
                        alertRepo.markTriggered(trig.rule.id, trig.nowMs, enabled = trig.rule.repeatable)
                    }
                    _alertTriggers.tryEmit(trig)
                }
            }
        }
        if (changed) {
            val merged = _liveTicks.value.toMutableMap()
            merged.putAll(update)
            _liveTicks.value = merged
        }
    }

    /** Scrive la mappa live in Room in un'unica transazione, preservando sparkline.
     *  Bump di [liveVersion] solo se qualcosa è davvero cambiato (UI per-riga). */
    private suspend fun flushRoom() {
        val snapshot = _liveTicks.value
        if (snapshot.isEmpty()) return
        val now = System.currentTimeMillis()
        val byProvider = snapshot.values.groupBy { it.provider }
        var wrote = false
        for ((provider, ticks) in byProvider) {
            val existing = runCatching { tickerCacheDao.allFor(provider.name) }.getOrNull() ?: continue
            val existingBySym = existing.associateBy { it.symbol }
            val entities = ticks.map { t ->
                val e = existingBySym[t.symbol]
                TickerCacheEntity(
                    provider = provider.name,
                    symbol = t.symbol,
                    price = t.price,
                    changePercent24h = t.changePercent24h,
                    high24h = t.high24h,
                    low24h = t.low24h,
                    quoteVolume24h = t.quoteVolume24h,
                    sparkline = e?.sparkline.orEmpty(),
                    updatedAt = now,
                )
            }
            runCatching { tickerCacheDao.upsertAll(entities) }.onSuccess { wrote = true }
        }
        if (wrote) _liveVersion.value += 1
    }

    companion object {
        /** Cadenza di flush della cache Room (batch, non per-tick). */
        private const val FLUSH_INTERVAL_MS = 5_000L
    }
}
