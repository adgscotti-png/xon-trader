package com.adgent.trader.ui.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
import com.adgent.trader.core.database.SymbolEntity
import com.adgent.trader.core.database.TickerCacheEntity
import com.adgent.trader.core.provider.ProviderId
import com.adgent.trader.core.provider.ProviderState
import com.adgent.trader.data.MarketRow
import com.adgent.trader.data.ProviderSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MarketFilter(val label: String) {
    FAVORITES("Favorites"),
    TOP("All"),
    GAINERS("Gainers"),
    LOSERS("Losers"),
}

data class MarketsUiState(
    val rows: List<MarketRow> = emptyList(),
    val provider: ProviderSelection = ProviderSelection.AUTO,
    val filter: MarketFilter = MarketFilter.TOP,
    val query: String = "",
    val searching: Boolean = false,
    val searchResults: List<MarketRow> = emptyList(),
    val loading: Boolean = true,
    val offlineSinceMs: Long? = null,
    /** Sorgenti live attive (provider delle coppie in watchlist) per il LiveBadge. */
    val liveSources: List<String> = emptyList(),
    /** Messaggio failover quando un provider configurato è giù (banner). */
    val failoverMessage: String? = null,
    /** Messaggio effimero (es. cap watchlist raggiunto), consumato dalla UI. */
    val message: String? = null,
)

/**
 * Lista mercati multi-provider: i dati REST arrivano SOLO a schermo attivo
 * (bootstrap + onResume) o col pulsante refresh; niente polling in background.
 * Il live è limitato alla watchlist (≤20 coppie) via PriceFeedHub.
 */
class MarketsViewModel(container: AppContainer) : ViewModel() {

    private val marketDataRepo = container.marketDataRepo
    private val watchlistRepo = container.watchlistRepo
    private val registry = container.providerRegistry
    private val settingsRepo = container.settingsRepo

    private val _state = MutableStateFlow(MarketsUiState())
    val state = _state.asStateFlow()

    /** Chip selezionato: AUTO aggrega tutti i provider. */
    private val selectedProvider = MutableStateFlow(ProviderSelection.AUTO)

    /** Opzioni chip: AUTO + solo i provider registrati (gli adapter arrivano per ondate). */
    val providerOptions: List<ProviderSelection> =
        ProviderSelection.entries.filter { sel ->
            sel.providerId == null || sel.providerId in registry.enabledIds()
        }

    private val bases = HashMap<String, String>() // symbol → base asset

    /** Catalogo del provider selezionato: fonte della ricerca. */
    @Volatile private var catalog: List<SymbolEntity> = emptyList()
    private var searchJob: Job? = null
    private var catalogJob: Job? = null
    @Volatile private var symbolsLoaded = false

    init {
        val scope = viewModelScope

        // Riga reattiva: cache del provider selezionato × watchlist × live hub.
        scope.launch {
            selectedProvider
                .flatMapLatest { sel ->
                    combine(
                        marketDataRepo.observeCached(sel.providerId, 300),
                        watchlistRepo.observe(),
                        marketDataRepo.liveVersion(),
                    ) { cached, favs, _ -> buildRows(cached, favs, sel) }
                }
                .collect { rows ->
                    _state.update { it.copy(rows = applyFilter(rows, it.filter), loading = false) }
                }
        }

        // Sorgenti live per il badge (provider delle coppie in watchlist).
        scope.launch {
            watchlistRepo.observe().collect { favs ->
                val sources = favs.mapNotNull { ProviderId.fromName(it.provider) }
                    .distinct()
                    .map { it.label }
                _state.update { it.copy(liveSources = sources) }
            }
        }

        // Banner failover: un provider configurato (default o per-coin) giù → spiega il fallback.
        scope.launch {
            combine(registry.health, settingsRepo.settings) { health, settings ->
                val requested = mutableSetOf<ProviderId>()
                settings.defaultProvider.providerId?.let { requested += it }
                settings.perCoinProviders.values.forEach { name ->
                    ProviderId.fromName(name)?.let { requested += it }
                }
                val down = requested.firstOrNull { health[it]?.state == ProviderState.DOWN }
                down?.let { "${it.label} temporarily unavailable — using another source" }
            }.collect { msg ->
                _state.update { it.copy(failoverMessage = msg) }
            }
        }

        scope.launch {
            watchlistRepo.ensureDefaults()
            bootstrap(selectedProvider.value)
        }
    }

    // ---------- Caricamento per provider ----------

    /** Seleziona un provider (chip): ricarica catalogo + dati del tab. */
    fun setProvider(sel: ProviderSelection) {
        if (selectedProvider.value == sel) return
        selectedProvider.value = sel
        _state.update { it.copy(provider = sel, loading = true) }
        viewModelScope.launch { bootstrap(sel) }
    }

