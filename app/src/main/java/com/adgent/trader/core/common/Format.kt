package com.adgent.trader.core.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Price display style selectable per widget: from the adaptive default to the
 * compact variants meant for large text with minimal information.
 */
enum class NumberFormatMode(val label: String, val example: String) {
    AUTO("Auto", "97,400.12"),
    FULL("Full", "97,400.12"),
    WHOLE("No decimals", "97400"),
    COMPACT("Compact", "97.4k"),
}

/**
 * Professional numeric formatting for crypto prices:
 * adaptive significant digits, US-style separators (app UI is English),
 * tabular figures at the UI level.
 */
object Format {

    /** Adaptive-precision price: 43,125.60 · 0.00001234 · 1.2345 */
    fun price(v: Double): String = when {
        v <= 0.0 -> "—"
        v >= 100_000 -> group(v, 0)
        v >= 1_000 -> group(v, 2)
        v >= 1 -> group(v, if (v >= 100) 2 else 4)
        else -> significant(v, 6)
    }

    /** Price in the user-selected style (home screen widgets). */
    fun price(v: Double, mode: NumberFormatMode): String = when (mode) {
        NumberFormatMode.AUTO -> price(v)
        NumberFormatMode.FULL -> when {
            v <= 0.0 -> "—"
            v >= 1 -> group(v, 2)
            else -> significant(v, 6)
        }
        // Intero tondo senza separatori: pensato per testi molto grandi.
        NumberFormatMode.WHOLE -> when {
            v <= 0.0 -> "—"
            v >= 1 -> v.roundToLong().toString()
            else -> significant(v, 4).replace(",", "")
        }
        NumberFormatMode.COMPACT -> compactShort(v)
    }

    /** Short compact form for big text: 97.4k · 1.2M · 345 · 0.000012 */
    private fun compactShort(v: Double): String {
        if (v <= 0) return "—"
        return if (v >= 1000) {
            val exp = (ln(v) / ln(1000.0)).toInt().coerceIn(1, 3)
            val scaled = v / 1000.0.pow(exp)
            val suffix = listOf("", "k", "M", "B")[exp]
            group(scaled, if (scaled >= 100) 0 else 1) + suffix
        } else {
            significant(v, 4)
        }
    }

    fun percent(v: Double): String =
        (if (v >= 0) "+" else "−") + group(abs(v), 2) + "%"

    /** Compact volume: 12.4B · 850M · 12.3k. */
    fun compact(v: Double): String {
        if (v <= 0) return "—"
        val exp = (ln(v) / ln(1000.0)).toInt().coerceIn(0, 4)
        val scaled = v / 1000.0.pow(exp)
        val suffix = when (exp) {
            0 -> ""
            1 -> "k"
            2 -> "M"
            3 -> "B"
            else -> "T"
        }
        return group(scaled, if (scaled >= 100) 1 else 2) + suffix
    }

    private fun group(v: Double, decimals: Int): String =
        String.format(Locale.US, "%,.${decimals}f", v)

    private fun significant(v: Double, sig: Int): String {
        if (v == 0.0) return "0"
        val magnitude = log10(abs(v)).toInt()
        val decimals = (sig - 1 - magnitude).coerceIn(0, 10)
        return group(v, decimals)
    }
}
