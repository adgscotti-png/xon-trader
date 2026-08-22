package com.adgent.trader.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adgent.trader.core.model.Kline

/** Oscillatori disponibili come sub-chart (F5). */
enum class OscKind(val label: String) { RSI("RSI"), MACD("MACD") }

private val GreenUp = Color(0xFF20B65A)
private val RedDown = Color(0xFFE5484D)
private val MacdBlue = Color(0xFF4C9AFF)
private val RsiYellow = Color(0xFFF0B90B)

/**
 * Sub-chart oscillatore sotto il grafico principale: RSI (guida 30/70) o
 * MACD (istogramma + linee MACD/segnale). Stessa ascissa del grafico candele.
 */
@Composable
fun OscillatorPanel(
    klines: List<Kline>,
    kind: OscKind,
    modifier: Modifier = Modifier,
) {
    val guides = MaterialTheme.colorScheme.outlineVariant
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant

    val closes = remember(klines) { klines.map { it.close } }
    val rsiVals = remember(closes, kind) {
        if (kind == OscKind.RSI) Indicators.rsi(closes) else emptyList()
    }
    val macdVals = remember(closes, kind) {
        if (kind == OscKind.MACD) Indicators.macd(closes) else null
    }

    Box(modifier.fillMaxWidth().height(110.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            when (kind) {
                OscKind.RSI -> drawRsi(rsiVals, guides)
                OscKind.MACD -> macdVals?.let { drawMacd(it, guides) }
            }
        }
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 4.dp),
        ) {
            when (kind) {
                OscKind.RSI -> {
                    Text("RSI (14)", style = MaterialTheme.typography.labelSmall, color = textDim)
                    rsiVals.lastOrNull()?.let { last ->
                        Text(
                            "%.1f".format(last),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                last >= 70 -> RedDown
                                last <= 30 -> GreenUp
                                else -> RsiYellow
                            },
                        )
                    }
                }
                OscKind.MACD -> {
                    Text("MACD (12,26,9)", style = MaterialTheme.typography.labelSmall, color = textDim)
                    macdVals?.histogram?.lastOrNull()?.let { h ->
                        Text(
                            "%+.3f".format(h),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (h >= 0) GreenUp else RedDown,
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRsi(values: List<Double?>, guideColor: Color) {
    if (values.isEmpty()) return
    fun y(v: Double) = size.height * (1f - (v / 100.0).toFloat())

    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
    listOf(30.0, 50.0, 70.0).forEach { lvl ->
        drawLine(
            color = guideColor,
            start = Offset(0f, y(lvl)),
            end = Offset(size.width, y(lvl)),
            strokeWidth = 1f,
            pathEffect = dash,
        )
    }

    val n = values.size
    val path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        v ?: return@forEachIndexed
        val x = if (n == 1) 0f else size.width * i / (n - 1)
        if (!started) {
            path.moveTo(x, y(v)); started = true
        } else {
            path.lineTo(x, y(v))
        }
    }
    drawPath(path, RsiYellow, style = Stroke(width = 2f))
}

private fun DrawScope.drawMacd(m: Indicators.Macd, guideColor: Color) {
    val all = (m.macd + m.signal + m.histogram).filterNotNull()
    if (all.isEmpty() || m.histogram.isEmpty()) return
    val maxAbs = all.maxOf { kotlin.math.abs(it) }.takeIf { it > 0.0 } ?: return

    fun y(v: Double) =
        size.height / 2f - (v / maxAbs * size.height / 2f * 0.92).toFloat()

    val zero = y(0.0)
    drawLine(guideColor, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1f)

    val n = m.histogram.size
    val step = size.width / n
    val barW = step * 0.6f
    m.histogram.forEachIndexed { i, v ->
        v ?: return@forEachIndexed
        val x = step * i + (step - barW) / 2f
        val endY = y(v)
        val top = minOf(endY, zero)
        val height = kotlin.math.abs(endY - zero).coerceAtLeast(1f)
        drawRoundRect(
            color = (if (v >= 0) GreenUp else RedDown).copy(alpha = 0.65f),
            topLeft = Offset(x, top),
            size = Size(barW, height),
            cornerRadius = CornerRadius(1.5f),
        )
    }

    fun series(values: List<Double?>, color: Color) {
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            v ?: return@forEachIndexed
            val x = if (n == 1) 0f else size.width * i / (n - 1)
            if (!started) {
                path.moveTo(x, y(v)); started = true
            } else {
                path.lineTo(x, y(v))
            }
        }
        drawPath(path, color, style = Stroke(width = 2f))
    }
    series(m.macd, MacdBlue)
    series(m.signal, RsiYellow)
}
