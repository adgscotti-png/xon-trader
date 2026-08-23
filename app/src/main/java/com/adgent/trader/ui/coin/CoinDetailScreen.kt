package com.adgent.trader.ui.coin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AddAlert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.model.Timeframe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import com.adgent.trader.ui.appViewModel
import com.adgent.trader.ui.chart.CandleChart
import com.adgent.trader.ui.chart.ChartMode
import com.adgent.trader.ui.chart.OscKind
import com.adgent.trader.ui.chart.OscillatorPanel
import com.adgent.trader.ui.components.CoinBadge
import com.adgent.trader.ui.components.ChangeBadge
import com.adgent.trader.ui.theme.BrandGradient
import com.adgent.trader.ui.theme.MarketGreen
import com.adgent.trader.ui.theme.MarketRed

/**
 * Dettaglio coin (F2): prezzo live, grafico interattivo con timeframe,
 * statistiche 24h e accesso rapido alla creazione di un avviso.
 */
@Composable
fun CoinDetailScreen(
    symbol: String,
    onClose: () -> Unit,
    onCreateAlert: () -> Unit,
    vm: CoinDetailViewModel = appViewModel(key = "coin-$symbol") { CoinDetailViewModel(it, symbol) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifState = com.adgent.trader.ui.notifications.rememberNotifPermissionState()
    var favorite by remember { mutableStateOf(false) }
    LaunchedEffect(symbol) { favorite = vm.isFavorite() }

    // Toast one-shot quando un avviso viene creato direttamente dal grafico.
    LaunchedEffect(state.quickAlertMsg) {
        state.quickAlertMsg?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            vm.clearQuickAlertMsg()
        }
    }

    val base = symbol.removeSuffix("USDT")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---------- Barra superiore ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to markets",
                )
            }
            CoinBadge(base = base, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(base, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "$base/USDT · Binance",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val priceText = state.livePrice?.let { Format.price(it) }
                        ?: state.klines.lastOrNull()?.close?.let { Format.price(it) }
                        ?: "—"
                    Text(
                        text = if (priceText == "—") priceText else "$" + priceText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                    state.changePercent24h?.let {
                        Spacer(Modifier.width(6.dp))
                        ChangeBadge(percent = it)
                    }
                }
                Spacer(Modifier.height(2.dp))
                LiveTimeText()
            }
            IconButton(onClick = {
                favorite = !favorite
                vm.toggleFavorite()
            }) {
                Icon(
                    imageVector = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (favorite)
                        androidx.compose.ui.graphics.Color(0xFFF5B301)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------- Statistiche 24h (sopra il grafico) ----------
        StatsCard(state)

        // ---------- Grafico + controlli ----------
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                TimeframeSelector(
                    selected = state.timeframe,
                    onSelect = vm::setTimeframe,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                ) {
                    when {
                        state.loading && state.klines.isEmpty() ->
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.Center),
                            )

                        state.klines.isEmpty() -> ChartEmpty(onRetry = vm::retry)

                        else -> CandleChart(
                            klines = state.klines,
                            mode = state.chartMode,
                            showMa = state.showMa,
                            showEma = state.showEma,
                            showBb = state.showBb,
                            livePrice = state.livePrice,
                            onCreateAlertAtPrice = { price, above ->
                                vm.quickAlert(price, above)
                                // Senza permesso notifiche l'avviso non suonerebbe mai.
                                if (!notifState.granted) notifState.ensure()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                ChartControls(
                    mode = state.chartMode,
                    onMode = vm::setChartMode,
                    showMa = state.showMa,
                    showEma = state.showEma,
                    showBb = state.showBb,
                    onToggle = vm::toggleOverlay,
                )

                // ---------- Sub-chart oscillatore (RSI/MACD) ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OscKind.entries.forEach { k ->
                        val active = state.oscillator == k
                        Text(
                            k.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .clickable { vm.setOscillator(k) },
                        )
                    }
                }
                state.oscillator?.let { osc ->
                    if (state.klines.isNotEmpty()) {
                        OscillatorPanel(
                            klines = state.klines,
                            kind = osc,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        // ---------- Azioni ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onCreateAlert,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.AddAlert, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Create alert")
            }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text("Close")
            }
        }
        state.offline.let {
            if (it) {
                Text(
                    "Chart offline: showing cached data.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TimeframeSelector(selected: Timeframe, onSelect: (Timeframe) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        items(Timeframe.entries) { tf ->
            FilterChip(
                selected = tf == selected,
                onClick = { onSelect(tf) },
                label = { Text(tf.label) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun ChartControls(
    mode: ChartMode,
    onMode: (ChartMode) -> Unit,
    showMa: Boolean,
    showEma: Boolean,
    showBb: Boolean,
    onToggle: (CoinDetailViewModel.OverlayKind) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChartMode.entries.forEach { m ->
            TextButton(onClick = { onMode(m) }) {
                Text(
                    m.label,
                    fontWeight = if (m == mode) FontWeight.Bold else FontWeight.Normal,
                    color = if (m == mode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        listOf(
            Triple("MA", showMa, CoinDetailViewModel.OverlayKind.MA),
            Triple("EMA", showEma, CoinDetailViewModel.OverlayKind.EMA),
            Triple("BB", showBb, CoinDetailViewModel.OverlayKind.BB),
        ).forEach { (label, active, kind) ->
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable { onToggle(kind) },
            )
        }
    }
}

/** Orologio live (solo HH:mm:ss) nella barra superiore accanto al prezzo. */
@Composable
private fun LiveTimeText() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(now).atZone(zone)
    Text(
        text = t.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Card unica "24h statistics": barra range High/Low con marker del prezzo
 * corrente + volume 24h. Tutti i dati arrivano già dal tick live/cache.
 */
@Composable
private fun StatsCard(state: CoinDetailUiState) {
    val high = state.high24h
    val low = state.low24h
    if (high == null || low == null) return
    val price = state.livePrice ?: state.klines.lastOrNull()?.close
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "24h statistics",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "High  " + Format.price(high),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MarketGreen,
                )
                if (price != null) {
                    Spacer(Modifier.width(10.dp))
                    RangeBar(
                        low = low,
                        high = high,
                        price = price,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    "Low  " + Format.price(low),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MarketRed,
                )
            }
            state.quoteVolume24h?.let { vol ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "24h volume",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        Format.compact(vol),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Barra orizzontale High-Low: High a sinistra, Low a destra. Il riempimento
 * brand parte dal lato High e il marker bianco segna la distanza del prezzo
 * corrente dal massimo 24h.
 */
@Composable
private fun RangeBar(low: Double, high: Double, price: Double, modifier: Modifier = Modifier) {
    val frac = if (high > low) ((price - low) / (high - low)).toFloat().coerceIn(0f, 1f) else 0.5f
    // High a sinistra: la distanza dal valore più alto è (1 - frac).
    val fromHigh = 1f - frac
    BoxWithConstraints(modifier = modifier) {
        val trackWidth = maxWidth - 10.dp
        val marker = 10.dp
        Box(
            Modifier
                .width(trackWidth + marker)
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .align(Alignment.CenterStart),
        )
        Box(
            Modifier
                .width(trackWidth * fromHigh + marker)
                .height(6.dp)
                .background(Brush.horizontalGradient(BrandGradient), CircleShape)
                .align(Alignment.CenterStart),
        )
        Box(
            Modifier
                .offset(x = trackWidth * fromHigh)
                .size(marker)
                .background(androidx.compose.ui.graphics.Color.White, CircleShape)
                .align(Alignment.CenterStart),
        )
    }
}

@Composable
private fun ChartEmpty(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No data available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}
