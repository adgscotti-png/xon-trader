package com.adgent.trader.core.provider

/**
 * Coppia canonica app-wide: base/quote con alias già risolti
 * (XBT→BTC, UST→USDT per Bitfinex). La chiave nel resto dell'app è
 * [compact] (es. "BTCUSDT"), identica per qualunque provider.
 */
data class CanonicalPair(val base: String, val quote: String) {
    /** Chiave leggibile, es. "BTC/USDT". */
    val key: String get() = "$base/$quote"
    /** Simbolo compatto senza separatore, es. "BTCUSDT". */
    val compact: String get() = base + quote
}

/**
 * Traduce tra coppie canoniche e simboli provider-specifici:
 * BINANCE/BYBIT "BTCUSDT" · OKX/KUCOIN/COINBASE "BTC-USDT" ·
 * BITFINEX "tBTCUST" (USDT=UST) · KRAKEN "XBTUSD" (base alias XBT→BTC).
 */
class SymbolMapper(
    private val baseAliases: Map<String, String> = mapOf("XBT" to "BTC"),
    private val quoteAliases: Map<String, String> = mapOf("UST" to "USDT"),
) {
    /** Costruisce una coppia canonica applicando gli alias di base (XBT→BTC). */
    fun canonical(base: String, quote: String): CanonicalPair =
        CanonicalPair(baseAliases[base] ?: base, quote)

    /** Quote offerte da un provider (Kraken non ha coppie USDT). */
    fun supportedQuotes(provider: ProviderId): Set<String> = when (provider) {
        ProviderId.KRAKEN -> setOf("USD", "USDC", "EUR", "GBP", "JPY", "BTC", "ETH")
        ProviderId.BITFINEX -> setOf("USD", "USDT", "EUR", "GBP", "JPY", "BTC", "ETH")
        else -> setOf("USDT", "USDC", "USD", "EUR", "BTC", "ETH")
    }

    /** Simbolo sul provider per una coppia canonica, o null se non supportata. */
    fun toProviderSymbol(provider: ProviderId, pair: CanonicalPair): String? {
        if (pair.quote !in supportedQuotes(provider)) return null
        return when (provider) {
            ProviderId.BINANCE, ProviderId.BYBIT -> pair.compact
            ProviderId.OKX, ProviderId.KUCOIN, ProviderId.COINBASE ->
                "${pair.base}-${pair.quote}"
            ProviderId.BITFINEX -> "t" + pair.base + (quoteAliases[pair.quote] ?: pair.quote)
            ProviderId.KRAKEN -> {
                val native = baseAliases.entries.firstOrNull { it.value == pair.base }?.key
                    ?: pair.base
                native + pair.quote
            }
        }
    }

    /** Risolve una stringa provider-specifica nella coppia canonica, o null. */
    fun toCanonical(provider: ProviderId, symbol: String): CanonicalPair? {
        val s = symbol.removePrefix("t")
        return when (provider) {
            ProviderId.BINANCE, ProviderId.BYBIT -> splitCompact(s)
            ProviderId.OKX, ProviderId.KUCOIN, ProviderId.COINBASE -> {
                val i = s.indexOf('-')
                if (i <= 0 || i == s.lastIndex) null
                else CanonicalPair(s.substring(0, i), s.substring(i + 1))
            }
            ProviderId.BITFINEX -> {
                val base = s.take(3)
                val rest = s.drop(3)
                if (rest.isEmpty()) null
                else CanonicalPair(base, quoteAliases[rest] ?: rest)
            }
            ProviderId.KRAKEN -> {
                val base = s.take(3)
                val quote = s.drop(3)
                if (quote.isEmpty()) null
                else CanonicalPair(baseAliases[base] ?: base, quote)
            }
        }
    }

    /** Scompone un simbolo CANONICO compatto (es. "BTCUSDT") nella coppia. */
    fun fromCompact(symbol: String): CanonicalPair? = splitCompact(symbol)

    /** Scompone un simbolo compatto (es. "BTCUSDT") usando le quote note. */
    private fun splitCompact(symbol: String): CanonicalPair? {
        val quote = supportedQuotes(ProviderId.BINANCE)
            .filter { it.length < symbol.length }
            .sortedByDescending { it.length }
            .firstOrNull { symbol.endsWith(it) } ?: return null
        val base = symbol.removeSuffix(quote)
        if (base.isEmpty()) return null
        return CanonicalPair(base, quote)
    }
}
