package com.adgent.trader.ui.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.adgent.trader.R
import com.adgent.trader.appContainer
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.common.NumberFormatMode
import com.adgent.trader.core.provider.ProviderId
import com.adgent.trader.data.AppStyle
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ora breve "14:32" per il footer dei widget. */
private fun timeLabel(epochMs: Long): String =
    if (epochMs <= 0) ""
    else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

/** Riga dati pronta per il rendering dei widget. */
data class WidgetRow(
    val symbol: String,
    val base: String,
    val price: Double,
    val changePercent24h: Double,
    val provider: ProviderId,
)

/**
 * Carica i preferiti ordinati (fallback top volume) dalla cache Room.
 * Chiamato dal thread del widget durante l'update: nessuna rete qui.
 */
suspend fun loadWidgetRows(context: Context, limit: Int = 6): List<WidgetRow> =
    runCatching {
        val c = context.applicationContext.appContainer
        val favs = c.watchlistRepo.all().map { "${it.provider}:${it.symbol}" }
        val cached = c.marketDataRepo.observeCached(provider = null, limit = 300).first()
            .associateBy { "${it.provider}:${it.symbol}" }
        val ordered = (favs + cached.values
            .sortedByDescending { it.quoteVolume24h }
            .map { "${it.provider}:${it.symbol}" })
            .distinct()
            .take(limit)
        ordered.mapNotNull { key ->
            cached[key]?.let { t ->
                val provider = ProviderId.fromName(t.provider) ?: ProviderId.BINANCE
                WidgetRow(
                    symbol = t.symbol,
                    base = c.marketDataRepo.fromCompact(t.symbol)?.base ?: t.symbol.removeSuffix("USDT"),
                    price = t.price,
                    changePercent24h = t.changePercent24h,
                    provider = provider,
                )
            }
        }
    }.getOrDefault(emptyList())

/**
 * Coppie (provider, symbol) impostate manualmente nei widget ticker: vanno
 * incluse nel refresh del worker, altrimenti un simbolo fuori dalla watchlist
 * resterebbe fermo.
 */
suspend fun configuredTickerPairs(context: Context): List<Pair<String, String>> =
    runCatching {
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(ComponentName(context, TickerWidgetReceiver::class.java))
        ids.toList().mapNotNull { id ->
            val cfg = WidgetConfigStore.load(context, WidgetKind.TICKER, id)
            cfg.symbol.takeIf { it.isNotBlank() }?.let { cfg.provider to it }
        }
    }.getOrDefault(emptyList())

/** Solo simboli configurati nei widget ticker (per diagnostica e compatibilità). */
suspend fun configuredTickerSymbols(context: Context): List<String> =
    configuredTickerPairs(context).map { it.second }

// ---------- Colori e stili condivisi ----------

private val BgColor = Color(0xFF0E1118)
private val CardColor = Color(0xFF171B26)
private val TextMain = Color(0xFFF4F5F9)
private val TextDim = Color(0xFF9AA0B5)
private val GreenUp = Color(0xFF20B65A)
private val RedDown = Color(0xFFE5484D)

/**
 * Palette del widget per lo stile scelto. border != null aggiunge un anello
 * ciano 1dp (Glance non supporta blur/box-shadow: niente glow vero nei widget).
 */
private data class WidgetPalette(
    val bg: Color,
    val card: Color,
    val border: Color? = null,
)

private fun paletteFor(style: AppStyle): WidgetPalette = when (style) {
    AppStyle.CLASSIC -> WidgetPalette(bg = BgColor, card = CardColor)
    AppStyle.NEON -> WidgetPalette(
        bg = Color(0xFF070F1E),
        card = Color(0xFF0D1A33),
        border = Color(0xFF4FA3E8),
    )
}

/**
 * Contenitore esterno widget. In Neon l'anello ciano 1dp NON ha cornerRadius
 * proprio: il launcher (Android 12+) arrotonda il widget con una maschera più
 * ampia, quindi un bordo disegnato all'angolo verrebbe ritagliato via. Senza
 * angolo nostro il contorno segue la stondatura di sistema e resta visibile
 * anche agli angoli. In Classic resta il navy arrotondato di sempre.
 */
private fun outerShell(pal: WidgetPalette): GlanceModifier {
    val m = GlanceModifier.fillMaxSize().background(ColorProvider(pal.border ?: pal.bg))
    return if (pal.border != null) m.padding(all = 1.dp) else m.cornerRadius(14.dp)
}

/** Box interno: sfondo + padding del contenuto, con l'angolo solo in Classic. */
private fun innerShell(pal: WidgetPalette, horizontal: Dp, vertical: Dp): GlanceModifier {
    var m = GlanceModifier.fillMaxSize().background(ColorProvider(pal.bg))
    if (pal.border == null) m = m.cornerRadius(14.dp)
    return m.padding(horizontal = horizontal, vertical = vertical)
}

@Composable
private fun priceStyle(size: Int) = TextStyle(
    color = ColorProvider(TextMain),
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
)

@Composable
private fun dimStyle(size: Int) = TextStyle(color = ColorProvider(TextDim), fontSize = size.sp)

