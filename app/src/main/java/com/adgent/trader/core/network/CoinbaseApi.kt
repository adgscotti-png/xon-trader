package com.adgent.trader.core.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Endpoint REST pubblici Coinbase Exchange (sola lettura, no API key).
 * Base: api.exchange.coinbase.com. Simboli nativi "BTC-USD". Nessun bulk per i
 * ticker 24h → stats per-simbolo (pattern curato, come Kraken). Granularity in
 * secondi.
 */
interface CoinbaseApi {

    @GET("products")
    suspend fun products(): List<CoinbaseProductDto>

    /** Stats 24h di un prodotto (open/high/low/volume/last). */
    @GET("products/{id}/stats")
    suspend fun stats(@Path("id") id: String): CoinbaseStatsDto

    /** Candele: [[time, low, high, open, close, volume], ...] (decrescente, NUMERI). */
    @GET("products/{id}/candles")
    suspend fun candles(
        @Path("id") id: String,
        @Query("granularity") granularity: Int,
        @Query("limit") limit: Int = 300,
    ): List<List<Double>>
}

@kotlinx.serialization.Serializable
data class CoinbaseProductDto(
    val id: String = "",
    val base_currency: String = "",
    val quote_currency: String = "",
    val status: String = "",
)

@kotlinx.serialization.Serializable
data class CoinbaseStatsDto(
    val open: String = "",
    val high: String = "",
    val low: String = "",
    val volume: String = "",
    val last: String = "",
)
