package com.adgent.trader.ui.favorites

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adgent.trader.R
import com.adgent.trader.appContainer
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.data.FavoritesStyle
import com.adgent.trader.data.MarketRow
import com.adgent.trader.ui.appViewModel
import com.adgent.trader.ui.components.ChangeBadge
import com.adgent.trader.ui.components.CoinBadge
import com.adgent.trader.ui.components.PriceText
import com.adgent.trader.ui.theme.NeonAccent
import com.adgent.trader.ui.theme.NeonBorder
import com.adgent.trader.ui.theme.NeonDim
import com.adgent.trader.ui.theme.NeonGlow
import com.adgent.trader.ui.theme.NeonSurfaceDark
import com.adgent.trader.ui.theme.NeonSurfaceLow
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

// ---------- Palette Retro 8-bit (dalla preview validata) ----------

private val RetroFont = FontFamily(Font(R.font.press_start_2p))

private val RetroBg = Color(0xFF050505)
private val RetroCard = Color(0xFF0B0B0B)
private val RetroBorder = Color(0xFF1F1F1F)
private val RetroTileBg = Color(0xFF111111)
private val RetroCyan = Color(0xFF00E5FF)
private val RetroMagenta = Color(0xFFFF2E88)
private val RetroGreen = Color(0xFF33FF66)
private val RetroRed = Color(0xFFFF2E55)
private val RetroDim = Color(0xFF6F7686)
private val RetroYellow = Color(0xFFFFE600)
private val RetroNoiseColors = listOf(Color.White, RetroMagenta, RetroCyan, RetroGreen, RetroYellow)

/**
 * Pagina Preferiti: la watchlist in una lista a riga singola, con la grafica
 * scelta in Settings → Appearance → Favorites style (Classic, Neon, Retro
 * 8-bit, Split-flap). L'intera pagina cambia con lo stile: header, card,
 * prezzo e badge. Le animazioni girano solo a schermo attivo → costo batteria zero.
 */
@Composable
fun FavoritesScreen(
    onOpenCoin: (String, String) -> Unit,
    vm: FavoritesViewModel = appViewModel { FavoritesViewModel(it) },
) {
    val context = LocalContext.current
    val settings by context.appContainer.settingsRepo.settings
        .collectAsStateWithLifecycle(initialValue = null)
    val style = settings?.favoritesStyle ?: FavoritesStyle.CLASSIC

    val state by vm.state.collectAsStateWithLifecycle()
    // Hot map dei tick live: la riga che cambia si aggiorna subito, le altre no.
    val liveTicks by context.appContainer.priceFeedHub.liveTicks.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            vm.consumeMessage()
        }
    }

    val bg = when (style) {
        FavoritesStyle.CLASSIC -> MaterialTheme.colorScheme.background
        FavoritesStyle.NEON -> NeonSurfaceDark
        FavoritesStyle.RETRO -> RetroBg
        FavoritesStyle.SPLITFLAP -> FlapBoard
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(bg),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            FavoritesHeader(style = style, liveSources = state.liveSources, onRefresh = vm::refresh)
            message?.let { FavoritesBanner(style, it) }
            when {
                state.loading && state.rows.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent(style))
                    }
                }
                state.rows.isEmpty() -> EmptyFavorites(style)
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // La stessa coppia può esistere su più exchange: chiave composta.
                    items(state.rows, key = { "${it.provider.name}:${it.symbol}" }) { row ->
                        // Callback e tick stabiliti per riga: quando cambia il prezzo
                        // live di un'altra riga, questa non viene ricomposta.
                        val onOpen = remember(row) { { onOpenCoin(row.symbol, row.provider.name) } }
                        val onToggle = remember(row) { { vm.toggleFavorite(row) } }
                        FavoriteRow(
                            style = style,
                            row = row,
                            live = liveTicks["${row.provider.name}:${row.symbol}"],
                            onOpenCoin = onOpen,
                            onToggleFavorite = onToggle,
                        )
                    }
                }
            }
        }
        if (style == FavoritesStyle.RETRO) RetroScanlines(Modifier.matchParentSize())
    }
}

