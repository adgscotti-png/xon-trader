package com.adgent.trader.core.provider

/** Provider di dati di mercato con API pubbliche (senza chiave). */
enum class ProviderId(val label: String, val defaultPriority: Int) {
    BINANCE("Binance", 1),
    BYBIT("Bybit", 2),
    KRAKEN("Kraken", 3),
    COINBASE("Coinbase", 4),
    OKX("OKX", 5),
    BITFINEX("Bitfinex", 6),
    KUCOIN("KuCoin", 7),
    ;

    companion object {
        /** Nome persistito su Room/DataStore (stabile anche se cambia la label). */
        fun fromName(name: String?): ProviderId? =
            name?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}
