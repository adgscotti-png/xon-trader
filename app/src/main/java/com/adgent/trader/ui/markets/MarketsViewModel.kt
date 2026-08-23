package com.adgent.trader.ui.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
import com.adgent.trader.core.database.SymbolEntity
import com.adgent.trader.core.database.TickerCacheEntity
import com.adgent.trader.data.MarketRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class MarketFilter(val label: String) {
    FAVORITES("Favorites"),
    TOP("All"),
    GAINERS("Gainers"),
    LOSERS("Losers"),
}

data class MarketsUiState(
    val rows: List<MarketRow> = emptyList(),
    val filter: MarketFilter = MarketFilter.TOP,
    val query: String = "",
    val searching: Boolean = false,
    val searchResults: List<MarketRow> = emptyList(),
    val loading: Boolean = true,
    val offlineSinceMs: Long? = null,
)

class MarketsViewModel(container: AppContainer) : ViewModel() {

    private val tickerRepo = container.tickerRepo
    private val watchlistRepo = container.watchlistRepo

    private val _state = MutableStateFlow(MarketsUiState())
    val state = _state.asStateFlow()

    private val bases = HashMap<String, String>() // symbol → base asset

    /** Catalogo completo coppie Binance USDT attive: fonte della ricerca. */
    @Volatile private var catalog: List<SymbolEntity> = emptyList()
    private var pollJob: Job? = null
    private var searchJob: Job? = null
    private var catalogJob: Job? = null
    @Volatile private var symbolsLoaded = false

