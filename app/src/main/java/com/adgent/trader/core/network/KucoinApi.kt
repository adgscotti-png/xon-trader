package com.adgent.trader.core.network

import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Endpoint REST pubblici KuCoin (sola lettura, no API key). Base: api.kucoin.com.
 * L'intero mercato arriva in 1 chiamata (`market/allTickers`). Il WS richiede un
 * token da `POST /bullet-public` (endpoint + token + pingInterval).
 * Simboli nativi "BTC-USDT".
 */
interface KucoinApi {

    @GET("api/v1/market/allTickers")
    suspend fun allTickers(): KucoinAllTickersResponse

    @GET("api/v1/timestamp")
    suspend fun timestamp(): KucoinTimestampResponse

    /** Token WS: data.token + data.instanceServers[].endpoint/pingInterval. */
    @POST("api/v1/bullet-public")
    suspend fun bulletPublic(): KucoinBulletResponse

    /** Candele: data = [[time,open,close,high,low,volume,turnover], ...] (decrescente). */
    @GET("api/v1/market/candles")
    suspend fun candles(
        @retrofit2.http.Query("type") type: String,
        @retrofit2.http.Query("symbol") symbol: String,
    ): KucoinCandlesResponse
}

@kotlinx.serialization.Serializable
data class KucoinTimestampResponse(
    val code: String = "",
    val data: Long = 0,
)

@kotlinx.serialization.Serializable
data class KucoinAllTickersResponse(
    val code: String = "",
    val data: KucoinTickerList = KucoinTickerList(),
)

@kotlinx.serialization.Serializable
data class KucoinTickerList(
    val time: Long = 0,
    val ticker: List<KucoinTickerDto> = emptyList(),
)

@kotlinx.serialization.Serializable
data class KucoinTickerDto(
    val symbol: String = "",
    val last: String = "",
    /** Variazione 24h come RAPPORTO (0.0012 = 0.12%): ×100 per la percentuale. */
    val changeRate: String = "",
    val open: String = "",
    val high: String = "",
    val low: String = "",
    /** Volume 24h in valuta quota. */
    val volValue: String = "",
    val vol: String = "",
)

@kotlinx.serialization.Serializable
data class KucoinBulletResponse(
    val code: String = "",
    val data: KucoinBulletData = KucoinBulletData(),
)

@kotlinx.serialization.Serializable
data class KucoinBulletData(
    val token: String = "",
    val instanceServers: List<KucoinInstanceServer> = emptyList(),
)

@kotlinx.serialization.Serializable
data class KucoinInstanceServer(
    val endpoint: String = "",
    val protocol: String = "websocket",
    val pingInterval: Long = 18_000,
    val pingTimeout: Long = 10_000,
)

@kotlinx.serialization.Serializable
data class KucoinCandlesResponse(
    val code: String = "",
    val data: List<List<String>> = emptyList(),
)
