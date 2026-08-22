package com.adgent.trader.ui.chart

import com.adgent.trader.core.model.Kline

/** Medie mobili e oscillatori per gli overlay del grafico. */
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

    /** EMA con seeding SMA del primo valore pieno (convenzione standard). */
    fun ema(closes: List<Double>, period: Int): List<Double?> {
        val out: MutableList<Double?> = MutableList(closes.size) { null }
        if (closes.isEmpty() || closes.size < period) return out
        val k = 2.0 / (period + 1)
        var seed = 0.0
        repeat(period) { seed += closes[it] }
        var prev = seed / period
        out[period - 1] = prev
        for (i in period until closes.size) {
            prev = closes[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Bollinger: banda superiore, media, banda inferiore (SMA ± deviazioni). */
    fun bollinger(
        closes: List<Double>,
        period: Int = 20,
        deviations: Double = 2.0,
    ): Triple<List<Double?>, List<Double?>, List<Double?>> {
        val mid = sma(closes, period)
        val upper = ArrayList<Double?>(closes.size)
        val lower = ArrayList<Double?>(closes.size)
        for (i in closes.indices) {
            val m = mid[i]
            if (m == null || i < period - 1) {
                upper.add(null); lower.add(null); continue
            }
            var sqSum = 0.0
            for (j in i - period + 1..i) { val d = closes[j] - m; sqSum += d * d }
            val sd = kotlin.math.sqrt(sqSum / period)
            upper.add(m + deviations * sd)
            lower.add(m - deviations * sd)
        }
        return Triple(upper, mid, lower)
    }

    /**
     * RSI di Wilder (smoothing esponenziale delle medie guadagno/perdita).
     * null finché la finestra non è piena.
     */
    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        val out = ArrayList<Double?>(closes.size)
        if (closes.size <= period) { repeat(closes.size) { out.add(null) }; return out }
        var gainAvg = 0.0
        var lossAvg = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gainAvg += d else lossAvg -= d
        }
        gainAvg /= period; lossAvg /= period
        repeat(period) { out.add(null) }
        fun rsValue(g: Double, l: Double): Double =
            if (l == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + g / l)
        out.add(rsValue(gainAvg, lossAvg))
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            gainAvg = (gainAvg * (period - 1) + maxOf(d, 0.0)) / period
            lossAvg = (lossAvg * (period - 1) + maxOf(-d, 0.0)) / period
            out.add(rsValue(gainAvg, lossAvg))
        }
        return out
    }

    /**
     * MACD (12, 26, signal 9): linea MACD, linea segnale, istogramma.
     * Le liste hanno la stessa lunghezza della serie; null dove non definito.
     */
    data class Macd(
        val macd: List<Double?>,
        val signal: List<Double?>,
        val histogram: List<Double?>,
    )

    fun macd(
        closes: List<Double>,
        fast: Int = 12,
        slow: Int = 26,
        signalPeriod: Int = 9,
    ): Macd {
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val macdLine = ArrayList<Double?>(closes.size)
        for (i in closes.indices) {
            val f = emaFast[i]; val s = emaSlow[i]
            macdLine.add(if (f != null && s != null) f - s else null)
        }
        // Signal = EMA dei soli valori definiti della linea MACD.
        val definedIdx = macdLine.withIndex().filter { it.value != null }.map { it.index }
        val definedVals = definedIdx.map { macdLine[it]!! }
        val sigVals = ema(definedVals, signalPeriod)
        val signal: MutableList<Double?> = MutableList(closes.size) { null }
        definedIdx.forEachIndexed { k, idx -> signal[idx] = sigVals[k] }
        val hist = ArrayList<Double?>(closes.size)
        for (i in closes.indices) {
            val m = macdLine[i]; val s = signal[i]
            hist.add(if (m != null && s != null) m - s else null)
        }
        return Macd(macdLine, signal, hist)
    }

    fun maOverlays(klines: List<Kline>): Map<Int, List<Double?>> = mapOf(
        7 to sma(klines.map { it.close }, 7),
        25 to sma(klines.map { it.close }, 25),
        99 to sma(klines.map { it.close }, 99),
    )

    fun emaOverlays(klines: List<Kline>): Map<Int, List<Double?>> = mapOf(
        12 to ema(klines.map { it.close }, 12),
        26 to ema(klines.map { it.close }, 26),
    )
}