    /** A schermo attivo (resume): refresh rispettando i TTL (cache entro 60s). */
    fun onResume() {
        viewModelScope.launch { refresh(selectedProvider.value, force = false) }
    }

    /** Pulsante refresh: forza il download dei ranking del provider selezionato. */
    fun refresh() {
        viewModelScope.launch { refresh(selectedProvider.value, force = true) }
    }

    fun retry() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch { bootstrap(selectedProvider.value) }
    }

    private suspend fun bootstrap(sel: ProviderSelection) {
        val pid = effectiveProviderId(sel) ?: return
        ensureCatalog(pid).join()
        // Primo avvio / cache fredda: seeding dei top del provider, così la
        // lista "All" mostra il mercato e non solo i preferiti di default.
        if (marketDataRepo.observeCached(pid, 1).first().size < 30) {
            runCatching { marketDataRepo.refreshTopMarket(pid) }
        }
        applyOffline(marketDataRepo.refreshTickers(pid, wantedSymbols(pid), force = false))
        marketDataRepo.refreshSparklines(pid, wantedSymbols(pid).take(SPARK_BUDGET))
        _state.update { it.copy(loading = false) }
    }

    private suspend fun refresh(sel: ProviderSelection, force: Boolean) {
        val pid = effectiveProviderId(sel) ?: return
        applyOffline(marketDataRepo.refreshTickers(pid, wantedSymbols(pid), force = force))
        marketDataRepo.refreshSparklines(pid, wantedSymbols(pid).take(SPARK_BUDGET))
    }

    private suspend fun applyOffline(result: Result<Unit>) {
        _state.update {
            it.copy(offlineSinceMs = if (result.isFailure) System.currentTimeMillis() else null)
        }
    }

    /** AUTO → default configurato; altrimenti il provider del chip. */
    private suspend fun effectiveProviderId(sel: ProviderSelection): ProviderId? {
        sel.providerId?.let { return it }
        settingsRepo.settings.first().defaultProvider.providerId?.let { return it }
        return ProviderId.BINANCE
    }

    /**
     * Carica il catalogo del provider (exchangeInfo → Room) in single-flight:
     * la ricerca lo joina quando è ancora vuoto, così un load lento o fallito
     * al primo avvio non lascia la ricerca morta per tutta la sessione.
     */
    private fun ensureCatalog(pid: ProviderId): Job {
        catalogJob?.cancel()
        return viewModelScope.launch {
            marketDataRepo.ensureCatalog(pid)
            val list = marketDataRepo.catalogFor(pid)
            if (list.isNotEmpty()) {
                catalog = list
                bases.clear()
                bases.putAll(marketDataRepo.symbolMap(list))
                symbolsLoaded = true
            }
        }.also { catalogJob = it }
    }

    /** Preferiti del provider + top volume dalla cache dello stesso provider. */
    private suspend fun wantedSymbols(pid: ProviderId): List<String> {
        val favs = watchlistRepo.all().filter { it.provider == pid.name }.map { it.symbol }
        val top = marketDataRepo.observeCached(pid, 120).first().map { it.symbol }
        return (favs + top).distinct()
    }

    private fun buildRows(
        cached: List<TickerCacheEntity>,
        favorites: List<com.adgent.trader.core.database.WatchlistEntity>,
        sel: ProviderSelection,
    ): List<MarketRow> {
        val favKeys = favorites.map { "${it.provider}:${it.symbol}" }.toSet()
        return cached.mapNotNull { c ->
            val provider = ProviderId.fromName(c.provider) ?: ProviderId.BINANCE
            val liveTick = marketDataRepo.liveTick(provider, c.symbol)
            MarketRow(
                symbol = c.symbol,
                base = bases[c.symbol]
                    ?: marketDataRepo.fromCompact(c.symbol)?.base
                    ?: c.symbol.removeSuffix("USDT"),
                price = liveTick?.price ?: c.price,
                changePercent24h = liveTick?.changePercent24h ?: c.changePercent24h,
                high24h = liveTick?.high24h ?: c.high24h,
                low24h = liveTick?.low24h ?: c.low24h,
                quoteVolume24h = liveTick?.quoteVolume24h ?: c.quoteVolume24h,
                sparkline = c.sparkline.split(",").mapNotNull { s -> s.toDoubleOrNull() },
                isFavorite = "${c.provider}:${c.symbol}" in favKeys,
                provider = provider,
            )
        }
    }

    private fun applyFilter(rows: List<MarketRow>, filter: MarketFilter): List<MarketRow> = when (filter) {
        MarketFilter.FAVORITES -> rows.filter { it.isFavorite }
        MarketFilter.TOP -> rows.sortedByDescending { it.quoteVolume24h }
        MarketFilter.GAINERS -> rows.filter { it.quoteVolume24h > 1_000_000 }
            .sortedByDescending { it.changePercent24h }
        MarketFilter.LOSERS -> rows.filter { it.quoteVolume24h > 1_000_000 }
            .sortedBy { it.changePercent24h }
    }.take(MAX_ROWS)

    fun setFilter(filter: MarketFilter) {
        _state.update { it.copy(filter = filter, rows = applyFilter(it.rows, filter)) }
    }

    fun setSearching(active: Boolean) {
        searchJob?.cancel()
        _state.update { it.copy(searching = active, query = "", searchResults = emptyList()) }
    }

    /**
     * Ricerca lettera-per-lettera sul catalogo del provider selezionato
     * (in AUTO: quello di default). Prima i prefissi, poi le occorrenze
     * interne; i prezzi assenti dalla cache vengono scaricati in batch.
     */
    fun onQueryChange(query: String) {
        val q = query.trim()
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (q.isEmpty()) {
            _state.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120) // smorza la digitazione
            if (catalog.isEmpty()) ensureCatalog(effectiveProviderId(selectedProvider.value) ?: ProviderId.BINANCE).join()
            val starts = ArrayList<SymbolEntity>()
            val contains = ArrayList<SymbolEntity>()
            catalog.forEach { s ->
                when {
                    s.base.startsWith(q, true) || s.symbol.startsWith(q, true) -> starts += s
                    s.base.contains(q, true) || s.symbol.contains(q, true) -> contains += s
                }
            }
            val ranked = (starts + contains).take(MAX_SEARCH_RESULTS)
            if (ranked.isEmpty()) {
                _state.update { it.copy(searchResults = emptyList()) }
                return@launch
            }

            val pid = effectiveProviderId(selectedProvider.value) ?: ProviderId.BINANCE
            val favKeys = watchlistRepo.all().map { "${it.provider}:${it.symbol}" }.toSet()
            val bySym = marketDataRepo.observeCached(pid, 1_000).first().associateBy { it.symbol }
            val missing = ranked.map { it.symbol }
                .filter { sym -> bySym[sym]?.updatedAt ?: 0L == 0L }
            if (missing.isNotEmpty()) {
                runCatching { marketDataRepo.refreshTickers(pid, missing, force = true) }
            }
            val fresh = marketDataRepo.observeCached(pid, 1_000).first().associateBy { it.symbol }

            _state.update {
                it.copy(
                    searchResults = ranked.map { s ->
                        fresh[s.symbol]?.let { c ->
                            val liveTick = marketDataRepo.liveTick(pid, c.symbol)
                            MarketRow(
                                symbol = c.symbol,
                                base = bases[c.symbol] ?: s.base,
                                price = liveTick?.price ?: c.price,
                                changePercent24h = liveTick?.changePercent24h ?: c.changePercent24h,
                                high24h = liveTick?.high24h ?: c.high24h,
                                low24h = liveTick?.low24h ?: c.low24h,
                                quoteVolume24h = liveTick?.quoteVolume24h ?: c.quoteVolume24h,
                                sparkline = c.sparkline.split(",").mapNotNull { v -> v.toDoubleOrNull() },
                                isFavorite = "${pid.name}:${c.symbol}" in favKeys,
                                provider = pid,
                            )
                        // Nessun dato raggiungibile: riga comunque presente, prezzo "—".
                        } ?: MarketRow(
                            symbol = s.symbol, base = s.base, price = 0.0,
                            changePercent24h = 0.0, high24h = 0.0, low24h = 0.0,
                            quoteVolume24h = 0.0, sparkline = emptyList(),
                            isFavorite = "${pid.name}:${s.symbol}" in favKeys,
                            provider = pid,
                        )
                    },
                )
            }
        }
    }

    fun toggleFavorite(row: MarketRow) {
        viewModelScope.launch {
            if (watchlistRepo.contains(row.provider, row.symbol)) {
                watchlistRepo.remove(row.provider, row.symbol)
            } else {
                val ok = watchlistRepo.add(row.provider, row.symbol)
                if (!ok) {
                    _state.update { it.copy(message = "Watchlist full: max ${com.adgent.trader.data.WatchlistRepository.MAX_SIZE} coins") }
                }
            }
        }
    }

    /** La UI consuma il messaggio effimero (cap watchlist ecc.). */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        private const val MAX_ROWS = 150
        private const val MAX_SEARCH_RESULTS = 30
        private const val SPARK_BUDGET = 40
    }
}