    /**
     * Carica il catalogo coppie USDT (exchangeInfo → Room) in single-flight:
     * la ricerca lo joina quando è ancora vuoto, così una exchangeInfo lenta o
     * fallita al primo avvio non lascia la ricerca morta per tutta la sessione.
     */
    private fun ensureCatalog(): Job {
        catalogJob?.let { if (it.isActive) return it }
        catalogJob = viewModelScope.launch {
            val list = runCatching {
                tickerRepo.ensureSymbols().ifEmpty { tickerRepo.allSymbols() }
            }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                catalog = list
                bases.clear()
                bases.putAll(tickerRepo.symbolMap(list))
                symbolsLoaded = true
            }
        }
        return catalogJob
    }

    init {
        val scope = viewModelScope

        // Flusso reattivo: cache Room (ticker) × watchlist × versione live WS.
        scope.launch {
            combine(
                tickerRepo.observeCached(limit = 300),
                watchlistRepo.observe(),
                tickerRepo.liveVersion,
            ) { cached, favorites, _ ->
                buildRows(cached, favorites.map { it.symbol }.toSet())
            }.collect { rows ->
                _state.update { it.copy(rows = applyFilter(rows, it.filter), loading = false) }
            }
        }

        scope.launch {
            bootstrap()
        }
    }

    private suspend fun bootstrap() {
        // Catalogo COMPLETO delle coppie USDT (exchangeInfo → Room): anche se la
        // lista mostra solo i top volume, la ricerca copre tutto il mercato.
        ensureCatalog().join()

        watchlistRepo.ensureDefaults()
        tickerRepo.ensureLive()

        // Primo avvio / cache fredda: seeding dei top volume USDT, così la lista
        // "All" mostra il mercato e non solo i preferiti di default.
        if (tickerRepo.observeCached(limit = 1).first().size < 30) {
            runCatching { tickerRepo.refreshTopMarket() }
        }

        val wanted = wantedSymbols()
        val refreshed = tickerRepo.refreshTickers(wanted)
        if (refreshed.isFailure) {
            _state.update { it.copy(offlineSinceMs = System.currentTimeMillis()) }
        } else {
            _state.update { it.copy(offlineSinceMs = null) }
        }

        // Sparkline solo per le prime righe visibili + preferiti (budget richieste).
        val sparkTargets = wanted.take(SPARK_BUDGET)
        tickerRepo.refreshSparklines(sparkTargets)

        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (_state.value.offlineSinceMs == null) {
                    tickerRepo.refreshTickers(wantedSymbols(), force = true)
                    tickerRepo.refreshSparklines(wantedSymbols().take(SPARK_BUDGET))
                } else {
                    // retry online
                    val ok = tickerRepo.refreshTickers(wantedSymbols()).isSuccess
                    if (ok) _state.update { it.copy(offlineSinceMs = null) }
                }
            }
        }
    }

    /** Preferiti sempre inclusi + top volume dalla cache. */
    private suspend fun wantedSymbols(): List<String> {
        val favs = watchlistRepo.all().map { it.symbol }
        val top = tickerRepo.observeCached(120).first().map { it.symbol }
        return (favs + top).distinct()
    }

    private fun buildRows(cached: List<TickerCacheEntity>, favorites: Set<String>): List<MarketRow> =
        cached.mapNotNull { c ->
            val liveTick = tickerRepo.liveTick(c.symbol)
            MarketRow(
                symbol = c.symbol,
                base = bases[c.symbol] ?: c.symbol.removeSuffix("USDT"),
                price = liveTick?.price ?: c.price,
                changePercent24h = liveTick?.changePercent24h ?: c.changePercent24h,
                high24h = liveTick?.high24h ?: c.high24h,
                low24h = liveTick?.low24h ?: c.low24h,
                quoteVolume24h = liveTick?.quoteVolume24h ?: c.quoteVolume24h,
                sparkline = c.sparkline.split(",").mapNotNull { s -> s.toDoubleOrNull() },
                isFavorite = c.symbol in favorites,
            )
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
     * Ricerca lettera-per-lettera su TUTTO il catalogo Binance USDT attivo,
     * non solo sulle righe già caricate in lista: prima i prefissi, poi le
     * occorrenze interne. I prezzi dei risultati assenti dalla cache vengono
     * scaricati in batch (il dettaglio poi funziona su qualunque coppia).
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
            delay(120) // smorza la digitazione
            // Catalogo non ancora pronto (exchangeInfo lenta/fallita al primo
            // avvio): attendi il load single-flight prima di dichiarare "no results".
            if (catalog.isEmpty()) ensureCatalog().join()
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

            val favs = watchlistRepo.all().map { it.symbol }.toSet()
            val bySym = tickerRepo.observeCached(limit = 1_000).first().associateBy { it.symbol }
            val missing = ranked.map { it.symbol }
                .filter { sym -> bySym[sym]?.updatedAt ?: 0L == 0L }
            if (missing.isNotEmpty()) {
                runCatching { tickerRepo.refreshTickers(missing, force = true) }
            }
            val fresh = tickerRepo.observeCached(limit = 1_000).first().associateBy { it.symbol }

            _state.update {
                it.copy(
                    searchResults = ranked.map { s ->
                        fresh[s.symbol]?.let { c ->
                            val liveTick = tickerRepo.liveTick(c.symbol)
                            MarketRow(
                                symbol = c.symbol,
                                base = bases[c.symbol] ?: s.base,
                                price = liveTick?.price ?: c.price,
                                changePercent24h = liveTick?.changePercent24h ?: c.changePercent24h,
                                high24h = liveTick?.high24h ?: c.high24h,
                                low24h = liveTick?.low24h ?: c.low24h,
                                quoteVolume24h = liveTick?.quoteVolume24h ?: c.quoteVolume24h,
                                sparkline = c.sparkline.split(",").mapNotNull { v -> v.toDoubleOrNull() },
                                isFavorite = c.symbol in favs,
                            )
                        // Nessun dato raggiungibile: riga comunque presente, prezzo "—".
                        } ?: MarketRow(
                            symbol = s.symbol, base = s.base, price = 0.0,
                            changePercent24h = 0.0, high24h = 0.0, low24h = 0.0,
                            quoteVolume24h = 0.0, sparkline = emptyList(),
                            isFavorite = s.symbol in favs,
                        )
                    },
                )
            }
        }
    }

    fun toggleFavorite(symbol: String) {
        viewModelScope.launch {
            if (watchlistRepo.contains(symbol)) watchlistRepo.remove(symbol)
            else watchlistRepo.add(symbol)
        }
    }

    fun retry() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch { bootstrap() }
    }

    companion object {
        private const val MAX_ROWS = 150
        private const val MAX_SEARCH_RESULTS = 30
        private const val SPARK_BUDGET = 40
    }
}
