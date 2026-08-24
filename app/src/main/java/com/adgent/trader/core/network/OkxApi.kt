package com.adgent.trader.core.network

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Endpoint REST pubblici OKX V5 (sola lettura, no API key). Base: www.okx.com.
 * L'intero mercato SPOT arriva in 1 chiamata (`market/tickers`); le candele
 * per-simbolo. Simboli nativi "BTC-USDT" (coppia canonica con "-").
 */
interface OkxApi {

    @GET("api/v5/public/time")
    suspend fun time(): OkxTimeResponse

    @GET("api/v5/market/tickers")
    suspend fun tickers(@Query("instType") instType: String = "SPOT"): OkxTickersResponse

    @GET("api/v5/public/instruments")
    suspend fun instruments(@Query("instType") instType: String = "SPOT"): OkxInstrumentsResponse

    /** Candele: data = [[ts,o,h,l,c,vol,volCcy,volCcyQuote,confirm], ...] (decrescente). */
    @GET("api/v5/market/candles")
    suspend fun candles(
        @Query("instId") instId: String,
        @Query("bar") bar: String,
        @Query("limit") limit: Int = 300,
    ): OkxCandlesResponse
}

@kotlinx.serialization.Serializable
data class OkxTimeResponse(val code: String = "", val ts: String = "")

@kotlinx.serialization.Serializable
data class OkxInstrumentsResponse(
    val code: String = "",
    val data: List<OkxInstrumentDto> = emptyList(),
)

@kotlinx.serialization.Serializable
data class OkxInstrumentDto(
    val instId: String = "",
    val state: String = "",
)

@kotlinx.serialization.Serializable
data class OkxTickersResponse(
    val code: String = "",
    val data: List<OkxTickerDto> = emptyList(),
)

@kotlinx.serialization.Serializable
data class OkxTickerDto(
    val instId: String = "",
    val last: String = "",
    /** Prezzo 24h fa (OKX lo espone già: base per la variazione). */
    val open24h: String = "",
    val high24h: String = "",
    val low24h: String = "",
    /** Volume 24h in valuta quota (equivale al quoteVolume Binance). */
    val volCcy24h: String = "",
    val vol24h: String = "",
)

@kotlinx.serialization.Serializable
data class OkxCandlesResponse(
    val code: String = "",
    val data: List<List<String>> = emptyList(),
)
