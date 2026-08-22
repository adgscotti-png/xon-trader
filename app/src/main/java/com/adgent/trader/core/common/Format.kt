package com.adgent.trader.core.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Formattazione numerica professionale per prezzi crypto:
 * cifre significative adattive, separatori localizzati, tabular figures a livello UI.
 */
object Format {

    /** Prezzo con precisione adattiva: 43.125,60 · 0,00001234 · 1,2345 */
    fun price(v: Double): String = when {
        v <= 0.0 -> "—"
        v >= 100_000 -> group(v, 0)
        v >= 1_000 -> group(v, 2)
        v >= 1 -> group(v, if (v >= 100) 2 else 4)
        else -> significant(v, 6)
    }

    fun percent(v: Double): String =
        (if (v >= 0) "+" else "−") + group(abs(v), 2) + "%"

    /** Volume compatto: 12,4 Mld · 850 Mln · 12,3 mln… stile it. */
    fun compact(v: Double): String {
        if (v <= 0) return "—"
        val exp = (ln(v) / ln(1000.0)).toInt().coerceIn(0, 4)
        val scaled = v / 1000.0.pow(exp)
        val suffix = when (exp) {
            0 -> ""
            1 -> " k"
            2 -> " Mln"
            3 -> " Mld"
            else -> " Bln"
        }
        return group(scaled, if (scaled >= 100) 1 else 2) + suffix
    }

    private fun group(v: Double, decimals: Int): String =
        String.format(Locale.ITALY, "%,.${decimals}f", v)

    private fun significant(v: Double, sig: Int): String {
        if (v == 0.0) return "0"
        val magnitude = log10(abs(v)).toInt()
        val decimals = (sig - 1 - magnitude).coerceIn(0, 10)
        return group(v, decimals)
    }
}
