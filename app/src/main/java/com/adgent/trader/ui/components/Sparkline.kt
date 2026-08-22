package com.adgent.trader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.adgent.trader.ui.theme.MarketGreen
import com.adgent.trader.ui.theme.MarketRed
import kotlin.math.max
import kotlin.math.min

/**
 * Sparkline con riempimento sfumato: colore in base al trend della finestra.
 * Usata nelle righe di mercato e (via bitmap) nei widget.
 */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    positive: Boolean = true,
    strokeWidthPx: Float = 2f,
) {
    val line = if (positive) MarketGreen else MarketRed
    // Path + area precalcolati fuori dal draw per performance su liste lunghe.
    val geometry = remember(values, positive) { buildGeometry(values) }

    Canvas(modifier = modifier) {
        if (geometry == null || size.width <= 0 || size.height <= 0) return@Canvas
        val (path, area) = geometry

        drawPath(
            brush = Brush.verticalGradient(
                colors = listOf(line.copy(alpha = 0.28f), Color.Transparent),
                startY = 0f,
                endY = size.height,
            ),
            path = area,
        )
        drawPath(path, color = line, style = Stroke(width = strokeWidthPx))
    }
}

private typealias Geom = Pair<Path, Path>

private fun buildGeometry(values: List<Double>): Geom? {
    if (values.size < 2) return null
    val minV = values.min()
    val maxV = values.max()
    val range = max(maxV - minV, 1e-12)

    // Normalizza in coordinate unit [0..1] — il Canvas le scala alla size reale.
    val stepX = 1.0 / (values.size - 1)
    val pts = values.mapIndexed { i, v ->
        Offset((i * stepX).toFloat(), 1f - ((v - minV) / range).toFloat())
    }

    val linePath = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            val p0 = pts[i - 1]
            val p1 = pts[i]
            // curva quadratica morbida tra punti consecutivi
            val midX = (p0.x + p1.x) / 2f
            quadraticBezierTo(midX, p0.y, p1.x, p1.y)
        }
    }
    val areaPath = Path().apply {
        addPath(linePath)
        lineTo(1f, 1.02f)
        lineTo(0f, 1.02f)
        close()
    }
    return Geom(linePath, areaPath)
}