@Composable
private fun accent(style: FavoritesStyle): Color = when (style) {
    FavoritesStyle.RETRO -> RetroCyan
    FavoritesStyle.SPLITFLAP -> FlapAmber
    else -> MaterialTheme.colorScheme.primary
}

// ---------- Header ----------

@Composable
private fun FavoritesHeader(
    style: FavoritesStyle,
    liveSources: List<String>,
    onRefresh: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                when (style) {
                    FavoritesStyle.RETRO -> Text("FAVORITES", fontFamily = RetroFont, fontSize = 12.sp, color = RetroCyan)
                    FavoritesStyle.SPLITFLAP -> Text(
                        "FAVORITES", fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        color = FlapAmber, letterSpacing = 3.sp,
                    )
                    else -> Text(
                        "Favorites", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                    )
                }
                Spacer(Modifier.height(3.dp))
                LiveBadge(style, liveSources)
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh prices now",
                    tint = accent(style),
                )
            }
        }
        if (style == FavoritesStyle.RETRO) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(RetroMagenta))
        }
    }
}

@Composable
private fun LiveBadge(style: FavoritesStyle, sources: List<String>) {
    when (style) {
        FavoritesStyle.RETRO -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(RetroGreen))
            Spacer(Modifier.width(6.dp))
            Text("LIVE", fontFamily = RetroFont, fontSize = 8.sp, color = RetroGreen)
        }
        FavoritesStyle.SPLITFLAP -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(FlapAmber))
            Spacer(Modifier.width(6.dp))
            Text("LIVE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = FlapAmber, letterSpacing = 2.sp)
        }
        else -> {
            val accent = if (style == FavoritesStyle.NEON) NeonAccent else MaterialTheme.colorScheme.primary
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
                Box(Modifier.size(7.dp).alpha(alpha).background(accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- Righe ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    style: FavoritesStyle,
    row: MarketRow,
    live: PriceTick?,
    onOpenCoin: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    // Il tick live della hot map (quando presente) sovrascrive prezzo/stats della
    // cache: la riga mostra SEMPRE il valore più fresco, senza aspettare il flush.
    val display = if (live != null) row.copy(
        price = live.price,
        changePercent24h = live.changePercent24h,
        high24h = live.high24h,
        low24h = live.low24h,
        quoteVolume24h = live.quoteVolume24h,
    ) else row
    val click = Modifier.combinedClickable(
        onClick = onOpenCoin,
        onLongClick = onToggleFavorite,
    )
    when (style) {
        FavoritesStyle.CLASSIC -> ClassicRow(display, click)
        FavoritesStyle.NEON -> NeonRow(display, click)
        FavoritesStyle.RETRO -> RetroRow(display, click)
        FavoritesStyle.SPLITFLAP -> FlapRow(display, click)
    }
}

/** Riga corrente (flat AMOLED): badge + nome + prezzo + badge variazione. */
@Composable
private fun ClassicRow(row: MarketRow, click: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().then(click),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoinBadge(base = row.base, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(row.base, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${row.symbol} · ${row.provider.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                PriceText(price = row.price, fontSize = 17.sp)
                Spacer(Modifier.height(2.dp))
                ChangeBadge(percent = row.changePercent24h, compact = true)
            }
        }
    }
}

/** Riga Neon: navy + anello ciano + alone, come la card già in app. */
@Composable
private fun NeonRow(row: MarketRow, click: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = NeonSurfaceLow,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .shadowNeon(shape)
            .then(click),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoinBadge(base = row.base, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(row.base, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${row.symbol} · ${row.provider.label}", style = MaterialTheme.typography.labelSmall, color = NeonDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                PriceText(price = row.price, fontSize = 17.sp, color = Color.White)
                Spacer(Modifier.height(2.dp))
                ChangeBadge(percent = row.changePercent24h, compact = true)
            }
        }
    }
}

private fun Modifier.shadowNeon(shape: RoundedCornerShape): Modifier = this
    .shadow(
        elevation = 8.dp, shape = shape, clip = false,
        ambientColor = NeonGlow, spotColor = NeonGlow,
    )
    .border(1.dp, NeonBorder, shape)

/** Riga Retro 8-bit: pixel font, bordi a bevel, static analogica sul prezzo. */
@Composable
private fun RetroRow(row: MarketRow, click: Modifier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RetroCard)
            .border(2.dp, RetroBorder)
            .retroBevel()
            .then(click)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RetroTile(row)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(row.base, fontFamily = RetroFont, fontSize = 9.sp, color = RetroMagenta, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text("${row.symbol} · ${row.provider.label}", fontFamily = RetroFont, fontSize = 8.sp, color = RetroDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            RetroPriceText(price = row.price, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            RetroBadge(percent = row.changePercent24h)
        }
    }
}

/** Tessera 8-bit della moneta: prima lettera, bordo ciano, bevel interno. */
@Composable
private fun RetroTile(row: MarketRow) {
    Box(
        Modifier
            .size(38.dp)
            .background(RetroTileBg)
            .border(2.dp, RetroCyan)
            .retroInsetBevel(),
        contentAlignment = Alignment.Center,
    ) {
        Text(row.base.first().toString(), fontFamily = RetroFont, fontSize = 15.sp, color = Color.White)
    }
}

private fun Modifier.retroInsetBevel(): Modifier = drawBehind {
    val inset = 2.dp.toPx()
    drawRect(RetroCyan.copy(alpha = 0.28f), topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, 2.dp.toPx()))
    drawRect(RetroCyan.copy(alpha = 0.28f), topLeft = Offset(inset, inset), size = Size(2.dp.toPx(), size.height - inset * 2))
    drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(inset, size.height - inset * 2), size = Size(size.width - inset * 2, 2.dp.toPx()))
    drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(size.width - inset * 2, inset), size = Size(2.dp.toPx(), size.height - inset * 2))
}

