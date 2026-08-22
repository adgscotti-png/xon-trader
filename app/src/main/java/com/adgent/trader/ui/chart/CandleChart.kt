package com.adgent.trader.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.model.Kline
import com.adgent.trader.ui.theme.MarketGreen
import com.adgent.trader.ui.theme.MarketRed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/** Finestra visibile del grafico: indice di partenza e numero candele. */
private class ChartWindow {
    var start by mutableFloatStateOf(0f)
    var count by mutableFloatStateOf(120f)
}

/**
 * Grafico professionale stile terminale trading:
 * candele/linea/area, pinch-zoom, pan, crosshair con tooltip OHLC,
 * volumi, medie mobili SMA 7/25/99, linea ultimo prezzo.
 */
@Composable
fun CandleChart(
    klines: List<Kline>,
    modifier: Modifier = Modifier,
    mode: ChartMode = ChartMode.CANDLES,
    showMa: Boolean = true,
    showVolume: Boolean = true,
    livePrice: Double? = null,
    upColor: Color = MarketGreen,
    downColor: Color = MarketRed,
    gridColor: Color = Color.White.copy(alpha = 0.06f),
    labelColor: Color = Color(0xFF9AA0B5),
    crosshairColor: Color = Color.White.copy(alpha = 0.55f),
) {
    val window = remember { ChartWindow() }
    val crosshairIdx = remember { mutableIntStateOf(-1) }
    val textMeasurer = rememberTextMeasurer()

    // Adatta la finestra alla serie disponibile (nuovi dati / nuovo timeframe).
    if (klines.isNotEmpty()) {
        val n = klines.size.toFloat()
        if (window.count == 0f || window.count > n || window.start > n - 2) {
            window.count = min(n, 120f)
            window.start = max(0f, n - window.count)
        }
        window.start = window.start.coerceIn(0f, max(0f, n - window.count))
        window.count = window.count.coerceIn(8f, min(n, 500f))
        if (crosshairIdx.intValue >= klines.size) crosshairIdx.intValue = -1
    }

    val ma = remember(klines, showMa) { if (showMa) Indicators.maOverlays(klines) else null }

    val maColors = mapOf(
        7 to Color(0xFFF0B90B),
        25 to Color(0xFF7B61FF),
        99 to Color(0xFF00A4DF),
    )

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(klines.size, mode) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (klines.isEmpty()) return@detectTransformGestures
                        val n = klines.size.toFloat()
                        val sizeW = size.width.toFloat()
                        val oldSlot = sizeW / window.count
                        val anchorIdx = window.start + centroid.x / oldSlot

                        window.count = (window.count / zoom)
                            .coerceIn(8f, min(n, 500f))
                        val newSlot = sizeW / window.count
                        window.start = (anchorIdx - centroid.x / newSlot)
                            .coerceIn(0f, max(0f, n - window.count))
                        window.start -= pan.x / newSlot
                        window.start = window.start.coerceIn(0f, max(0f, n - window.count))
                    }
                }
                .pointerInput(klines) {
                    // crosshair mentre il dito è premuto (anche trascinato)
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.any { it.pressed }
                            if (!pressed) break
                            val pos = ev.changes.firstOrNull()?.position ?: continue
                            if (klines.isNotEmpty()) {
                                val slot = size.width.toFloat() / window.count
                                crosshairIdx.intValue =
                                    floor(window.start + pos.x / slot)
                                        .toInt()
                                        .coerceIn(0, klines.size - 1)
                            }
                        }
                        crosshairIdx.intValue = -1
                    }
                },
        ) {
            if (klines.isEmpty()) return@Canvas
            drawTradingView(
                klines = klines,
                ma = ma,
                maColors = maColors,
                mode = mode,
                showMa = showMa,
                showVolume = showVolume,
                window = window,
                livePrice = livePrice,
                crosshairIdx = crosshairIdx.intValue,
                upColor = upColor,
                downColor = downColor,
                gridColor = gridColor,
                labelColor = labelColor,
                crosshairColor = crosshairColor,
                textMeasurer = textMeasurer,
            )
        }
    }
}

// ---------- Rendering ----------

