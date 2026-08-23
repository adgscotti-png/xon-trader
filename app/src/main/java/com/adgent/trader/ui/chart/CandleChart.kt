package com.adgent.trader.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.model.Kline
import com.adgent.trader.ui.theme.MarketGreen
import com.adgent.trader.ui.theme.MarketRed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ChartMode(val label: String) {
    CANDLES("Candele"),
    LINE("Linea"),
    AREA("Area"),
}

/** Quanto tempo il dito deve restare fermo sul grafico per far apparire la linea avviso. */
private const val ALERT_LONG_PRESS_MS = 450L

/** Slot orizzontale desiderato per candela al fit iniziale: determina quante barre riempiono lo schermo. */
private val TARGET_SLOT_DP = 7.dp

/**
 * Finestra visibile del grafico: indice (float) di partenza e numero candele.
 * Tutte le scritture passano da [clamp]: mai range di coercion vuoti (fonte
 * classica di crash con serie corte).
 */
private class ChartWindow {
    var start by mutableFloatStateOf(0f)
    var count by mutableFloatStateOf(120f)

    fun clamp(n: Int) {
        if (n <= 0) return
        val nf = n.toFloat()
        count = count.coerceIn(1f, nf)
        start = start.coerceIn(0f, max(0f, nf - count))
    }
}

/**
 * Geometria del grafico condivisa tra gesture e rendering: niente testo dentro
 * l'area candele — fascia superiore per il tooltip OHLC, fascia inferiore per i
 * tempi, gutter destro per le etichette prezzo.
 */
private class ChartLayout(
    val w: Float,
    val h: Float,
    showVolume: Boolean,
    val gutter: Float,
) {
    /** Fascia riservata al tooltip OHLC (mai sopra le candele). */
    val topBand = 34f

    /** Fascia riservata alle etichette dei tempi. */
    val axisLabelHeight = 32f
    val volumeBand = if (showVolume) h * 0.14f else 0f
    val plotLeft = 0f
    val plotRight = (w - gutter).coerceAtLeast(w * 0.45f)
    val chartTop = topBand
    val chartBottom = h - axisLabelHeight - volumeBand
    val plotW = (plotRight - plotLeft).coerceAtLeast(10f)
    val plotH = (chartBottom - chartTop).coerceAtLeast(1f)

    fun slot(count: Float): Float = plotW / count.coerceAtLeast(1f)
}

/**
 * Scala prezzi→pixel della porzione visibile, calcolata una volta per frame e
 * riusata sia dal rendering sia dalle gesture (per la linea avviso trascinabile).
 */
private class PriceScale(val lo: Double, val hi: Double) {
    fun y(v: Double, l: ChartLayout): Float {
        val denom = (hi - lo).takeIf { it > 0 } ?: 1.0
        return l.chartBottom - ((v - lo) / denom).toFloat() * l.plotH
    }

    fun invert(y: Float, l: ChartLayout): Double {
        val frac = ((l.chartBottom - y) / l.plotH).toDouble().coerceIn(0.0, 1.0)
        return lo + frac * (hi - lo)
    }
}

private fun computeScale(
    klines: List<Kline>,
    firstIdx: Int,
    lastIdx: Int,
    ma: Map<Int, List<Double?>>?,
    bb: Triple<List<Double?>, List<Double?>, List<Double?>>?,
    livePrice: Double?,
    mode: ChartMode,
): PriceScale {
    if (firstIdx > lastIdx || klines.isEmpty()) return PriceScale(0.0, 1.0)
    val visible = klines.subList(firstIdx, lastIdx + 1)
    var lo = visible.minOf { it.low }
    var hi = visible.maxOf { it.high }
    if (mode != ChartMode.CANDLES) {
        lo = min(lo, visible.minOf { it.close })
        hi = max(hi, visible.maxOf { it.close })
    }
    ma?.values?.forEach { series ->
        (firstIdx..lastIdx).forEach { i ->
            series.getOrNull(i)?.let { v -> lo = min(lo, v); hi = max(hi, v) }
        }
    }
    bb?.let { (upper, _, lower) ->
        (firstIdx..lastIdx).forEach { i ->
            upper.getOrNull(i)?.let { v -> lo = min(lo, v); hi = max(hi, v) }
            lower.getOrNull(i)?.let { v -> lo = min(lo, v); hi = max(hi, v) }
        }
    }
    livePrice?.let { lo = min(lo, it); hi = max(hi, it) }
    val range = (hi - lo).takeIf { it > 0 } ?: (abs(hi) * 0.01 + 1.0)
    val padY = range * 0.06
    return PriceScale(lo - padY, hi + padY)
}