private fun Modifier.retroBevel(): Modifier = drawBehind {
    val inset = 3.dp.toPx()
    drawRect(Color.White.copy(alpha = 0.12f), topLeft = Offset(0f, 0f), size = Size(size.width, 3.dp.toPx()))
    drawRect(Color.Black.copy(alpha = 0.7f), topLeft = Offset(0f, size.height - 3.dp.toPx()), size = Size(size.width, 3.dp.toPx()))
    drawRect(RetroBorder, topLeft = Offset(inset, 3.dp.toPx()), size = Size(size.width - inset * 2, 1.dp.toPx()))
}

/**
 * Riga Split-flap: tabellone aeroportuale, simbolo come codice volo. Come nei
 * tabelloni veri ogni spazio ha la STESSA larghezza (numero o lettera): prezzo e
 * variazione usano moduli uniformi larghi il doppio di un digit standard — il
 * prezzo su una riga propria così le caselle ci stanno larghe.
 */
@Composable
private fun FlapRow(row: MarketRow, click: Modifier) {
    Surface(
        color = FlapCard,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().then(click),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.base, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = FlapInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${row.symbol} · ${row.provider.label}", fontSize = 11.sp, color = FlapDim, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                SplitFlapRow(text = row.base.take(5), fontSize = 14.sp, color = FlapDim)
            }
            Spacer(Modifier.height(8.dp))
            SplitFlapRow(
                text = flapPrice(row),
                fontSize = 20.sp,
                color = FlapInk,
                uniformWidth = true,
                cellWidthFactor = 2f,
            )
            Spacer(Modifier.height(4.dp))
            SplitFlapRow(
                text = flapPercent(row.changePercent24h),
                fontSize = 11.sp,
                color = if (row.changePercent24h >= 0) FlapUp else FlapDown,
                uniformWidth = true,
                cellWidthFactor = 2f,
            )
        }
    }
}