@Composable
private fun changeColor(percent: Double) = ColorProvider(if (percent >= 0) GreenUp else RedDown)

/** Deep link al dettaglio coin dalla home screen (con provider per il corretto grafico). */
private fun openCoinAction(symbol: String, provider: ProviderId) = actionStartActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse("adgent://coin/$symbol?provider=${provider.name}")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    },
)

/** Pulsante refresh immediato: scarica prezzi freschi e ridisegna i widget. */
@Composable
private fun RefreshButton() {
    Image(
        provider = ImageProvider(R.drawable.ic_widget_refresh),
        contentDescription = "Refresh now",
        modifier = GlanceModifier
            .width(22.dp)
            .height(22.dp)
            .clickable(actionRunCallback<WidgetRefreshAction>()),
    )
}

// ---------- Widget ticker 2×1 ----------

/**
 * Prezzo grande di uno strumento: automatico (preferiti → volume) oppure
 * fisso, con dimensione testo, formato numero e ora aggiornamento configurabili.
 */
class TickerWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val cfg = WidgetConfigStore.load(context, WidgetKind.TICKER, appWidgetId)
        val provider = ProviderId.fromName(cfg.provider) ?: ProviderId.BINANCE
        val rows = loadWidgetRows(context, limit = 30)
        // Lo strumento configurato vince SEMPRE: cache → download immediato →
        // segnaposto. Mai un'altra moneta al posto di quella scelta.
        val row = cfg.symbol.takeIf { it.isNotBlank() }
            ?.let { sym ->
                rows.firstOrNull { it.symbol == sym && it.provider == provider }
                    ?: fetchRowFromCache(context, provider, sym)
                    ?: fetchRowLive(context, provider, sym)
                    ?: WidgetRow(sym, sym.removeSuffix("USDT"), 0.0, 0.0, provider)
            }
            ?: rows.firstOrNull()
        val lastUpdate = WidgetConfigStore.lastUpdate(context)
        val timeLabel = timeLabel(lastUpdate)
        val pal = paletteFor(context.applicationContext.appContainer.settingsRepo.settings.first().appStyle)

        provideContent {
            GlanceTheme {
                Box(modifier = outerShell(pal)) {
                    Box(modifier = innerShell(pal, horizontal = 10.dp, vertical = 10.dp)) {
                    if (row == null) {
                        Text(
                            "Open XON Trader to load data",
                            style = dimStyle(11),
                        )
                    } else {
                        Row(
                            modifier = GlanceModifier.fillMaxSize(),
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                        ) {
                            Column(
                                modifier = GlanceModifier.defaultWeight()
                                    .clickable(openCoinAction(row.symbol, row.provider)),
                            ) {
                                Text(
                                    row.base,
                                    style = TextStyle(color = ColorProvider(TextDim), fontSize = 11.sp),
                                )
                                Spacer(GlanceModifier.height(2.dp))
                                Text(
                                    "$" + Format.price(row.price, cfg.numberFormat),
                                    style = priceStyle(cfg.textSizeSp),
                                    maxLines = 1,
                                )
                                Spacer(GlanceModifier.height(2.dp))
                                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                                    if (cfg.showChange) {
                                        Text(
                                            Format.percent(row.changePercent24h),
                                            style = TextStyle(
                                                color = changeColor(row.changePercent24h),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                        )
                                        if (cfg.showTimestamp && timeLabel.isNotBlank()) {
                                            Spacer(GlanceModifier.width(6.dp))
                                            Text("· $timeLabel", style = dimStyle(10))
                                        }
                                    } else if (cfg.showTimestamp && timeLabel.isNotBlank()) {
                                        Text("upd. $timeLabel", style = dimStyle(10))
                                    }
                                }
                            }
                            Spacer(GlanceModifier.width(8.dp))
                            Box(
                                modifier = GlanceModifier.background(ColorProvider(pal.card)).cornerRadius(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                RefreshButton()
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

class TickerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TickerWidget()

    /** Widget rimosso: la sua configurazione non serve più. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetConfigStore.delete(context, WidgetKind.TICKER, it) }
    }
}

/** Prezzo di un singolo simbolo direttamente dalla cache (per simboli configurati). */
private suspend fun fetchRowFromCache(context: Context, provider: ProviderId, symbol: String): WidgetRow? =
    runCatching {
        val c = context.applicationContext.appContainer
        c.marketDataRepo.observeCached(provider = provider, limit = 500).first()
            .firstOrNull { it.symbol == symbol }
            ?.let {
                WidgetRow(
                    symbol = it.symbol,
                    base = c.marketDataRepo.fromCompact(it.symbol)?.base ?: it.symbol.removeSuffix("USDT"),
                    price = it.price,
                    changePercent24h = it.changePercent24h,
                    provider = provider,
                )
            }
    }.getOrNull()

/**
 * Scarica subito il prezzo del simbolo configurato quando non è in cache
 * (es. fuori dalla watchlist): il widget non deve mai mostrare un altro asset.
 */
private suspend fun fetchRowLive(context: Context, provider: ProviderId, symbol: String): WidgetRow? =
    runCatching {
        val container = context.applicationContext.appContainer
        container.marketDataRepo.refreshTickers(provider, listOf(symbol), force = true).getOrThrow()
        container.marketDataRepo.observeCached(provider = provider, limit = 500).first()
            .firstOrNull { it.symbol == symbol }
            ?.let {
                WidgetRow(
                    symbol = it.symbol,
                    base = container.marketDataRepo.fromCompact(it.symbol)?.base ?: it.symbol.removeSuffix("USDT"),
                    price = it.price,
                    changePercent24h = it.changePercent24h,
                    provider = provider,
                )
            }
    }.getOrNull()

// ---------- Widget watchlist 4×2 ----------

/** Lista preferiti (numero righe configurabile) con prezzo e variazione 24h. */
class WatchlistWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val cfg = WidgetConfigStore.load(context, WidgetKind.WATCHLIST, appWidgetId)
        val rows = loadWidgetRows(context, limit = cfg.rows.coerceIn(1, 8))
        val lastUpdate = WidgetConfigStore.lastUpdate(context)
        val timeLabel = timeLabel(lastUpdate)
        val pal = paletteFor(context.applicationContext.appContainer.settingsRepo.settings.first().appStyle)

        provideContent {
            GlanceTheme {
                Box(modifier = outerShell(pal)) {
                    Box(modifier = innerShell(pal, horizontal = 12.dp, vertical = 8.dp)) {
                    Column(
                        modifier = GlanceModifier.fillMaxSize(),
                    ) {
                    // Header: titolo + refresh immediato.
                    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Text(
                            "Favorites",
                            style = TextStyle(color = ColorProvider(TextDim), fontSize = 10.sp),
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        RefreshButton()
                    }
                    Spacer(GlanceModifier.height(4.dp))

                    if (rows.isEmpty()) {
                        Text("Open XON Trader to load data", style = dimStyle(11))
                    } else {
                        // Column annidata: Glance limita a 10 figli per contenitore,
                        // con le righe qui dentro non si troncano mai.
                        Column {
                            rows.forEach { r ->
                                Row(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .background(ColorProvider(pal.card))
                                        .cornerRadius(10.dp)
                                        .clickable(openCoinAction(r.symbol, r.provider))
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.Vertical.CenterVertically,
                                ) {
                                    Text(
                                        r.base,
                                        style = TextStyle(
                                            color = ColorProvider(TextMain),
                                            fontSize = cfg.textSizeSp.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        maxLines = 1,
                                    )
                                    Spacer(GlanceModifier.width(8.dp))
                                    Text(
                                        "$" + Format.price(r.price, cfg.numberFormat),
                                        style = TextStyle(color = ColorProvider(TextDim), fontSize = cfg.textSizeSp.sp),
                                        maxLines = 1,
                                    )
                                    Spacer(GlanceModifier.defaultWeight())
                                    if (cfg.showChange) {
                                        Text(
                                            Format.percent(r.changePercent24h),
                                            style = TextStyle(
                                                color = changeColor(r.changePercent24h),
                                                fontSize = cfg.textSizeSp.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (cfg.showTimestamp && timeLabel.isNotBlank()) {
                        Spacer(GlanceModifier.height(3.dp))
                        Text("Updated $timeLabel", style = dimStyle(9))
                    }
                }
                    }
                }
            }
        }
    }
}

class WatchlistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WatchlistWidget()

    /** Widget rimosso: la sua configurazione non serve più. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetConfigStore.delete(context, WidgetKind.WATCHLIST, it) }
    }
}

// ---------- Azioni ----------

/**
 * Refresh immediato scatenato dal pulsantino ↻ del widget: scarica i prezzi
 * (forza, bypass TTL), aggiorna il timestamp visibile e ridisegna. Debounce 2s
 * anti-tap-rapidi: un tap entro la finestra dall'ultimo non rifà il fetch.
 */
class WidgetRefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - WidgetConfigStore.lastManualRefresh(ctx) < MANUAL_REFRESH_DEBOUNCE_MS) return
        WidgetConfigStore.stampManualRefresh(ctx)
        runCatching {
            val container = ctx.appContainer
            val pairs = container.watchlistRepo.all().map { it.provider to it.symbol } +
                configuredTickerPairs(ctx)
            val defaults = if (pairs.isEmpty())
                listOf("BINANCE" to "BTCUSDT", "BINANCE" to "ETHUSDT", "BINANCE" to "SOLUSDT")
            else pairs
            defaults.groupBy { it.first }.forEach { (providerName, list) ->
                val provider = ProviderId.fromName(providerName) ?: return@forEach
                container.marketDataRepo.refreshTickers(provider, list.map { it.second }, force = true)
            }
        }
        WidgetConfigStore.stampUpdate(ctx)
        runCatching { TickerWidget().updateAll(ctx) }
        runCatching { WatchlistWidget().updateAll(ctx) }
    }

    companion object {
        private const val MANUAL_REFRESH_DEBOUNCE_MS = 2_000L
    }
}
