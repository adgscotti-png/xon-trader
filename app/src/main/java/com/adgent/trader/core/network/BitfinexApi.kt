package com.adgent.trader.core.network

import kotlinx.serialization.json.JsonArray
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Endpoint REST pubblici Bitfinex v2 (sola lettura, no API key).
 * Base: api-pub.bitfinex.com. Simboli nativi "tBTCUST" (USDT=UST, prefisso t).
 * Le risposte sono array eterogenei → JsonArray parsato a mano nell'adapter.
 */
interface BitfinexApi {

    /** Intero mercato in 1 chiamata: symbols=ALL → [[sym, bid, ..., last, vol, high, low, ...], ...]. */
    @GET("v2/tickers")
    suspend fun tickers(@Query("symbols") symbols: String = "ALL"): JsonArray

    /** Candele: [time, open, close, high, low, volume] — attenzione: close prima di high/low. */
    @GET("v2/candles/trade:{timeframe}:{symbol}/hist")
    suspend fun candles(
        @Path("timeframe") timeframe: String,
        @Path("symbol") symbol: String,
        @Query("limit") limit: Int = 500,
    ): JsonArray
}
