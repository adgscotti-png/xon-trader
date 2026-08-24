package com.adgent.trader.ui.coin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.model.AlertType
import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.provider.ProviderId
import com.adgent.trader.core.service.PriceFeedController
import com.adgent.trader.data.DataMode
import com.adgent.trader.ui.chart.ChartMode
import com.adgent.trader.ui.chart.OscKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CoinDetailUiState(
    val symbol: String = "",
    val provider: ProviderId = ProviderId.BINANCE,
    /** Nome base mostrato (alias risolti: XBT→BTC). */
    val base: String = "",
    /** Quote mostrata (es. USDT per Binance, USD per Kraken). */
    val quote: String = "USDT",
    val klines: List<Kline> = emptyList(),
    val timeframe: Timeframe = Timeframe.DEFAULT,
    val chartMode: ChartMode = ChartMode.CANDLES,
    val showMa: Boolean = false,
    val showEma: Boolean = false,
    val showBb: Boolean = false,
    /** Sub-chart oscillatore attivo (null = nessuno). */
    val oscillator: OscKind? = null,
    val livePrice: Double? = null,
    /** Statistiche 24h dal tick live (fallback cache via refreshTickers del mercati). */
    val changePercent24h: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val quoteVolume24h: Double? = null,
    val loading: Boolean = true,
    val offline: Boolean = false,
    /** Messaggio one-shot per la UI (avviso creato dal grafico). */
    val quickAlertMsg: String? = null,
)

/**
 * Dettaglio di una coin su un exchange specifico. Il live (watchlist) e il
 * grafico sono provider-aware; il picker "Source" cambia il provider per-coin
 * (override salvato nelle impostazioni → il router lo userà anche in Auto).
 */
class CoinDetailViewModel(
    private val container: AppContainer,
    private val symbol: String,
    providerName: String,
) : ViewModel() {

    /** Provider di partenza (dalla navigazione; default Binance per i deep link legacy). */
    val provider: ProviderId = ProviderId.fromName(providerName) ?: ProviderId.BINANCE

    /** Provider disponibili per il picker, ordinati per priorità. */
    val enabledProviders: List<ProviderId> =
        container.providerRegistry.enabledIds().sortedBy { it.defaultPriority }

    private val marketDataRepo = container.marketDataRepo
    private val chartRepo = container.chartRepo
    private val watchlistRepo = container.watchlistRepo
    private val settingsRepo = container.settingsRepo

    /** Coppia canonica stabile (non cambia con lo switch provider). */
    private val canonicalPair = marketDataRepo.canonicalOf(provider, symbol)

    private val _state = MutableStateFlow(
        CoinDetailUiState(
            symbol = symbol,
            provider = provider,
            base = canonicalPair?.base ?: symbol.removeSuffix("USDT"),
            quote = canonicalPair?.quote ?: "USDT",
        )
    )
    val state = _state.asStateFlow()

    init {
        // Prezzo live + stats 24h dal flusso del live hub (watchlist).
        viewModelScope.launch {
            marketDataRepo.liveVersion().collect {
                marketDataRepo.liveTick(provider, symbol)?.let { t ->
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
            marketDataRepo.refreshTickers(provider, listOf(symbol), force = true)
        }
    }

    /** Cambia la fonte per-coin: salva l'override e ricarica grafico + stats. */
    fun setProvider(newProvider: ProviderId) {
        if (newProvider == _state.value.provider) return
        viewModelScope.launch {
            val newSymbol = canonicalPair?.let { marketDataRepo.providerSymbol(newProvider, it) }
            if (canonicalPair != null && newSymbol == null) {
                // L'exchange non lista questa coppia (es. Kraken non ha USDT):
                // niente switch, niente override, solo un messaggio chiaro.
                _state.update {
                    it.copy(quickAlertMsg = "${newProvider.label} does not list ${canonicalPair!!.key}")
                }
                return@launch
            }
            if (canonicalPair != null) {
                settingsRepo.setPerCoinProvider(canonicalPair.key, newProvider)
            }
            val resolved = newSymbol ?: symbol
            _state.update { s ->
                s.copy(
                    provider = newProvider,
                    symbol = resolved,
                    base = canonicalPair?.base ?: s.base,
                    quote = canonicalPair?.quote ?: s.quote,
                    loading = true,
                    klines = emptyList(),
                    livePrice = null,
                    changePercent24h = null,
                    high24h = null,
                    low24h = null,
                    quoteVolume24h = null,
                )
            }
            loadTimeframe(_state.value.timeframe, initial = true)
            marketDataRepo.refreshTickers(newProvider, listOf(resolved), force = true)
        }
    }

    fun setTimeframe(tf: Timeframe) {
        if (tf == _state.value.timeframe) return
        _state.update { it.copy(timeframe = tf) }
        loadTimeframe(tf)
    }

    fun setChartMode(mode: ChartMode) = _state.update { it.copy(chartMode = mode) }

    /** Seleziona/switcha-off il sub-chart oscillatore. */
    fun setOscillator(kind: OscKind?) =
        _state.update { it.copy(oscillator = if (it.oscillator == kind) null else kind) }

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

    /**
     * Avviso creato direttamente dal grafico (pressione prolungata): salva la
     * regola al prezzo scelto (con il provider della coin), garantisce il feed
     * realtime attivo e notifica la UI.
     */
    fun quickAlert(price: Double, above: Boolean) {
        viewModelScope.launch {
            val type = if (above) AlertType.PRICE_ABOVE else AlertType.PRICE_BELOW
            container.alertRepo.save(
                AlertRuleEntity(
                    id = 0L,
                    symbol = symbol,
                    type = type.name,
                    threshold = price,
                    repeatable = false,
                    note = "From chart",
                    enabled = true,
                    createdAt = System.currentTimeMillis(),
                    lastTriggeredAt = null,
                    provider = provider.name,
                ),
            )
            val realtime = runCatching {
                container.settingsRepo.settings.first().dataMode
            }.getOrDefault(DataMode.REALTIME) == DataMode.REALTIME
            if (realtime) {
                PriceFeedController.start(container.appContext)
            }
            _state.update {
                it.copy(
                    quickAlertMsg = "Alert created: ${it.base} " +
                        (if (above) "above" else "below") + " $" + Format.price(price),
                )
            }
        }
    }

    /** Messaggio toast consumato dalla UI. */
    fun clearQuickAlertMsg() = _state.update { it.copy(quickAlertMsg = null) }

    fun toggleFavorite() {
        viewModelScope.launch {
            if (watchlistRepo.contains(provider, symbol)) {
                watchlistRepo.remove(provider, symbol)
            } else {
                watchlistRepo.add(provider, symbol)
            }
        }
    }

    suspend fun isFavorite(): Boolean =
        runCatching { watchlistRepo.contains(provider, symbol) }.getOrDefault(false)

    private fun loadTimeframe(tf: Timeframe, initial: Boolean = false) {
        viewModelScope.launch {
            val p = _state.value.provider
            val s = _state.value.symbol
            // Prima la cache (istantaneo), poi il refresh di rete.
            val cached = runCatching { chartRepo.cached(p, s, tf) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                _state.update { it.copy(klines = cached, loading = false) }
            }
            val refreshed = chartRepo.refresh(p, s, tf)
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
