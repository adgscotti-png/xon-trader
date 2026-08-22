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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adgent.trader.data.MarketRow
import com.adgent.trader.ui.appViewModel
import com.adgent.trader.ui.components.ChangeBadge
import com.adgent.trader.ui.components.CoinBadge
import com.adgent.trader.ui.components.PriceText
import com.adgent.trader.ui.components.Sparkline

/**
 * Schermata Mercati: lista live con filtri, ricerca e preferiti.
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
                    else -> MarketList(state.rows, onOpenCoin, vm::toggleFavorite)
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
                "Mercati",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(3.dp))
            LiveBadge()
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Rounded.Search, contentDescription = "Cerca una crypto")
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
            "Dati live · Binance",
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

// ---------- Lista mercati ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarketList(
    rows: List<MarketRow>,
    onOpenCoin: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(rows, key = { _, r -> r.symbol }) { _, row ->
            MarketRowItem(
                row = row,
                showFavorite = row.isFavorite,
                modifier = Modifier
                    .animateItem()
                    .combinedClickable(
                        onClick = { onOpenCoin(row.symbol) },
                        onLongClick = { onToggleFavorite(row.symbol) },
                    ),
            )
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

@Composable
private fun MarketRowItem(
    row: MarketRow,
    showFavorite: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoinBadge(base = row.base, size = 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.base,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (showFavorite) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = null,
                        tint = Color(0xFFF5B301),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Text(
                row.symbol,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Sparkline(
            values = row.sparkline,
            positive = row.changePercent24h >= 0,
            modifier = Modifier
                .width(56.dp)
                .height(26.dp)
                .alpha(if (row.sparkline.isEmpty()) 0f else 1f),
        )
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            PriceText(price = row.price)
            Spacer(Modifier.height(2.dp))
            ChangeBadge(percent = row.changePercent24h, compact = true)
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
            placeholder = { Text("Cerca simbolo o nome (es. BTC)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Chiudi ricerca")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResults(
    results: List<MarketRow>,
    onOpenCoin: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    if (results.isEmpty()) {
        EmptyState(
            title = "Nessun risultato",
            subtitle = "Prova con un simbolo diverso: BTC, ETH, SOL…",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.symbol }) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarketRowItem(
                    row = row,
                    showFavorite = false,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(onClick = { onOpenCoin(row.symbol) }),
                )
                IconButton(onClick = { onToggleFavorite(row.symbol) }) {
                    Icon(
                        imageVector = if (row.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (row.isFavorite) "Togli dai preferiti" else "Aggiungi ai preferiti",
                        tint = if (row.isFavorite) Color(0xFFF5B301) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
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
            "Sei offline: mostro gli ultimi dati ricevuti.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyOffline(retry: () -> Unit) {
    EmptyState(
        title = "Nessun dato",
        subtitle = "Connessione non disponibile al primo avvio. Riprova quando sei online.",
        action = { TextButton(onClick = retry) { Text("Riprova") } },
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
