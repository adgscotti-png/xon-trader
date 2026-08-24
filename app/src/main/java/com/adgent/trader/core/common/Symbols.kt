package com.adgent.trader.core.common

/** Quote note per estrarre la base da un simbolo provider-specifico. */
private val QUOTES = listOf("USDT", "USDC", "USD", "EUR", "GBP", "JPY", "BTC", "ETH")

/** Base asset leggibile da un simbolo di qualunque provider (alias risolti). */
fun baseOf(symbol: String): String {
    val s = symbol.removePrefix("t")
    val dash = s.indexOf('-')
    if (dash > 0) return s.substring(0, dash)
    val quote = QUOTES.firstOrNull { s.endsWith(it) && it.length < s.length } ?: return s
    val base = s.removeSuffix(quote)
    return if (base == "XBT") "BTC" else base
}