private fun DrawScope.drawTradingView(
    klines: List<Kline>,
    ma: Map<Int, List<Double?>>?,
    maColors: Map<Int, Color>,
    mode: ChartMode,
    showMa: Boolean,
    showVolume: Boolean,
    window: ChartWindow,
    livePrice: Double?,
    crosshairIdx: Int,
    upColor: Color,
    downColor: Color,
    gridColor: Color,
    labelColor: Color,
    crosshairColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val w = size.width
    val h = size.height
    val axisLabelHeight = 34f
    val volumeBand = if (showVolume) h * 0.14f else 0f
    val chartTop = 8f
    val chartBottom = h - axisLabelHeight - volumeBand
    val plotH = chartBottom - chartTop

    val n = klines.size
    val firstIdx = floor(window.start).toInt().coerceIn(0, n - 1)
    val lastIdx = min(n - 1, ceil(window.start + window.count).toInt())
    val visible = klines.subList(firstIdx, lastIdx + 1)

    var lo = visible.minOf { it.low }
    var hi = visible.maxOf { it.high }
    if (mode != ChartMode.CANDLES) {
        lo = min(lo, visible.minOf { it.close })
        hi = max(hi, visible.maxOf { it.close })
    }
    if (showMa && ma != null) {
        ma.values.forEach { series ->
            (firstIdx..lastIdx).forEach { i ->
                series.getOrNull(i)?.let { v ->
                    lo = min(lo, v); hi = max(hi, v)
                }
            }
        }
    }
    livePrice?.let { lo = min(lo, it); hi = max(hi, it) }
    val range = (hi - lo).takeIf { it > 0 } ?: 1.0
    val padY = range * 0.06
    lo -= padY; hi += padY

    fun y(v: Double): Float = chartBottom - ((v - lo) / (hi - lo)).toFloat() * plotH
    val slot = w / window.count
    fun x(i: Int): Float = (i - window.start + 0.5f) * slot
    val bodyW = (slot * 0.68f).coerceAtLeast(1f)

    // --- griglia orizzontale + etichette prezzo ---
    val step = niceStep((hi - lo) / 4.0)
    var gridV = (lo / step).toInt() * step + step
    val labelStyle = TextStyle(fontSize = 19.sp, color = labelColor)
    while (gridV < hi) {
        val gy = y(gridV)
        drawLine(gridColor, Offset(0f, gy), Offset(w - 4f, gy), strokeWidth = 1f)
        val lbl = textMeasurer.measure(Format.price(gridV), labelStyle)
        drawText(lbl, topLeft = Offset(w - lbl.size.width - 10f, gy - lbl.size.height / 2))
        gridV += step
    }

    // --- griglia verticale + etichette tempo ---
    val labelEvery = max(1, (window.count / 5).roundToInt())
    val timeFmt = DateTimeFormatter.ofPattern(if (window.count <= 150) "dd/MM HH:mm" else "dd/MM/yy")
        .withZone(ZoneId.systemDefault())
    (firstIdx..lastIdx).forEach { i ->
        if ((i - firstIdx) % labelEvery != 0) return@forEach
        val gx = x(i)
        drawLine(gridColor, Offset(gx, chartTop), Offset(gx, chartBottom), strokeWidth = 1f)
        val txt = timeFmt.format(Instant.ofEpochMilli(klines[i].openTime))
        val lbl = textMeasurer.measure(txt, labelStyle)
        val lx = (gx - lbl.size.width / 2f).coerceIn(4f, w - lbl.size.width - 4f)
        drawText(lbl, topLeft = Offset(lx, h - axisLabelHeight + 8f))
    }

    // --- volumi ---
    if (showVolume) {
        val maxVol = visible.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
        (firstIdx..lastIdx).forEach { i ->
            val k = klines[i]
            val c = if (k.close >= k.open) upColor else downColor
            val barH = (k.volume / maxVol * volumeBand * 0.92).toFloat()
            drawRect(
                color = c.copy(alpha = 0.45f),
                topLeft = Offset(x(i) - bodyW / 2, h - axisLabelHeight - barH),
                size = Size(bodyW, barH),
            )
        }
    }

    // --- serie principale ---
    when (mode) {
        ChartMode.CANDLES -> (firstIdx..lastIdx).forEach { i ->
            val k = klines[i]
            val c = if (k.close >= k.open) upColor else downColor
            val cx = x(i)
            // ombra
            drawLine(c, Offset(cx, y(k.high)), Offset(cx, y(k.low)), strokeWidth = 1.5f)
            // corpo
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
                    lineTo(x(lastIdx), chartBottom)
                    lineTo(x(firstIdx), chartBottom)
                    close()
                }
                drawPath(
                    area,
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(upColor.copy(alpha = 0.30f), Color.Transparent),
                        startY = chartTop,
                        endY = chartBottom,
                    ),
                )
            }
            drawPath(path, upColor, style = Stroke(width = 2.2f))
        }
    }

    // --- medie mobili ---
    if (showMa && ma != null) {
        ma.forEach { (period, series) ->
            val c = maColors[period] ?: Color.Gray
            val p = Path()
            var started = false
            (firstIdx..lastIdx).forEach { i ->
                val v = series.getOrNull(i) ?: return@forEach
                val pt = Offset(x(i), y(v))
                if (!started) { p.moveTo(pt.x, pt.y); started = true } else p.lineTo(pt.x, pt.y)
            }
            drawPath(p, c, style = Stroke(width = 1.6f))
        }
    }

    // --- linea ultimo prezzo ---
    val lastPrice = livePrice ?: klines.lastOrNull()?.close
    if (lastPrice != null && lastPrice > lo && lastPrice < hi) {
        val ly = y(lastPrice)
        dashLine(crosshairColor, Offset(0f, ly), Offset(w - 4f, ly))
        val lbl = textMeasurer.measure(Format.price(lastPrice), TextStyle(fontSize = 19.sp, color = Color.Black))
        val bg = if ((livePrice ?: klines.last().close) >= klines[lastIdx].open) upColor else downColor
        drawRoundRect(bg, Offset(w - lbl.size.width - 14f, ly - lbl.size.height / 2 - 4f),
            Size(lbl.size.width + 10f, lbl.size.height + 8f), CornerRadius(5f))
        drawText(lbl, topLeft = Offset(w - lbl.size.width - 9f, ly - lbl.size.height / 2))
    }

    // --- crosshair + tooltip ---
    if (crosshairIdx in firstIdx..lastIdx) {
        val k = klines[crosshairIdx]
        val cx = x(crosshairIdx)
        val cy = y(k.close)
        dashLine(crosshairColor, Offset(cx, chartTop), Offset(cx, chartBottom))
        dashLine(crosshairColor, Offset(0f, cy), Offset(w, cy))

        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
        val l1 = textMeasurer.measure(fmt.format(Instant.ofEpochMilli(k.openTime)),
            TextStyle(fontSize = 18.sp, color = labelColor))
        val valueStyleUp = TextStyle(fontSize = 19.sp, color = upColor)
        val valueStyleDn = TextStyle(fontSize = 19.sp, color = downColor)
        val isUp = k.close >= k.open
        val oL = textMeasurer.measure("O ${Format.price(k.open)}", if (isUp) valueStyleUp else valueStyleDn)
        val hL = textMeasurer.measure("H ${Format.price(k.high)}", if (isUp) valueStyleUp else valueStyleDn)
        val lL = textMeasurer.measure("L ${Format.price(k.low)}", if (isUp) valueStyleUp else valueStyleDn)
        val cL = textMeasurer.measure("C ${Format.price(k.close)}", if (isUp) valueStyleUp else valueStyleDn)
        val gap = 12f
        val totalW = oL.size.width + hL.size.width + lL.size.width + cL.size.width + gap * 3
        val bx = (cx + 14f).coerceIn(4f, w - totalW - 12f)
        var tx = bx
        drawText(l1, topLeft = Offset(bx, 6f))
        val rowY = 6f + l1.size.height + 6f
        listOf(oL, hL, lL, cL).forEach {
            drawText(it, topLeft = Offset(tx, rowY)); tx += it.size.width + gap
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
    if (raw <= 0) return 1.0
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
