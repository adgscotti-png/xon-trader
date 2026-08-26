package com.adgent.trader.ui.markets

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adgent.trader.appContainer
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.provider.ProviderId
import com.adgent.trader.data.MarketRow
import com.adgent.trader.data.ProviderSelection
import com.adgent.trader.ui.appViewModel
import com.adgent.trader.ui.components.ChangeBadge
import com.adgent.trader.ui.components.CoinBadge
import com.adgent.trader.ui.components.PriceText
import com.adgent.trader.ui.theme.neonCardFrame
import kotlinx.coroutines.delay

/**
 * Schermata Mercati: griglia di card live con filtri, ricerca e preferiti.
 * Tap apre il dettaglio con grafico · long-press aggiunge/toglie dai preferiti.
 * Il provider è selezionabile in pagina (Auto + exchange): i ranking vengono
 * scaricati a schermo attivo o col pulsante refresh, mai in polling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketsScreen(
    onOpenCoin: (String, String) -> Unit,
    vm: MarketsViewModel = appViewModel { MarketsViewModel(it) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // A schermo attivo i dati del provider selezionato si rinfrescano (entro il TTL
    // di 60s della cache), così il ranking non invecchia mai a tab nascosto.
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    // Messaggio effimero (es. watchlist piena) mostrato come banner, poi consumato.
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            vm.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        if (state.searching) {
            SearchBar(
                query = state.query,
                onQueryChange = vm::onQueryChange,
                onClose = { vm.setSearching(false) },
            )
            SearchResults(state.searchResults, onOpenCoin, vm::toggleFavorite)
        } else {
            MarketHeader(
                liveSources = state.liveSources,
                onRefresh = vm::refresh,
                onSearch = { vm.setSearching(true) },
            )
            ProviderChips(options = vm.providerOptions, selected = state.provider, onSelect = vm::setProvider)
            FilterChips(selected = state.filter, onSelect = vm::setFilter)
            message?.let { MessageBanner(it) }
            state.failoverMessage?.let { FailoverBanner(it) }
            state.offlineSinceMs?.let { OfflineBanner() }
            Box(Modifier.weight(1f)) {
                when {
                    state.loading -> LoadingIndicator()
                    state.rows.isEmpty() && state.offlineSinceMs != null -> EmptyOffline(vm::retry)
                    else -> MarketGrid(
                        rows = state.rows,
                        // Quando cambia il ranking (filtro/provider) o si sposta il primo
                        // risultato (la cache si riempie da parziale a mercato pieno) la
                        // griglia deve tornare in cima: LazyGrid ancora lo scroll alla key
                        // del primo item visibile e "nasconde" i nuovi item sopra di esso.
                        resetKey = "${state.filter}:${state.provider.providerId}:${state.rows.firstOrNull()?.let { "${it.provider.name}:${it.symbol}" }}",
                        onOpenCoin = onOpenCoin,
                        onToggleFavorite = vm::toggleFavorite,
                        showProvider = state.provider == ProviderSelection.AUTO,
                    )
                }
            }
        }
    }
}

// ---------- Header ----------

@Composable
private fun MarketHeader(
    liveSources: List<String>,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Markets",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(3.dp))
            LiveBadge(liveSources)
        }
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "Refresh prices now: downloads the latest prices and rankings for this exchange",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Rounded.Search, contentDescription = "Search a coin")
        }
    }
}

@Composable
private fun LiveBadge(sources: List<String>) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val label = when {
        sources.isEmpty() -> "Live data · Auto"
        sources.size == 1 -> "Live data · ${sources.first()}"
        else -> "Live data · ${sources.size} sources"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .alpha(alpha)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Provider e filtri ----------

/** Chips sorgente dati: Auto (miglior provider disponibile) oppure un exchange. */
@Composable
private fun ProviderChips(
    options: List<ProviderSelection>,
    selected: ProviderSelection,
    onSelect: (ProviderSelection) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        items(options) { sel ->
            FilterChip(
                selected = selected == sel,
                onClick = { onSelect(sel) },
                label = { Text(sel.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun FilterChips(selected: MarketFilter, onSelect: (MarketFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        items(MarketFilter.entries) { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onSelect(f) },
                label = { Text(f.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = Color.White,
                ),
                border = null,
            )
        }
    }
}

// ---------- Griglia mercati ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarketGrid(
    rows: List<MarketRow>,
    resetKey: String,
    onOpenCoin: (String, String) -> Unit,
    onToggleFavorite: (MarketRow) -> Unit,
    showProvider: Boolean,
    showFavoriteToggle: Boolean = false,
) {
    val context = LocalContext.current
    val liveFocus = context.appContainer.liveFocus
    val liveTicks by context.appContainer.priceFeedHub.liveTicks.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    LaunchedEffect(resetKey) { gridState.scrollToItem(0) }

    // Viewport → focus: solo le righe VISIBILI restano live (debounce anti-churn
    // su scroll rapido). Quando scorri, prima le nuove righe diventano live, le
    // fuori schermo vengono disiscritte dall'hub.
    val firstIdx = gridState.firstVisibleItemIndex
    val lastIdx = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstIdx
    val viewportKey = if (rows.isEmpty()) "" else {
        val f = firstIdx.coerceIn(0, rows.lastIndex)
        val l = lastIdx.coerceIn(f, rows.lastIndex)
        buildString {
            for (i in f..l) {
                val r = rows[i]
                append(r.provider.name).append(':').append(r.symbol).append(',')
            }
        }
    }
    LaunchedEffect(viewportKey) {
        if (viewportKey.isEmpty()) return@LaunchedEffect
        val f = gridState.firstVisibleItemIndex.coerceAtLeast(0)
        val l = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.coerceAtLeast(f) ?: f
        if (rows.isEmpty() || f >= rows.size) return@LaunchedEffect
        val slice = rows.subList(f, minOf(l + 1, rows.size))
        if (slice.isEmpty()) return@LaunchedEffect
        val byProvider = HashMap<ProviderId, MutableSet<String>>()
        slice.forEach { r -> byProvider.getOrPut(r.provider) { mutableSetOf() }.add(r.symbol) }
        delay(300)
        liveFocus.onMarketsViewport(byProvider.mapValues { it.value.toSet() })
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // La stessa coppia può esistere su più exchange: chiave composta.
        items(rows, key = { "${it.provider.name}:${it.symbol}" }) { row ->
            // Callback e tick stabiliti per riga: quando cambia SOLO il prezzo live
            // di un'altra riga, questa non viene ricomposta.
            val onOpen = remember(row) { { onOpenCoin(row.symbol, row.provider.name) } }
            val onToggle = remember(row) { { onToggleFavorite(row) } }
            MarketCard(
                row = row,
                live = liveTicks["${row.provider.name}:${row.symbol}"],
                showFavorite = row.isFavorite,
                showFavoriteToggle = showFavoriteToggle,
                showProvider = showProvider,
                onOpenCoin = onOpen,
                onToggleFavorite = onToggle,
            )
        }
    }
}

/**
 * Card mercato 2-per-riga (stile TabTrader): badge, nome/simbolo, prezzo e
 * variazione %. In modalità Auto (più exchange) mostra anche l'exchange di
 * provenienza, così sai da quale mercato arriva il prezzo. Il tick live della
 * hot map (quando presente) sovrascrive prezzo/stats della cache per la riga.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarketCard(
    row: MarketRow,
    live: PriceTick?,
    showFavorite: Boolean,
    showFavoriteToggle: Boolean,
    showProvider: Boolean,
    onOpenCoin: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val display = if (live != null) row.copy(
        price = live.price,
        changePercent24h = live.changePercent24h,
        high24h = live.high24h,
        low24h = live.low24h,
        quoteVolume24h = live.quoteVolume24h,
    ) else row
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .combinedClickable(onClick = onOpenCoin, onLongClick = onToggleFavorite)
            .neonCardFrame(RoundedCornerShape(18.dp))
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(12.dp),
        ) {
            // Riga superiore: badge + nome/simbolo (+ toggle preferito in ricerca).
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinBadge(base = display.base, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            display.base,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showFavorite) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Rounded.Star,
                                contentDescription = null,
                                tint = Color(0xFFF5B301),
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Il simbolo si accorcia (ellipsis) se serve: il nome
                        // dell'exchange ha SEMPRE tutto lo spazio, non deve
                        // mai finire troncato (es. "C" per Coinbase).
                        Text(
                            display.symbol,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (showProvider) {
                            Spacer(Modifier.width(5.dp))
                            Text(
                                display.provider.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                if (showFavoriteToggle) {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = if (showFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = if (showFavorite) "Remove from favorites" else "Add to favorites",
                            tint = if (showFavorite) Color(0xFFF5B301) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Prezzo + badge variazione.
            Row(verticalAlignment = Alignment.Bottom) {
                PriceText(
                    price = display.price,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                ChangeBadge(percent = display.changePercent24h, compact = true)
            }

        }
    }
}

// ---------- Ricerca ----------

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search symbol or name (e.g. BTC)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Close search")
        }
    }
}

@Composable
private fun SearchResults(
    results: List<MarketRow>,
    onOpenCoin: (String, String) -> Unit,
    onToggleFavorite: (MarketRow) -> Unit,
) {
    if (results.isEmpty()) {
        EmptyState(
            title = "No results",
            subtitle = "Try a different symbol: BTC, ETH, SOL…",
        )
        return
    }
    MarketGrid(
        rows = results,
        resetKey = "search:${results.firstOrNull()?.let { "${it.provider.name}:${it.symbol}" }}",
        onOpenCoin = onOpenCoin,
        onToggleFavorite = onToggleFavorite,
        showProvider = true,
        showFavoriteToggle = true,
    )
}

// ---------- Banner ----------

/** Messaggio effimero della view model (es. cap watchlist raggiunto). */
@Composable
private fun MessageBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Spiega che un exchange selezionato è giù e che i prezzi vengono da un altro. */
@Composable
private fun FailoverBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ---------- Stati speciali ----------

@Composable
private fun LoadingIndicator() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            "You are offline: showing the last received data.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyOffline(retry: () -> Unit) {
    EmptyState(
        title = "No data",
        subtitle = "Connection unavailable on first launch. Retry once you are online.",
        action = { TextButton(onClick = retry) { Text("Retry") } },
    )
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}
