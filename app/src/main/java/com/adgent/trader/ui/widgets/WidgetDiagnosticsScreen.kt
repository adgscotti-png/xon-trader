package com.adgent.trader.ui.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.adgent.trader.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Una riga della diagnostica: un widget reale presente sulla home screen. */
data class WidgetDiagEntry(
    val id: Int,
    val kind: WidgetKind,
    val cfg: WidgetConfig,
    val saved: Boolean,
)

/**
 * Diagnostica widget: elenca i widget presenti con la loro configurazione
 * effettiva salvata, così si vede subito se due widget condividono o no le
 * impostazioni — e perché possono mostrare la stessa moneta.
 */
@Composable
fun WidgetDiagnosticsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<WidgetDiagEntry>>(emptyList()) }
    var rawKeys by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var firstFav by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var redrawing by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val c = context.applicationContext
            val awm = AppWidgetManager.getInstance(c)
            val tickerIds = runCatching {
                awm.getAppWidgetIds(ComponentName(c, TickerWidgetReceiver::class.java))
            }.getOrDefault(intArrayOf())
            val watchIds = runCatching {
                awm.getAppWidgetIds(ComponentName(c, WatchlistWidgetReceiver::class.java))
            }.getOrDefault(intArrayOf())
            val list = buildList {
                tickerIds.forEach { id ->
                    val cfg = WidgetConfigStore.load(c, WidgetKind.TICKER, id)
                    add(
                        WidgetDiagEntry(
                            id, WidgetKind.TICKER, cfg,
                            WidgetConfigStore.isConfigured(c, WidgetKind.TICKER, id),
                        ),
                    )
                }
                watchIds.forEach { id ->
                    val cfg = WidgetConfigStore.load(c, WidgetKind.WATCHLIST, id)
                    add(
                        WidgetDiagEntry(
                            id, WidgetKind.WATCHLIST, cfg,
                            WidgetConfigStore.isConfigured(c, WidgetKind.WATCHLIST, id),
                        ),
                    )
                }
            }.sortedBy { it.id }
            entries = list
            rawKeys = WidgetConfigStore.snapshot(c).toList().sortedBy { it.first }
            firstFav = runCatching { c.appContainer.watchlistRepo.all().firstOrNull()?.symbol }
                .getOrNull() ?: "—"
            loaded = true
        }
    }

    fun redrawAll() {
        redrawing = true
        scope.launch {
            val c = context.applicationContext
            runCatching { com.adgent.trader.core.work.WidgetUpdateWorker.enqueueNow(c) }
            runCatching { TickerWidget().updateAll(c) }
            runCatching { WatchlistWidget().updateAll(c) }
            redrawing = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Widget status",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
        }

        Text(
            "Each widget has its own saved settings (coin, size, format). If two " +
                "widgets show the same coin, check that they are not both " +
                "\"Automatic\". This panel shows exactly what is stored.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            OutlinedButton(onClick = { reload() }) { Text("Reload") }
            Button(onClick = { redrawAll() }, enabled = !redrawing) {
                Text(if (redrawing) "Redrawing…" else "Redraw all widgets")
            }
        }
        Spacer(Modifier.height(10.dp))

        if (!loaded) {
            Text(
                "Reading widget list…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else if (entries.isEmpty()) {
            DiagBlock("No widgets on the home screen") {
                Text(
                    "Add a widget from the launcher (long-press home → Widgets → " +
                        "XON Trader). After configuring it, it appears here with " +
                        "its own settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            entries.forEach { e ->
                WidgetDiagCard(e, firstFav)
            }
        }

        Spacer(Modifier.height(12.dp))
        DiagBlock("Stored settings (raw)") {
            if (rawKeys.isEmpty()) {
                Text(
                    "Nothing stored yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                rawKeys.forEach { (k, v) ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            k,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            v,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun WidgetDiagCard(e: WidgetDiagEntry, firstFav: String?) {
    DiagBlock(title = "Widget #${e.id} · ${if (e.kind == WidgetKind.TICKER) "Ticker" else "Watchlist"}") {
        when (e.kind) {
            WidgetKind.TICKER -> {
                val automatic = e.cfg.symbol.isBlank()
                Row {
                    Text(
                        if (automatic) "Automatic" else "Fixed: ${e.cfg.symbol}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (automatic) {
                    Text(
                        "Shows the first favorite now: $firstFav (or top volume). " +
                            "If it looks wrong, pick a fixed coin.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            WidgetKind.WATCHLIST -> {
                Text(
                    "${e.cfg.rows} rows · first favorite: $firstFav",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Text ${e.cfg.textSizeSp}sp · format ${e.cfg.numberFormat.label} · " +
                "change ${if (e.cfg.showChange) "on" else "off"} · time " +
                "${if (e.cfg.showTimestamp) "on" else "off"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (e.saved) {
            Text(
                "Saved config: yes — settings are isolated per widget.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Text(
                "⚠ No saved config: the widget is on default settings. " +
                    "If you configured it, open it again and tap \"Save and apply\".",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DiagBlock(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
