package com.adgent.trader.ui.chart

import com.adgent.trader.core.model.Kline

/** Medie mobili semplici per gli overlay del grafico. */
object Indicators {

    /** SMA calcolata sull'intera serie; null finché la finestra non è piena. */
    fun sma(closes: List<Double>, period: Int): List<Double?> {
        val out = ArrayList<Double?>(closes.size)
        var sum = 0.0
        for (i in closes.indices) {
            sum += closes[i]
            if (i >= period) sum -= closes[i - period]
            out.add(if (i >= period - 1) sum / period else null)
        }
        return out
    }

    fun maOverlays(klines: List<Kline>): Map<Int, List<Double?>> = mapOf(
        7 to sma(klines.map { it.close }, 7),
        25 to sma(klines.map { it.close }, 25),
        99 to sma(klines.map { it.close }, 99),
    )
}
