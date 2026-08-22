package com.adgent.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Badge circolare moneta: colore deterministico dal simbolo (palette curata),
 * iniziali dell'asset base. Coerente offline, nessuna icona da scaricare.
 */
private val BadgePalette = listOf(
    0xFFF7931A to 0xFFB26400, // bitcoin amber
    0xFF627EEA to 0xFF3A4BA0, // ethereum indigo
    0xFF00FFA3 to 0xFF00A86B, // solana mint
    0xFF26A17B to 0xFF14654B, // tether teal
    0xFF4C3DFF to 0xFF2A1FB8, // brand violet
    0xFFE6007E to 0xFF93004F, // brand magenta
    0xFF00A4DF to 0xFF006C96, // xrp blue
    0xFFC2A633 to 0xFF7A681F, // doge gold
    0xFF8247E5 to 0xFF522C99, // polygon purple
    0xFFE84142 to 0xFF96292B, // avalanche red
    0xFF2775CA to 0xFF174A82, // circle blue
    0xFF2A5ADA to 0xFF16337E, // link blue
)

data class BadgeColors(val start: Color, val end: Color)

fun badgeColorsFor(symbol: String): BadgeColors {
    val idx = (symbol.hashCode() and 0x7FFFFFFF) % BadgePalette.size
    val (s, e) = BadgePalette[idx]
    return BadgeColors(Color(s), Color(e))
}

@Composable
fun CoinBadge(
    base: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = remember(base) { badgeColorsFor(base) }
    val letters = remember(base) { base.take(3).uppercase() }
    val fontSize: TextUnit = when {
        size.value >= 40 -> 14.sp
        size.value >= 28 -> 11.sp
        else -> 9.sp
    }
    Box(
        modifier = modifier
            .size(size)
            .background(Brush.linearGradient(listOf(colors.start, colors.end)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letters,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
