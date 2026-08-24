package com.adgent.trader.core.network

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Endpoint REST pubblici Bybit V5 (sola lettura dati di mercato, no API key).
 * Base URL: https://api.bybit.com/ · Spot: il catalogo e l'intero mercato
 * arrivano in una singola chiamata, i klines per-simbolo.
 */
interface BybitApi {

    @GET("v5/market/time")
    suspend fun time(): BybitTimeResponse

    @GET("v5/market/instruments-info")
    suspend fun instruments(@Query("category") category: String = "spot"): BybitInstrumentsResponse

    /** Istantanee 24h di TUTTO il mercato spot (una chiamata: è anche il bulk). */
    @GET("v5/market/tickers")
    suspend fun tickers(@Query("category") category: String = "spot"): BybitTickersResponse

    @GET("v5/market/kline")
    suspend fun klines(
        @Query("category") category: String = "spot",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
    ): BybitKlinesResponse
}

// ---------- DTO (numeri come stringhe; default per campi assenti) ----------

@kotlinx.serialization.Serializable
data class BybitTimeResponse(val retCode: Int = 0, val result: BybitTime = BybitTime())

@kotlinx.serialization.Serializable
data class BybitTime(val timeSecond: String = "")

@kotlinx.serialization.Serializable
data class BybitInstrumentsResponse(
    val retCode: Int = 0,
    val result: BybitInstrumentList = BybitInstrumentList(),
)

@kotlinx.serialization.Serializable
data class BybitInstrumentList(val list: List<BybitInstrumentDto> = emptyList())

@kotlinx.serialization.Serializable
data class BybitInstrumentDto(
    val symbol: String = "",
    val status: String = "",
)

@kotlinx.serialization.Serializable
data class BybitTickersResponse(
    val retCode: Int = 0,
    val result: BybitTickerList = BybitTickerList(),
)

@kotlinx.serialization.Serializable
data class BybitTickerList(val list: List<BybitTickerDto> = emptyList())

@kotlinx.serialization.Serializable
data class BybitTickerDto(
    val symbol: String = "",
    val lastPrice: String = "",
    /** Prezzo ~24h fa: base per la variazione (il pcnt di Bybit è un rapporto). */
    val prevPrice24h: String = "",
    val highPrice24h: String = "",
    val lowPrice24h: String = "",
    /** Volume in valuta quota (equivalente del quoteVolume Binance). */
    val turnover24h: String = "",
    /** Variazione 24h come RAPPORTO (0.0012 = 0.12%): ×100 per la percentuale. */
    val price24hPcnt: String = "",
)

@kotlinx.serialization.Serializable
data class BybitKlinesResponse(
    val retCode: Int = 0,
    val result: BybitKlineList = BybitKlineList(),
)

@kotlinx.serialization.Serializable
data class BybitKlineList(val list: List<BybitKlineDto> = emptyList())

@kotlinx.serialization.Serializable
data class BybitKlineDto(
    val startTime: String = "",
    val open: String = "",
    val high: String = "",
    val low: String = "",
    val close: String = "",
    val volume: String = "",
)
