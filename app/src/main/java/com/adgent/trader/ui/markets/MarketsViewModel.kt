package com.adgent.trader.ui.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
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
    FAVORITES("Preferiti"),
    TOP("Tutti"),
    GAINERS("Guadagni"),
    LOSERS("Perdite"),
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
    private var pollJob: Job? = null
    @Volatile private var symbolsLoaded = false

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
        val symbols = runCatching { tickerRepo.ensureSymbols() }.getOrDefault(emptyList())
        bases.clear()
        bases.putAll(tickerRepo.symbolMap(symbols))
        symbolsLoaded = symbols.isNotEmpty()

        watchlistRepo.ensureDefaults()
        tickerRepo.ensureLive()

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
        _state.update { it.copy(searching = active, query = "", searchResults = emptyList()) }
    }

    fun onQueryChange(query: String) {
        _state.update { current ->
            current.copy(
                query = query,
                searchResults = if (query.isBlank()) emptyList() else current.rows.filter {
                    it.base.contains(query.trim(), ignoreCase = true) ||
                        it.symbol.contains(query.trim(), ignoreCase = true)
                }.take(20),
            )
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
        private const val SPARK_BUDGET = 40
    }
}
