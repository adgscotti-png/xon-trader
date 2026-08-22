package com.adgent.trader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adgent.trader.core.common.Format
import com.adgent.trader.ui.theme.MarketGreen
import com.adgent.trader.ui.theme.MarketRed

/** Pill % variazione: verde/rosso convenzione mercato, testo tabulare. */
@Composable
fun ChangeBadge(
    percent: Double,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val color = if (percent >= 0) MarketGreen else MarketRed
    Text(
        text = Format.percent(percent),
        color = color,
        fontSize = if (compact) 11.sp else 13.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = if (compact) 14.sp else 18.sp,
        style = TextStyle(fontFeatureSettings = "tnum"),
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = if (compact) 2.dp else 3.dp),
    )
}

/** Prezzo con cifre tabulari (allineamento verticale perfetto nelle liste). */
@Composable
fun PriceText(
    price: Double,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.SemiBold,
    color: Color = MaterialTheme.colorScheme.onSurface,
    prefix: String = "$",
) {
    Text(
        text = prefix + Format.price(price),
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = fontSize * 1.25f,
        style = TextStyle(fontFeatureSettings = "tnum"),
        modifier = modifier,
    )
}
