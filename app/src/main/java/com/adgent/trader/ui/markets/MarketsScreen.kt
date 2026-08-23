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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adgent.trader.core.common.Format
import com.adgent.trader.data.MarketRow
import com.adgent.trader.ui.appViewModel
import com.adgent.trader.ui.components.ChangeBadge
import com.adgent.trader.ui.components.CoinBadge
import com.adgent.trader.ui.components.PriceText
import com.adgent.trader.ui.components.Sparkline
import com.adgent.trader.ui.theme.MarketGreen
import com.adgent.trader.ui.theme.MarketRed

/**
 * Schermata Mercati: griglia di card live con filtri, ricerca e preferiti.
 * Tap apre il dettaglio con grafico · long-press aggiunge/toglie dai preferiti.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketsScreen(
    onOpenCoin: (String) -> Unit,
    vm: MarketsViewModel = appViewModel { MarketsViewModel(it) },
) {
    val state by vm.state.collectAsStateWithLifecycle()

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
            MarketHeader(onSearch = { vm.setSearching(true) })
            FilterChips(selected = state.filter, onSelect = vm::setFilter)
            state.offlineSinceMs?.let { OfflineBanner() }
            Box(Modifier.weight(1f)) {
                when {
                    state.loading -> LoadingIndicator()
                    state.rows.isEmpty() && state.offlineSinceMs != null -> EmptyOffline(vm::retry)
                    else -> MarketGrid(state.rows, onOpenCoin, vm::toggleFavorite)
                }
            }
        }
    }
}

// ---------- Header ----------

@Composable
private fun MarketHeader(onSearch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Markets",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(3.dp))
            LiveBadge()
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Rounded.Search, contentDescription = "Search a coin")
        }
    }
}

@Composable
private fun LiveBadge() {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .alpha(alpha)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Live data · Binance",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Filtri ----------

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
    onOpenCoin: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    showFavoriteToggle: Boolean = false,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(rows, key = { it.symbol }) { row ->
            MarketCard(
                row = row,
                showFavorite = row.isFavorite,
                showFavoriteToggle = showFavoriteToggle,
                onToggleFavorite = { onToggleFavorite(row.symbol) },
                modifier = Modifier.combinedClickable(
                    onClick = { onOpenCoin(row.symbol) },
                    onLongClick = { onToggleFavorite(row.symbol) },
                ),
            )
        }
    }
}

/**
 * Card mercato 2-per-riga (stile TabTrader): badge, nome/simbolo, sparkline,
 * prezzo, variazione % e statistiche 24h. I dati sono gli stessi della vecchia
 * riga lista — cambia solo la presentazione.
 */
@Composable
private fun MarketCard(
    row: MarketRow,
    showFavorite: Boolean,
    showFavoriteToggle: Boolean,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(12.dp),
        ) {
            // Riga superiore: badge, nome/simbolo, sparkline (o toggle preferito in ricerca).
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinBadge(base = row.base, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.base,
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
                    Text(
                        row.symbol,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
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
                } else {
                    Sparkline(
                        values = row.sparkline,
                        positive = row.changePercent24h >= 0,
                        modifier = Modifier
                            .width(52.dp)
                            .height(22.dp)
                            .alpha(if (row.sparkline.isEmpty()) 0f else 1f),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Prezzo + badge variazione.
            Row(verticalAlignment = Alignment.Bottom) {
                PriceText(
                    price = row.price,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                ChangeBadge(percent = row.changePercent24h, compact = true)
            }

            Spacer(Modifier.height(10.dp))

            // Mini-statistiche 24h in fondo alla card: massimo, minimo, volume.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CardStat("24h H", Format.price(row.high24h), MarketGreen, Modifier.weight(1f))
                CardStat("24h L", Format.price(row.low24h), MarketRed, Modifier.weight(1f))
                CardStat("Vol", Format.compact(row.quoteVolume24h), null, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CardStat(
    label: String,
    value: String,
    valueColor: Color?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    onOpenCoin: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
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
        onOpenCoin = onOpenCoin,
        onToggleFavorite = onToggleFavorite,
        showFavoriteToggle = true,
    )
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