/**
 * Grafico professionale stile terminale trading:
 * - trascinamento 1 dito → scorrimento temporale;
 * - pinch 2 dita → zoom sulla distribuzione dei timeframe;
 * - tocco → crosshair con tooltip OHLC;
 * - pressione prolungata → linea prezzo trascinabile per creare un avviso
 *   direttamente dal grafico ([onCreateAlertAtPrice]);
 * - volumi, medie mobili SMA/EMA, bande Bollinger, linea ultimo prezzo.
 */
@Composable
fun CandleChart(
    klines: List<Kline>,
    modifier: Modifier = Modifier,
    mode: ChartMode = ChartMode.CANDLES,
    showMa: Boolean = true,
    showEma: Boolean = false,
    showBb: Boolean = false,
    showVolume: Boolean = true,
    livePrice: Double? = null,
    upColor: Color = MarketGreen,
    downColor: Color = MarketRed,
    gridColor: Color = Color.White.copy(alpha = 0.06f),
    labelColor: Color = Color(0xFF9AA0B5),
    crosshairColor: Color = Color.White.copy(alpha = 0.55f),
    /** Non-null: abilita la creazione rapida avvisi con pressione prolungata. */
    onCreateAlertAtPrice: ((price: Double, above: Boolean) -> Unit)? = null,
) {
    val window = remember { ChartWindow() }
    val crosshairIdx = remember { mutableIntStateOf(-1) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // Larghezza del gutter destro riservata alle etichette prezzo: misurata sui
    // casi peggiori una volta sola, così gesture e disegno condividono esattamente
    // la stessa geometria (lo slot candela dipende da questa misura).
    val gutterPx = remember(textMeasurer) {
        val style = TextStyle(fontSize = 19.sp)
        listOf("888,888.88", "0.00001234").maxOf {
            textMeasurer.measure(it, style).size.width
        } + 24f
    }

    // Larghezza canvas in px (per il fit delle barre) — 0 finché il primo layout.
    var canvasW by remember { mutableIntStateOf(0) }

    // Linea avviso in piazzamento: prezzo selezionato col dito (null = spenta).
    var alertPrice by remember { mutableStateOf<Double?>(null) }
    // Direzione scelta nell'overlay di conferma (ricordata tra i piazzamenti).
    var alertAbove by remember { mutableStateOf(true) }

    val ma = remember(klines, showMa, showEma) {
        when {
            showEma -> Indicators.emaOverlays(klines)
            else -> if (showMa) Indicators.maOverlays(klines) else null
        }
    }
    val bb = remember(klines, showBb) {
        if (showBb) Indicators.bollinger(klines.map { it.close }) else null
    }

    // Adattamento sicuro della finestra ai dati disponibili (nuovi dati / resize).
    if (klines.isNotEmpty()) {
        window.clamp(klines.size)
        if (crosshairIdx.intValue >= klines.size) crosshairIdx.intValue = -1
    }

    // Nuova serie (cambio timeframe): barre dimensionate per riempire lo schermo.
    val seriesKey = klines.firstOrNull()?.openTime ?: 0L
    LaunchedEffect(seriesKey, canvasW) {
        val n = klines.size
        if (n > 0 && canvasW > 0) {
            val targetSlot = with(density) { TARGET_SLOT_DP.toPx() }
            val fit = (canvasW / targetSlot).roundToInt().coerceIn(24, 180)
            window.count = min(n, fit).toFloat()
            window.start = max(0f, n - window.count)
            window.clamp(n)
        }
    }

    val maColors = mapOf(
        7 to Color(0xFFF0B90B),
        25 to Color(0xFF7B61FF),
        99 to Color(0xFF00A4DF),
    )

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasW = it.width }
                .pointerInput(klines.size, mode, onCreateAlertAtPrice != null, gutterPx) {
                    if (klines.isEmpty()) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    fun geom(s: IntSize) =
                        ChartLayout(s.width.toFloat(), s.height.toFloat(), showVolume, gutterPx)
                    // Scope per il timer della pressione prolungata: condivide il
                    // contesto del pointerInput (stesso job → cancellazione inclusa
                    // quando il grafico esce dalla composizione). Il blocco di
                    // awaitEachGesture è a suspension ristretta e non può lanciare
                    // coroutine direttamente, ma launch su questo scope è una
                    // chiamata normale e quindi lecita.
                    val timerScope = CoroutineScope(currentCoroutineContext())
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var multiTouch = false

                        // Timer parallelo per la pressione prolungata: funziona anche
                        // col dito perfettamente fermo (nessun evento di movimento).
                        val holdTimer = timerScope.launch {
                            delay(ALERT_LONG_PRESS_MS)
                            if (!moved && !multiTouch && onCreateAlertAtPrice != null &&
                                alertPrice == null
                            ) {
                                crosshairIdx.intValue = -1
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                val layout = geom(size)
                                val yClamped = down.position.y
                                    .coerceIn(layout.chartTop, layout.chartBottom)
                                visibleScale(klines, window, ma, bb, livePrice, mode)?.let { s ->
                                    alertPrice = s.invert(yClamped, layout)
                                }
                            }
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break

                                // La linea avviso è attiva finché alertPrice non è null.
                                val alertActive = alertPrice != null

                                if (pressed.size >= 2) {
                                    // ---------- PINCH ZOOM (disattivo durante il piazzamento) ----------
                                    multiTouch = true
                                    if (!alertActive) {
                                        val zoomChange = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        val pw = geom(size).plotW
                                        val nf = klines.size.toFloat()

                                        if (!zoomChange.isNaN() && zoomChange != 1f && centroid.x.isFinite()) {
                                            val oldSlot = pw / window.count
                                            val anchorIdx = window.start + centroid.x / oldSlot
                                            window.count = (window.count / zoomChange)
                                                .coerceIn(minOf(8f, nf), min(nf, 500f))
                                            val newSlot = pw / window.count
                                            window.start = anchorIdx - centroid.x / newSlot
                                        }
                                        if (pan.x.isFinite() && pan.x != 0f) {
                                            val slot = pw / window.count
                                            window.start -= pan.x / slot
                                        }
                                        window.clamp(klines.size)
                                    }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else {
                                    // ---------- 1 DITO ----------
                                    val c = pressed[0]
                                    val pos = c.position

                                    if (alertActive) {
                                        // Trascina la linea verticale: y → prezzo.
                                        val layout = geom(size)
                                        val yClamped = pos.y.coerceIn(layout.chartTop, layout.chartBottom)
                                        visibleScale(klines, window, ma, bb, livePrice, mode)?.let { s ->
                                            alertPrice = s.invert(yClamped, layout)
                                        }
                                        c.consume()
                                        continue
                                    }

                                    val totalDx = pos.x - down.position.x
                                    val totalDy = pos.y - down.position.y
                                    val beyondSlop = abs(totalDx) > slop || abs(totalDy) > slop

                                    if (beyondSlop) {
                                        moved = true
                                        val dx = c.position.x - c.previousPosition.x
                                        if (dx != 0f) {
                                            val slot = geom(size).slot(window.count)
                                            window.start -= dx / slot
                                            window.clamp(klines.size)
                                            c.consume()
                                        }
                                    }

                                    // Crosshair segue il dito (anche durante il pan).
                                    if (klines.isNotEmpty()) {
                                        val slot = geom(size).slot(window.count)
                                        crosshairIdx.intValue = floor(window.start + pos.x / slot)
                                            .toInt()
                                            .coerceIn(0, klines.size - 1)
                                    }
                                }
                            }
                        } finally {
                            // Gesture conclusa: il timer non serve più.
                            holdTimer.cancel()
                        }

                        // Rilascio senza linea avviso → crosshair via; con linea resta l'overlay.
                        if (alertPrice == null) crosshairIdx.intValue = -1
                    }
                },
        ) {
            if (klines.isEmpty()) return@Canvas
            drawTradingView(
                klines = klines,
                ma = ma,
                bb = bb,
                maColors = maColors,
                mode = mode,
                showVolume = showVolume,
                window = window,
                livePrice = livePrice,
                crosshairIdx = crosshairIdx.intValue,
                alertPrice = alertPrice,
                upColor = upColor,
                downColor = downColor,
                gridColor = gridColor,
                labelColor = labelColor,
                crosshairColor = crosshairColor,
                textMeasurer = textMeasurer,
                gutter = gutterPx,
            )
        }

        // ---------- Overlay conferma avviso piazzato dal grafico ----------
        alertPrice?.let { price ->
            AlertConfirmBar(
                price = price,
                aboveInitial = alertAbove,
                onAboveChange = { alertAbove = it },
                onConfirm = {
                    onCreateAlertAtPrice?.invoke(price, alertAbove)
                    alertPrice = null
                },
                onDismiss = { alertPrice = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 38.dp),
            )
        }
    }
}

