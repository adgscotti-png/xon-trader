package com.adgent.trader.ui.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.glance.appwidget.updateAll
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adgent.trader.appContainer
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.common.NumberFormatMode
import com.adgent.trader.core.work.WidgetUpdateWorker
import com.adgent.trader.ui.theme.AdgentTraderTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Configurazione di un widget home screen (aperta dal picker o da
 * "Ridimensiona/Configura" dopo un pressione lunga sul widget).
 *
 * Opzioni: strumento fisso o automatico (ticker), numero righe (watchlist),
 * dimensione del testo, formato del numero (con/senza virgole, approssimato),
 * elementi visibili e intervallo di aggiornamento automatico.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Finché non si salva, il piazzamento del widget viene annullato.
        setResult(RESULT_CANCELED)

        setContent {
            AdgentTraderTheme(darkTheme = true) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    WidgetConfigScreen(
                        appWidgetId = appWidgetId,
                        onClose = { ok ->
                            if (ok) {
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                                )
                            }
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(appWidgetId: Int, onClose: (Boolean) -> Unit) {
    val context = LocalContext.current
    var kind by rememberState { mutableStateOf<WidgetKind?>(null) }
    var config by rememberState { mutableStateOf<WidgetConfig?>(null) }
    var symbols by rememberState { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var refreshMinutes by rememberState { mutableIntStateOf(WidgetConfigStore.MIN_REFRESH_MINUTES) }

    LaunchedEffect(appWidgetId) {
        val c = context.applicationContext
        // Rileva il tipo di widget dagli id nativi (AppWidgetManager): gli id
        // coincidono con quelli che Glance passa qui come EXTRA_APPWIDGET_ID.
        val detected: WidgetKind? = runCatching {
            val awm = AppWidgetManager.getInstance(c)
            val tickerIds = awm.getAppWidgetIds(
                ComponentName(c, TickerWidgetReceiver::class.java),
            ) ?: intArrayOf()
            val watchIds = awm.getAppWidgetIds(
                ComponentName(c, WatchlistWidgetReceiver::class.java),
            ) ?: intArrayOf()
            when {
                appWidgetId in tickerIds -> WidgetKind.TICKER
                appWidgetId in watchIds -> WidgetKind.WATCHLIST
                else -> null
            }
        }.getOrNull()

        val loaded = WidgetConfigStore.load(c, detected ?: WidgetKind.TICKER, appWidgetId)
        refreshMinutes = WidgetConfigStore.getRefreshMinutes(c)
        val available = runCatching {
            val container = c.appContainer
            val cachedPrices = container.tickerRepo.observeCached(limit = 300).first()
                .associate { it.symbol to it.price }
            val favs = container.watchlistRepo.all().map { it.symbol }
            // Preferiti anche se non in cache (prezzo 0 finché non arriva un update).
            (favs + cachedPrices.keys).distinct().associateWith { cachedPrices[it] ?: 0.0 }
        }.getOrDefault(emptyMap())

        kind = detected
        config = loaded
        symbols = available.entries.map { it.key to it.value }.sortedBy { it.first }
    }

    val k = kind
    val cfg = config

    if (k == null || cfg == null) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Configure widget",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )

        // ---------- Strumento / righe ----------
        SettingsBlock(
            title = if (k == WidgetKind.TICKER) "Shown instrument" else "Content",
            subtitle = if (k == WidgetKind.TICKER)
                "Pick a fixed pair, or leave Automatic: it follows your favorites."
            else "How many favorite pairs to show in the widget.",
        ) {
            if (k == WidgetKind.WATCHLIST) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 4, 5, 6).forEach { n ->
                        FilterChip(
                            selected = cfg.rows == n,
                            onClick = { config = cfg.copy(rows = n) },
                            label = { Text("$n rows") },
                        )
                    }
                }
            } else {
                SymbolPicker(
                    symbols = symbols,
                    selected = cfg.symbol,
                    onSelect = { config = cfg.copy(symbol = it) },
                )
            }
        }

        // ---------- Dimensione testo ----------
        SettingsBlock(
            title = "Text size",
            subtitle = "Bigger text = essential info at a glance.",
        ) {
            val presets = WidgetConfig.sizesFor(k)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEachIndexed { i, sp ->
                    FilterChip(
                        selected = cfg.textSizeSp == sp,
                        onClick = { config = cfg.copy(textSizeSp = sp) },
                        label = {
                            Text(listOf("Small", "Medium", "Large", "XL", "XXL")[i])
                        },
                    )
                }
            }
        }

        // ---------- Formato numeri ----------
        SettingsBlock(
            title = "Number format",
            subtitle = "Example with price 97,400.12 — pick how much detail you want.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberFormatMode.entries.chunked(2).forEach { pairModes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pairModes.forEach { mode ->
                            FilterChip(
                                selected = cfg.numberFormat == mode,
                                onClick = { config = cfg.copy(numberFormat = mode) },
                                label = {
                                    Text("${mode.label} · ${mode.example}")
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---------- Elementi visibili ----------
        SettingsBlock("Visible elements", "Minimize to show just the price.") {
            ToggleRow(
                label = "24h change",
                description = "Green/red percentage next to the price.",
                checked = cfg.showChange,
                onChecked = { config = cfg.copy(showChange = it) },
            )
            ToggleRow(
                label = "Update time",
                description = "Tells you how old the shown prices are.",
                checked = cfg.showTimestamp,
                onChecked = { config = cfg.copy(showTimestamp = it) },
            )
        }

        // ---------- Aggiornamento automatico ----------
        SettingsBlock(
            title = "Auto refresh",
            subtitle = "With the app closed Android allows at least 15 minutes. With the app open prices are live in real time.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    15 to "15 min", 30 to "30 min", 60 to "1 hour",
                    120 to "2 hours", 360 to "6 hours",
                ).forEach { (min, label) ->
                    FilterChip(
                        selected = refreshMinutes == min,
                        onClick = { refreshMinutes = min },
                        label = { Text(label) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { onClose(false) },
                modifier = Modifier.weight(1f),
            ) { Text("Cancel") }

            Button(
                onClick = {
                    val ctx = context.applicationContext
                    WidgetConfigStore.save(ctx, k, appWidgetId, cfg)
                    WidgetConfigStore.setRefreshMinutes(ctx, refreshMinutes)
                    WidgetUpdateWorker.schedule(ctx, refreshMinutes)
                    // Risultato ALLA LAUNCHER immediato: se arrivasse dopo (o mai,
                    // per uno scope cancellato dal finish) il sistema scarterebbe
                    // il widget piazzato e alla riaggiunta ripartirebbe dai default.
                    onClose(true)
                    // Ridisegno su scope dell'app: sopravvive alla chiusura activity.
                    ctx.appContainer.appScope.launch {
                        runCatching {
                            when (k) {
                                WidgetKind.TICKER -> TickerWidget().updateAll(ctx)
                                WidgetKind.WATCHLIST -> WatchlistWidget().updateAll(ctx)
                            }
                        }
                    }
                },
                enabled = true,
                modifier = Modifier.weight(1f),
            ) { Text("Save and apply") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Preset dimensioni testo per tipo widget. */
private fun WidgetConfig.Companion.sizesFor(kind: WidgetKind): List<Int> =
    when (kind) {
        WidgetKind.TICKER -> TICKER_SIZES
        WidgetKind.WATCHLIST -> LIST_SIZES
    }

/** Picker simbolo: campo ricerca + lista cliccabile + voce Automatico. */
@Composable
private fun SymbolPicker(
    symbols: List<Pair<String, Double>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var query by rememberState { mutableStateOf("") }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = selected.isBlank(),
                onClick = { onSelect(""); query = "" },
                label = { Text("Automatic") },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (selected.isBlank()) "follows your favorites"
                else "fixed: ${selected.removeSuffix("USDT")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(12) },
            placeholder = { Text("Search symbol (e.g. BTC)") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        val results = if (query.isBlank()) emptyList()
        else symbols.filter { it.first.contains(query, ignoreCase = true) }.take(5)
        results.forEach { (sym, _) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(sym); query = "" }
                    .padding(horizontal = 4.dp, vertical = 7.dp),
            ) {
                Text(sym.removeSuffix("USDT"), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    sym,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingsBlock(title: String, subtitle: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Piccolo helper: mutableStateOf delegato senza import multipli ripetuti. */
@Composable
private fun <T> rememberState(init: () -> androidx.compose.runtime.MutableState<T>) =
    androidx.compose.runtime.remember { init() }
