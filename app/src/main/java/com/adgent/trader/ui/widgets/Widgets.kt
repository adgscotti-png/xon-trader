package com.adgent.trader.ui.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import com.adgent.trader.appContainer
import com.adgent.trader.core.common.Format
import kotlinx.coroutines.flow.first

/** Riga dati pronta per il rendering dei widget. */
data class WidgetRow(
    val symbol: String,
    val base: String,
    val price: Double,
    val changePercent24h: Double,
)

/**
 * Carica i preferiti ordinati (fallback top volume) dalla cache Room.
 * Chiamato dal thread del widget durante l'update: nessuna rete qui.
 */
suspend fun loadWidgetRows(context: Context, limit: Int = 6): List<WidgetRow> =
    runCatching {
        val c = context.applicationContext.appContainer
        val favs = c.watchlistRepo.all().map { it.symbol }
        val cached = c.tickerRepo.observeCached(limit = 300).first().associateBy { it.symbol }
        val ordered = (favs + cached.values.sortedByDescending { it.quoteVolume24h }.map { it.symbol })
            .distinct()
            .take(limit)
        ordered.mapNotNull { sym ->
            cached[sym]?.let { t ->
                WidgetRow(sym, sym.removeSuffix("USDT"), t.price, t.changePercent24h)
            }
        }
    }.getOrDefault(emptyList())

// ---------- Colori e stili condivisi ----------

private val BgColor = Color(0xFF0E1118)
private val CardColor = Color(0xFF171B26)
private val TextMain = Color(0xFFF4F5F9)
private val TextDim = Color(0xFF9AA0B5)
private val GreenUp = Color(0xFF20B65A)
private val RedDown = Color(0xFFE5484D)

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

/** Deep link al dettaglio coin dalla home screen. */
private fun openCoinAction(symbol: String) = actionStartActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse("adgent://coin/$symbol")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    },
)

// ---------- Widget ticker 2×1 ----------

/** Prezzo grande del primo strumento in lista (preferiti → top volume). */
class TickerWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = loadWidgetRows(context, limit = 1)
        provideContent {
            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(BgColor)
                        .cornerRadius(14.dp)
                        .padding(10.dp),
                ) {
                    val r = rows.firstOrNull()
                    if (r == null) {
                        Text(
                            "Apri ADGENT Trader per caricare i dati",
                            style = dimStyle(11),
                        )
                    } else {
                        Row(
                            modifier = GlanceModifier.fillMaxSize().clickable(openCoinAction(r.symbol)),
                            verticalAlignment = Alignment.Vertical.CenterVertically,
                        ) {
                            Column {
                                Text(r.base, style = TextStyle(color = ColorProvider(TextDim), fontSize = 11.sp))
                                Spacer(GlanceModifier.height(2.dp))
                                Text("$" + Format.price(r.price), style = priceStyle(17))
                            }
                            Spacer(GlanceModifier.defaultWeight())
                            Text(
                                Format.percent(r.changePercent24h),
                                style = TextStyle(color = changeColor(r.changePercent24h), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }
}

class TickerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TickerWidget()
}

// ---------- Widget watchlist 4×2 ----------

/** Lista preferiti (fino a 5 righe) con prezzo e variazione 24h live-cache. */
class WatchlistWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rows = loadWidgetRows(context, limit = 5)
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(BgColor)
                        .cornerRadius(14.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (rows.isEmpty()) {
                        Text("Apri ADGENT Trader per caricare i dati", style = dimStyle(11))
                    } else {
                        rows.forEachIndexed { i, r ->
                            if (i > 0) Spacer(GlanceModifier.height(4.dp))
                            Row(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .background(ColorProvider(CardColor))
                                    .cornerRadius(10.dp)
                                    .clickable(openCoinAction(r.symbol))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.Vertical.CenterVertically,
                            ) {
                                Text(r.base, style = TextStyle(color = ColorProvider(TextMain), fontSize = 12.sp, fontWeight = FontWeight.Medium))
                                Spacer(GlanceModifier.width(8.dp))
                                Text("$" + Format.price(r.price), style = TextStyle(color = ColorProvider(TextDim), fontSize = 11.sp))
                                Spacer(GlanceModifier.defaultWeight())
                                Text(
                                    Format.percent(r.changePercent24h),
                                    style = TextStyle(color = changeColor(r.changePercent24h), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                )
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
}
