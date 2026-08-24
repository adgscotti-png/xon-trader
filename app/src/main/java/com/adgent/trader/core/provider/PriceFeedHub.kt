package com.adgent.trader.core.provider

import com.adgent.trader.core.database.TickerCacheDao
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.data.SettingsRepository
import com.adgent.trader.data.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Live hub della WATCHLIST (max 20 coppie): sostituisce `TickerRepository.ensureLive`.
 * Combina watchlist + impostazioni (default/override) + salute provider per risolvere
 * il provider effettivo di ogni coppia, poi apre UN WebSocket per exchange con ≥1
 * coppia e fa delta subscribe/unsubscribe. I tick aggiornano la mappa live e la
 * cache Room (sparkline preservata). Con [setForeground] false (SAVER in background)
 * tutti gli stream vengono chiusi: niente connessioni inutili, batteria rispettata.
 */
class PriceFeedHub(
    private val registry: ProviderRegistry,
    private val watchlistRepo: WatchlistRepository,
    private val settingsRepo: SettingsRepository,
    private val router: AutoProviderRouter,
    private val mapper: SymbolMapper,
    private val tickerCacheDao: TickerCacheDao,
    private val scope: CoroutineScope,
) {
    private val live = ConcurrentHashMap<String, PriceTick>() // "$provider:$symbol"
    private val desiredKeys = ConcurrentHashMap.newKeySet<String>()
    private val subscribed = ConcurrentHashMap<ProviderId, Set<String>>()
    private val syncMutex = Mutex()

    private val _liveVersion = MutableStateFlow(0L)
    val liveVersion = _liveVersion.asStateFlow()

    @Volatile private var foreground = true
    @Volatile private var lastDesired: Map<ProviderId, Set<String>> = emptyMap()
    private var job: Job? = null

    fun liveTick(provider: ProviderId, symbol: String): PriceTick? =
        live["${provider.name}:$symbol"]

    /** Chiude/apre gli stream (SAVER background → false). */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        scope.launch { syncMutex.withLock { syncWith(lastDesired) } }
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            combine(
                watchlistRepo.observe(),
                settingsRepo.settings,
                registry.health,
            ) { watchlist, settings, health ->
                val perCoin = settings.perCoinProviders
                val default = settings.defaultProvider.providerId
                val desired = HashMap<ProviderId, MutableSet<String>>()
                for (item in watchlist) {
                    val pair = mapper.fromCompact(item.symbol) ?: continue
                    val provider = router.resolve(pair, perCoin, default, health)
                    desired.getOrPut(provider) { mutableSetOf() }.add(item.symbol)
                }
                desired
            }.collect { desired -> syncWith(desired) }
        }
        registry.all().forEach { p ->
            scope.launch {
                p.tickFlow().collect { batch -> onTicks(p.id, batch) }
            }
        }
    }

    private fun syncWith(desired: Map<ProviderId, Set<String>>) {
        lastDesired = desired
        desiredKeys.clear()
        desired.forEach { (p, syms) -> syms.forEach { desiredKeys.add("${p.name}:$it") } }

        for (p in registry.enabledIds()) {
            val syms = desired[p].orEmpty()
            val adapter = registry.get(p)
            if (syms.isEmpty() || !foreground) {
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
        var any = false
        for (t in batch) {
            val key = "${provider.name}:${t.symbol}"
            if (key !in desiredKeys) continue
            live[key] = t
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
        if (any) _liveVersion.value += 1
    }
}