private fun flapPrice(row: MarketRow): String = Format.price(row.price)

private fun flapPercent(v: Double): String =
    (if (v >= 0) "+" else "-") + String.format(Locale.US, "%.2f", abs(v)) + "%"

// ---------- Prezzo Retro (static TV analogica) ----------

/**
 * Prezzo in pixel font verde neon: quando il valore cambia, per ~250ms i numeri
 * "sfarfallano" come la neve di una TV analogica, poi si fermano sul nuovo
 * valore. I numeri invariati restano fermi.
 */
@Composable
private fun RetroPriceText(
    price: Double,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
) {
    val text = if (price > 0) "$" + Format.price(price) else Format.price(price)
    var lastText by remember { mutableStateOf(text) }
    var flashing by remember { mutableStateOf(false) }
    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(text) {
        if (text == lastText) return@LaunchedEffect
        lastText = text
        flashing = true
        val start = withFrameNanos { it }
        while (withFrameNanos { it } - start < 250_000_000L) frameTick++
        flashing = false
    }

    Box(modifier) {
        Text(
            text = text,
            fontFamily = RetroFont,
            fontSize = fontSize,
            color = RetroGreen,
            style = TextStyle(shadow = Shadow(color = RetroGreen.copy(alpha = 0.45f), blurRadius = 6f, offset = Offset.Zero)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (flashing) {
            Canvas(Modifier.matchParentSize()) {
                val n = maxOf(20, (size.width * size.height / 80).toInt())
                repeat(n) {
                    val r = Random(frameTick * 7919 + it * 104729)
                    drawRect(
                        color = RetroNoiseColors[r.nextInt(RetroNoiseColors.size)],
                        topLeft = Offset(
                            r.nextInt((size.width + 1).toInt()).toFloat(),
                            r.nextInt((size.height + 1).toInt()).toFloat(),
                        ),
                        size = Size((1 + r.nextInt(3)).toFloat(), (1 + r.nextInt(2)).toFloat()),
                    )
                }
            }
        }
    }
}

/** Pill variazione in pixel font: verde/rosso, come il prezzo. */
@Composable
private fun RetroBadge(percent: Double) {
    val color = if (percent >= 0) RetroGreen else RetroRed
    Text(
        text = flapPercent(percent),
        fontFamily = RetroFont,
        fontSize = 8.sp,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.12f)).padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

// ---------- Overlay CRT + stati ----------

/** Scanline orizzontali + vignettatura CRT, sopra tutta la pagina Retro. */
@Composable
private fun RetroScanlines(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawRect(Color.Black.copy(alpha = 0.20f), topLeft = Offset(0f, y), size = Size(size.width, 1f))
            y += step
        }
        drawRect(
            brush = Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.maxDimension * 0.55f,
            ),
        )
    }
}

@Composable
private fun FavoritesBanner(style: FavoritesStyle, message: String) {
    val color = accent(style)
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            message,
            fontFamily = if (style == FavoritesStyle.RETRO) RetroFont else FontFamily.Default,
            fontSize = if (style == FavoritesStyle.RETRO) 7.sp else MaterialTheme.typography.labelMedium.fontSize,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyFavorites(style: FavoritesStyle) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (style) {
                FavoritesStyle.RETRO -> {
                    Text("NO FAVORITES", fontFamily = RetroFont, fontSize = 11.sp, color = RetroCyan)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "HOLD A COIN IN MARKETS TO PIN IT",
                        fontFamily = RetroFont, fontSize = 8.sp, color = RetroDim,
                        textAlign = TextAlign.Center, lineHeight = 12.sp,
                    )
                }
                FavoritesStyle.SPLITFLAP -> {
                    Text("NO FAVORITES", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = FlapAmber, letterSpacing = 2.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Hold a coin in Markets to pin it here.", fontSize = 12.sp, color = FlapDim, textAlign = TextAlign.Center)
                }
                else -> {
                    Text("No favorites yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Long-press a coin in Markets to pin it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
