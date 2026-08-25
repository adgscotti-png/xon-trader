package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Timeframe

/** Intervallo candele nel formato dell'exchange per l'endpoint klines. */
fun Timeframe.intervalFor(provider: ProviderId): String = when (provider) {
    ProviderId.BINANCE -> binanceInterval
    ProviderId.BYBIT -> when (this) {
        Timeframe.M1 -> "1"; Timeframe.M5 -> "5"; Timeframe.M15 -> "15"; Timeframe.H1 -> "60"
        Timeframe.H4 -> "240"; Timeframe.D1 -> "D"; Timeframe.W1 -> "W"; Timeframe.MO -> "M"
    }
    ProviderId.KRAKEN -> when (this) {
        Timeframe.M1 -> "1"; Timeframe.M5 -> "5"; Timeframe.M15 -> "15"; Timeframe.H1 -> "60"
        Timeframe.H4 -> "240"; Timeframe.D1 -> "1440"; Timeframe.W1 -> "10080"; Timeframe.MO -> "21600"
    }
    ProviderId.COINBASE -> when (this) {
        Timeframe.M1 -> "60"; Timeframe.M5 -> "300"; Timeframe.M15 -> "900"; Timeframe.H1 -> "3600"
        Timeframe.H4 -> "14400"; Timeframe.D1 -> "86400"; Timeframe.W1 -> "604800"; Timeframe.MO -> "2592000"
    }
    ProviderId.OKX -> when (this) {
        Timeframe.M1 -> "1m"; Timeframe.M5 -> "5m"; Timeframe.M15 -> "15m"; Timeframe.H1 -> "1H"
        Timeframe.H4 -> "4H"; Timeframe.D1 -> "1D"; Timeframe.W1 -> "1W"; Timeframe.MO -> "1M"
    }
    ProviderId.BITFINEX -> when (this) {
        Timeframe.M1 -> "1m"; Timeframe.M5 -> "5m"; Timeframe.M15 -> "15m"; Timeframe.H1 -> "1h"
        Timeframe.H4 -> "4h"; Timeframe.D1 -> "1D"; Timeframe.W1 -> "1W"; Timeframe.MO -> "1M"
    }
    ProviderId.KUCOIN -> when (this) {
        Timeframe.M1 -> "1min"; Timeframe.M5 -> "5min"; Timeframe.M15 -> "15min"; Timeframe.H1 -> "1hour"
        Timeframe.H4 -> "4hour"; Timeframe.D1 -> "1day"; Timeframe.W1 -> "1week"; Timeframe.MO -> "1month"
    }
}
