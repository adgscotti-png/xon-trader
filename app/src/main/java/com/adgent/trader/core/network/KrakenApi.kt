package com.adgent.trader.core.network

import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Endpoint REST pubblici Kraken (sola lettura, no API key). Nessun bulk:
 * Ticker e OHLC vanno per-simbolo (coppia nativa es. "XBTUSD").
 * Risposte dinamiche (chiavi = coppia) → si ritorna JsonObject e si parsano
 * a mano nell'adapter. Rate limit peso-based ~100/min: il RateLimiter lo rispetta.
 */
interface KrakenApi {

    @GET("0/public/Time")
    suspend fun time(): JsonObject

    @GET("0/public/AssetPairs")
    suspend fun assetPairs(): JsonObject

    @GET("0/public/Ticker")
    suspend fun ticker(@Query("pair") pair: String): JsonObject

    @GET("0/public/OHLC")
    suspend fun ohlc(
        @Query("pair") pair: String,
        @Query("interval") interval: String,
    ): JsonObject
}
