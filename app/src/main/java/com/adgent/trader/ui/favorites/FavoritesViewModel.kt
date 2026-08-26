package com.adgent.trader.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
import com.adgent.trader.core.provider.ProviderId
import com.adgent.trader.data.MarketRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoritesUiState(
    val rows: List<MarketRow> = emptyList(),
    val loading: Boolean = true,
    /** Sorgenti live attive (provider delle coppie in watchlist) per il LiveBadge. */
    val liveSources: List<String> = emptyList(),
    /** Messaggio effimero (es. cap watchlist raggiunto), consumato dalla UI. */
    val message: String? = null,
)

/**
 * Pagina Preferiti: la watchlist risolta sul provider EFFETTIVO per coppia
 * (override per-coin → provider archiviato → fallback), così badge/live/tap
 * seguono la scelta fatta nel coin detail anche se la coppia è archiviata
 * sotto un altro exchange. Grafica configurabile in Settings → Appearance
 * (FavoritesStyle): Classic, Neon, Retro 8-bit, Split-flap.
 */
class FavoritesViewModel(container: AppContainer) : ViewModel() {

    private val marketDataRepo = container.marketDataRepo
    private val watchlistRepo = container.watchlistRepo
    private val registry = container.providerRegistry
    private val settingsRepo = container.settingsRepo
    private val router = container.autoProviderRouter

    private val _state = MutableStateFlow(FavoritesUiState())
    val state = _state.asStateFlow()

    init {
        val scope = viewModelScope

        // Righe: watchlist × settings × salute provider × cache × live hub.
        scope.launch {
            combine(
                watchlistRepo.observe(),
                settingsRepo.settings,
                registry.health,
                marketDataRepo.observeCached(null, 1_000),
                marketDataRepo.liveVersion(),
            ) { favs, settings, health, cached, _ ->
                Triple(favs, settings, health) to cached.associateBy { "${it.provider}:${it.symbol}" }
            }.collect { (ctx, byKey) ->
                val (favs, settings, health) = ctx
                val rows = favs.mapNotNull { fav ->
                    val stored = ProviderId.fromName(fav.provider) ?: ProviderId.BINANCE
                    val pair = marketDataRepo.fromCompact(fav.symbol) ?: return@mapNotNull null
                    // Priorità: override per-coin (scelta esplicita nel coin detail) →
                    // provider ARCHIVIATO (scelta esplicita al momento del preferito) →
                    // fallback (default → miglior provider). Il provider archiviato NON
                    // va scavalcato dal default: un preferito aggiunto da Kraken restava
                    // su OKX solo perché il default era OKX.
                    val hasOverride = settings.perCoinProviders.containsKey(pair.key)
                    val eff = if (hasOverride || !router.isUsable(stored, health)) {
                        router.resolve(pair, settings.perCoinProviders, settings.defaultProvider.providerId, health)
                    } else {
                        stored
                    }
                    val effCache = byKey["${eff.name}:${fav.symbol}"]
                    val storedCache = byKey["${stored.name}:${fav.symbol}"]
                    val c = effCache ?: storedCache
                    val rowProvider = if (effCache != null) eff else if (c != null) stored else eff
                    val live = marketDataRepo.liveTick(rowProvider, fav.symbol)
                    MarketRow(
                        symbol = fav.symbol,
                        base = pair.base,
                        price = live?.price ?: c?.price ?: 0.0,
                        changePercent24h = live?.changePercent24h ?: c?.changePercent24h ?: 0.0,
                        high24h = live?.high24h ?: c?.high24h ?: 0.0,
                        low24h = live?.low24h ?: c?.low24h ?: 0.0,
                        quoteVolume24h = live?.quoteVolume24h ?: c?.quoteVolume24h ?: 0.0,
                        sparkline = c?.sparkline?.split(",")?.mapNotNull { s -> s.toDoubleOrNull() } ?: emptyList(),
                        isFavorite = true,
                        provider = rowProvider,
                        storedProvider = stored,
                    )
                }
                _state.update { it.copy(rows = rows, loading = false) }
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

        scope.launch {
            watchlistRepo.ensureDefaults()
        }
    }

    /** A schermo attivo (resume): refresh dei prezzi della watchlist (TTL cache). */
    fun onResume() {
        viewModelScope.launch { refreshFavorites(force = false) }
    }

    /** Pulsante refresh: forza lo scaricamento dei prezzi della watchlist. */
    fun refresh() {
        viewModelScope.launch { refreshFavorites(force = true) }
    }

    private suspend fun refreshFavorites(force: Boolean) {
        val favs = watchlistRepo.all()
        favs.groupBy { it.provider }.forEach { (name, list) ->
            val pid = ProviderId.fromName(name) ?: return@forEach
            runCatching { marketDataRepo.refreshTickers(pid, list.map { it.symbol }, force = force) }
        }
    }

    fun toggleFavorite(row: MarketRow) {
        viewModelScope.launch {
            // Rimozione sul provider ARCHIVIATO (il row.provider può essere
            // quello effettivo risolto, es. override per-coin su Bybit).
            val stored = row.storedProvider ?: row.provider
            if (watchlistRepo.contains(stored, row.symbol)) {
                watchlistRepo.remove(stored, row.symbol)
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
}