/** Scala visibile corrente (identica a quella del rendering) per le gesture y↔prezzo. */
private fun visibleScale(
    klines: List<Kline>,
    window: ChartWindow,
    ma: Map<Int, List<Double?>>?,
    bb: Triple<List<Double?>, List<Double?>, List<Double?>>?,
    livePrice: Double?,
    mode: ChartMode,
): PriceScale? {
    if (klines.isEmpty()) return null
    val n = klines.size
    val firstIdx = floor(window.start).toInt().coerceIn(0, n - 1)
    val lastIdx = min(n - 1, ceil(window.start + window.count).toInt().coerceAtLeast(firstIdx))
    return computeScale(klines, firstIdx, lastIdx, ma, bb, livePrice, mode)
}

// ---------- Rendering ----------

private fun DrawScope.drawTradingView(
    klines: List<Kline>,
    ma: Map<Int, List<Double?>>?,
    bb: Triple<List<Double?>, List<Double?>, List<Double?>>?,
    maColors: Map<Int, Color>,
    mode: ChartMode,
    showVolume: Boolean,
    window: ChartWindow,
    livePrice: Double?,
    crosshairIdx: Int,
    alertPrice: Double?,
    upColor: Color,
    downColor: Color,
    gridColor: Color,
    labelColor: Color,
    crosshairColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    gutter: Float,
) {
    val w = size.width
    val h = size.height
    val layout = ChartLayout(w, h, showVolume, gutter)
    val n = klines.size
    if (n == 0 || w < 10f) return

    val firstIdx = floor(window.start).toInt().coerceIn(0, n - 1)
    val lastIdx = min(n - 1, ceil(window.start + window.count).toInt())
        .coerceAtLeast(firstIdx)
    val visible = klines.subList(firstIdx, lastIdx + 1)

    val scale = computeScale(klines, firstIdx, lastIdx, ma, bb, livePrice, mode)
    val lo = scale.lo
    val hi = scale.hi

    fun y(v: Double): Float = scale.y(v, layout)

    val slot = (layout.plotW / window.count).coerceAtLeast(0.5f)
    fun x(i: Int): Float = (i - window.start + 0.5f) * slot
    val bodyW = (slot * 0.68f).coerceAtLeast(1f)

    val labelStyle = TextStyle(fontSize = 19.sp, color = labelColor)

    // Prezzo per i tag nel gutter: formato completo se entra, altrimenti compatto.
    fun tagPrice(v: Double): String {
        val full = Format.price(v)
        val wide = textMeasurer.measure(full, TextStyle(fontSize = 19.sp)).size.width
        return if (wide <= gutter - 14f) full else Format.compact(v)
    }

    // --- griglia orizzontale + etichette prezzo nel gutter destro ---
    val rawStep = (hi - lo) / max(3.0, layout.plotH / 64.0)
    val step = niceStep(rawStep)
    val measuredLabelH = textMeasurer.measure(Format.price(hi), labelStyle).size.height
    val minLabelGap = measuredLabelH + 8f
    var lastLabelCenter = -Float.MAX_VALUE
    var gridV = (lo / step).toInt() * step + step
    while (gridV < hi) {
        val gy = y(gridV)
        drawLine(gridColor, Offset(layout.plotLeft, gy), Offset(layout.plotRight, gy), strokeWidth = 1f)
        if (abs(gy - lastLabelCenter) >= minLabelGap) {
            val lbl = textMeasurer.measure(tagPrice(gridV), labelStyle)
            drawText(lbl, topLeft = Offset(w - lbl.size.width - 7f, gy - lbl.size.height / 2f))
            lastLabelCenter = gy
        }
        gridV += step
    }

    // --- griglia verticale + etichette tempo (spaziate in base alla larghezza) ---
    var labelEvery = max(1, (window.count / 5).roundToInt())
    val timeFmt = DateTimeFormatter.ofPattern(if (window.count <= 150) "dd/MM HH:mm" else "dd/MM/yy")
        .withZone(ZoneId.systemDefault())
    val sampleTxt = timeFmt.format(Instant.ofEpochMilli(klines[firstIdx].openTime))
    val sampleW = textMeasurer.measure(sampleTxt, labelStyle).size.width
    labelEvery = max(labelEvery, ceil(sampleW * 1.4f / slot).toInt())

    (firstIdx..lastIdx).forEach { i ->
        if ((i - firstIdx) % labelEvery != 0) return@forEach
        val gx = x(i)
        drawLine(gridColor, Offset(gx, layout.chartTop), Offset(gx, layout.chartBottom), strokeWidth = 1f)
        val txt = timeFmt.format(Instant.ofEpochMilli(klines[i].openTime))
        val lbl = textMeasurer.measure(txt, labelStyle)
        val lx = (gx - lbl.size.width / 2f).coerceIn(4f, (layout.plotRight - lbl.size.width - 4f).coerceAtLeast(4f))
        drawText(lbl, topLeft = Offset(lx, h - layout.axisLabelHeight + 7f))
    }

    // --- volumi ---
    if (showVolume && visible.isNotEmpty()) {
        val maxVol = visible.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
        (firstIdx..lastIdx).forEach { i ->
            val k = klines[i]
            val c = if (k.close >= k.open) upColor else downColor
            val barH = (k.volume / maxVol * layout.volumeBand * 0.92).toFloat()
            if (barH > 0.5f) {
                drawRect(
                    color = c.copy(alpha = 0.45f),
                    topLeft = Offset(x(i) - bodyW / 2, h - layout.axisLabelHeight - barH),
                    size = Size(bodyW, barH),
                )
            }
        }
    }

    // --- serie principale ---
    when (mode) {
        ChartMode.CANDLES -> (firstIdx..lastIdx).forEach { i ->
            val k = klines[i]
            val c = if (k.close >= k.open) upColor else downColor
            val cx = x(i)
            drawLine(c, Offset(cx, y(k.high)), Offset(cx, y(k.low)), strokeWidth = 1.5f)
            val top = y(max(k.open, k.close))
            val bot = y(min(k.open, k.close))
            val bodyRect = Rect(cx - bodyW / 2, top, cx + bodyW / 2, bot)
            if (bodyRect.height < 1f) {
                drawLine(c, Offset(cx, top), Offset(cx, bot), strokeWidth = 1.5f)
            } else {
                drawRoundRect(c, bodyRect.topLeft, Size(bodyW, bodyRect.height), CornerRadius(bodyW * 0.15f))
            }
        }
        ChartMode.LINE, ChartMode.AREA -> {
            val path = Path()
            (firstIdx..lastIdx).forEach { i ->
                val p = Offset(x(i), y(klines[i].close))
                if (i == firstIdx) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            if (mode == ChartMode.AREA) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(x(lastIdx), layout.chartBottom)
                    lineTo(x(firstIdx), layout.chartBottom)
                    close()
                }
                drawPath(
                    area,
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(upColor.copy(alpha = 0.30f), Color.Transparent),
                        startY = layout.chartTop,
                        endY = layout.chartBottom,
                    ),
                )
            }
            drawPath(path, upColor, style = Stroke(width = 2.2f))
        }
    }

    // --- bande Bollinger ---
    if (bb != null) {
        val (upper, mid, lower) = bb
        val top = Path(); val bottom = Path()
        var started = false
        (firstIdx..lastIdx).forEach { i ->
            val u = upper.getOrNull(i) ?: return@forEach
            val l = lower.getOrNull(i) ?: return@forEach
            if (!started) {
                top.moveTo(x(i), y(u)); bottom.moveTo(x(i), y(l)); started = true
            } else {
                top.lineTo(x(i), y(u)); bottom.lineTo(x(i), y(l))
            }
        }
        if (started) {
            val band = Path().apply {
                addPath(top)
                val pts = (firstIdx..lastIdx).mapNotNull { i ->
                    lower.getOrNull(i)?.let { x(i) to it }
                }.asReversed()
                pts.forEach { (px, v) -> lineTo(px, y(v)) }
                close()
            }
            drawPath(band, upColor.copy(alpha = 0.08f))
            drawPath(top, labelColor.copy(alpha = 0.7f), style = Stroke(width = 1.2f))
            drawPath(bottom, labelColor.copy(alpha = 0.7f), style = Stroke(width = 1.2f))
        }
        val midPath = Path()
        var mStarted = false
        (firstIdx..lastIdx).forEach { i ->
            val m = mid.getOrNull(i) ?: return@forEach
            val p = Offset(x(i), y(m))
            if (!mStarted) { midPath.moveTo(p.x, p.y); mStarted = true } else midPath.lineTo(p.x, p.y)
        }
        if (mStarted) {
            drawPath(midPath, Color(0xFFF0B90B).copy(alpha = 0.9f), style = Stroke(width = 1.2f))
        }
    }

    // --- medie mobili ---
    if (ma != null) {
        ma.forEach { (period, series) ->
            val c = maColors[period] ?: Color.Gray
            val p = Path()
            var started = false
            (firstIdx..lastIdx).forEach { i ->
                val v = series.getOrNull(i) ?: return@forEach
                val pt = Offset(x(i), y(v))
                if (!started) { p.moveTo(pt.x, pt.y); started = true } else p.lineTo(pt.x, pt.y)
            }
            if (started) drawPath(p, c, style = Stroke(width = 1.6f))
        }
    }

    // --- linea ultimo prezzo (tag nel gutter, mai sopra le candele) ---
    val lastPrice = livePrice ?: klines.lastOrNull()?.close
    if (lastPrice != null && lastPrice > lo && lastPrice < hi) {
        val ly = y(lastPrice)
        dashLine(crosshairColor, Offset(layout.plotLeft, ly), Offset(layout.plotRight, ly))
        val lbl = textMeasurer.measure(tagPrice(lastPrice), TextStyle(fontSize = 19.sp, color = Color.Black))
        val bg = if ((livePrice ?: klines.last().close) >= klines[lastIdx].open) upColor else downColor
        val tagW = minOf(lbl.size.width + 12f, gutter - 4f).coerceAtLeast(20f)
        drawRoundRect(bg, Offset(w - tagW - 3f, ly - lbl.size.height / 2 - 4f),
            Size(tagW, lbl.size.height + 8f), CornerRadius(5f))
        drawText(lbl, topLeft = Offset(w - lbl.size.width - 9f, ly - lbl.size.height / 2))
    }

    // --- linea avviso in piazzamento (tag nel gutter) ---
    alertPrice?.let { ap ->
        val ay = y(ap).coerceIn(layout.chartTop, layout.chartBottom)
        drawLine(
            Color(0xFFF0B90B), Offset(layout.plotLeft, ay), Offset(layout.plotRight, ay), strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f)),
        )
        val lbl = textMeasurer.measure(tagPrice(ap), TextStyle(fontSize = 19.sp, color = Color.Black))
        val tagW = minOf(lbl.size.width + 14f, gutter - 4f).coerceAtLeast(20f)
        drawRoundRect(
            Color(0xFFF0B90B),
            Offset(w - tagW - 3f, ay - lbl.size.height / 2 - 4f),
            Size(tagW, lbl.size.height + 8f),
            CornerRadius(5f),
        )
        drawText(lbl, topLeft = Offset(w - lbl.size.width - 9f, ay - lbl.size.height / 2))
    }

    // --- crosshair + tooltip OHLC nella fascia superiore riservata ---
    if (crosshairIdx in firstIdx..lastIdx) {
        val k = klines[crosshairIdx]
        val cx = x(crosshairIdx)
        val cy = y(k.close)
        dashLine(crosshairColor, Offset(cx, layout.chartTop), Offset(cx, layout.chartBottom))
        dashLine(crosshairColor, Offset(layout.plotLeft, cy), Offset(layout.plotRight, cy))

        // Prezzo sotto il dito, nel gutter.
        val tagLbl = textMeasurer.measure(tagPrice(k.close), TextStyle(fontSize = 19.sp, color = Color.White))
        val tagBgW = minOf(tagLbl.size.width + 12f, gutter - 4f).coerceAtLeast(20f)
        drawRoundRect(
            Color(0xFF2A3040),
            Offset(w - tagBgW - 3f, (cy - tagLbl.size.height / 2 - 4f).coerceIn(layout.chartTop, layout.chartBottom - tagLbl.size.height - 8f)),
            Size(tagBgW, tagLbl.size.height + 8f),
            CornerRadius(5f),
        )
        drawText(tagLbl, topLeft = Offset(w - tagLbl.size.width - 9f, cy - tagLbl.size.height / 2))

        // Riga unica data + OHLC nella fascia alta: nessuna sovrapposizione.
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        val l1 = textMeasurer.measure(fmt.format(Instant.ofEpochMilli(k.openTime)),
            TextStyle(fontSize = 18.sp, color = labelColor))
        val valueStyleUp = TextStyle(fontSize = 19.sp, color = upColor)
        val valueStyleDn = TextStyle(fontSize = 19.sp, color = downColor)
        val isUp = k.close >= k.open
        val style = if (isUp) valueStyleUp else valueStyleDn
        val gap = 12f
        var tx = 6f
        drawText(l1, topLeft = Offset(tx, 8f)); tx += l1.size.width + gap
        listOf(
            "O" to k.open,
            "H" to k.high,
            "L" to k.low,
            "C" to k.close,
        ).forEach { (prefix, v) ->
            val seg = textMeasurer.measure("$prefix ${tagPrice(v)}", style)
            if (tx + seg.size.width <= layout.plotRight) {
                drawText(seg, topLeft = Offset(tx, 7f)); tx += seg.size.width + gap
            }
        }
    }
}

