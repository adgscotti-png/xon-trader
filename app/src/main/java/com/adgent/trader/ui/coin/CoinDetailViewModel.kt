package com.adgent.trader.ui.coin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.ui.chart.ChartMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CoinDetailUiState(
    val symbol: String = "",
    val klines: List<Kline> = emptyList(),
    val timeframe: Timeframe = Timeframe.DEFAULT,
    val chartMode: ChartMode = ChartMode.CANDLES,
    val showMa: Boolean = true,
    val showEma: Boolean = false,
    val showBb: Boolean = false,
    val livePrice: Double? = null,
    /** Statistiche 24h dal tick live (fallback cache via refreshTickers del mercati). */
    val changePercent24h: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val quoteVolume24h: Double? = null,
    val loading: Boolean = true,
    val offline: Boolean = false,
)

class CoinDetailViewModel(
    private val container: AppContainer,
    private val symbol: String,
) : ViewModel() {

    private val tickerRepo = container.tickerRepo
    private val chartRepo = container.chartRepo
    private val watchlistRepo = container.watchlistRepo

    private val _state = MutableStateFlow(CoinDetailUiState(symbol = symbol))
    val state = _state.asStateFlow()

    init {
        tickerRepo.ensureLive()

        // Prezzo live + stats 24h dal flusso WS.
        viewModelScope.launch {
            tickerRepo.liveVersion.collect {
                tickerRepo.liveTick(symbol)?.let { t ->
                    _state.update { s ->
                        s.copy(
                            livePrice = t.price,
                            changePercent24h = t.changePercent24h,
                            high24h = t.high24h.takeIf { it > 0 },
                            low24h = t.low24h.takeIf { it > 0 },
                            quoteVolume24h = t.quoteVolume24h.takeIf { it > 0 },
                        )
                    }
                }
            }
        }

        loadTimeframe(Timeframe.DEFAULT, initial = true)
        // Fallback REST per le statistiche se il WS non è ancora arrivato.
        viewModelScope.launch {
            tickerRepo.refreshTickers(listOf(symbol), force = true)
        }
    }

    fun setTimeframe(tf: Timeframe) {
        if (tf == _state.value.timeframe) return
        _state.update { it.copy(timeframe = tf) }
        loadTimeframe(tf)
    }

    fun setChartMode(mode: ChartMode) = _state.update { it.copy(chartMode = mode) }

    /** Toggle degli overlay del grafico (mutuamente esclusivi MA/EMA). */
    fun toggleOverlay(kind: OverlayKind) {
        _state.update { s ->
            when (kind) {
                OverlayKind.MA -> s.copy(showMa = !s.showMa, showEma = if (!s.showMa) false else s.showEma)
                OverlayKind.EMA -> s.copy(showEma = !s.showEma, showMa = if (!s.showEma) false else s.showMa)
                OverlayKind.BB -> s.copy(showBb = !s.showBb)
            }
        }
    }

    enum class OverlayKind { MA, EMA, BB }

    fun retry() = loadTimeframe(_state.value.timeframe)

    fun toggleFavorite() {
        viewModelScope.launch {
            if (watchlistRepo.contains(symbol)) watchlistRepo.remove(symbol)
            else watchlistRepo.add(symbol)
        }
    }

    suspend fun isFavorite(): Boolean =
        runCatching { watchlistRepo.contains(symbol) }.getOrDefault(false)

    private fun loadTimeframe(tf: Timeframe, initial: Boolean = false) {
        viewModelScope.launch {
            // Prima la cache (istantaneo), poi il refresh di rete.
            val cached = runCatching { chartRepo.cached(symbol, tf) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                _state.update { it.copy(klines = cached, loading = false) }
            }
            val refreshed = chartRepo.refresh(symbol, tf)
            if (refreshed.isSuccess && refreshed.getOrThrow().isNotEmpty()) {
                _state.update { it.copy(klines = refreshed.getOrThrow(), loading = false, offline = false) }
            } else if (cached.isEmpty()) {
                _state.update { it.copy(loading = false, offline = true) }
            } else if (!refreshed.isSuccess && !initial) {
                _state.update { it.copy(offline = true) }
            }
        }
    }
}
