package com.adgent.trader.core.model

import com.adgent.trader.core.provider.ProviderId

/** Metadati di un simbolo scambiato (es. BTCUSDT → BTC/USDT). */
data class SymbolInfo(
    val symbol: String,
    val base: String,
    val quote: String,
)

/** Istantanea 24h di un simbolo (REST /ticker/24hr). */
data class Ticker(
    val symbol: String,
    val price: Double,
    val changePercent24h: Double,
    val high24h: Double,
    val low24h: Double,
    val quoteVolume24h: Double,
    val open24h: Double,
)

/** Candela OHLCV. */
data class Kline(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
)

/** Tick di prezzo in tempo reale (WebSocket, normalizzato sul provider). */
data class PriceTick(
    val symbol: String,
    val price: Double,
    val open24h: Double,
    val high24h: Double,
    val low24h: Double,
    val quoteVolume24h: Double,
    val provider: ProviderId = ProviderId.BINANCE,
) {
    val changePercent24h: Double
        get() = if (open24h > 0) (price - open24h) / open24h * 100.0 else 0.0
}

/** Timeframe supportati dal grafico. */
enum class Timeframe(val binanceInterval: String, val label: String, val defaultLimit: Int) {
    M1("1m", "1m", 1000),
    M15("15m", "15m", 96),
    H1("1h", "1h", 168),
    H4("4h", "4h", 180),
    D1("1d", "1d", 365),
    W1("1w", "1w", 260),
    MO("1M", "1M", 200),
    ;

    companion object {
        val DEFAULT = H1
    }
}

/** Tipi di avviso prezzo supportati. */
enum class AlertType(val label: String) {
    PRICE_ABOVE("Above"),
    PRICE_BELOW("Below"),
    PERCENT_UP("24h up ≥"),
    PERCENT_DOWN("24h down ≤"),
}