private fun DrawScope.dashLine(color: Color, from: Offset, to: Offset) {
    drawLine(
        color, from, to, strokeWidth = 1.2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
    )
}

/** Step "bello" per le linee di griglia: 1, 2, 2.5, 5 × 10^n */
private fun niceStep(raw: Double): Double {
    if (raw <= 0 || raw.isNaN() || raw.isInfinite()) return 1.0
    val exp = floor(kotlin.math.log10(raw)).toInt()
    val base = raw / Math.pow(10.0, exp.toDouble())
    val nice = when {
        base < 1.5 -> 1.0
        base < 3.0 -> 2.0
        base < 7.0 -> 5.0
        else -> 10.0
    }
    return nice * Math.pow(10.0, exp.toDouble())
}

// ---------- Overlay conferma avviso ----------

/**
 * Barra di conferma sotto la linea avviso: direzione Sopra/Sotto, prezzo scelto,
 * conferma o annullamento. Compare al rilascio del dito dopo la pressione lunga.
 */
@Composable
private fun AlertConfirmBar(
    price: Double,
    aboveInitial: Boolean,
    onAboveChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            FilterChip(
                selected = aboveInitial,
                onClick = { onAboveChange(true) },
                label = { Text("Above") },
            )
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = !aboveInitial,
                onClick = { onAboveChange(false) },
                label = { Text("Below") },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$" + Format.price(price),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onConfirm) {
                Icon(
                    Icons.Rounded.Check, contentDescription = "Create alert at this price",
                    tint = Color(0xFF20B65A),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close, contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
