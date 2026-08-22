package com.adgent.trader.core.network

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.SymbolInfo
import com.adgent.trader.core.model.Ticker
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Endpoint REST pubblici Binance (sola lettura dati di mercato).
 * Nessuna API key richiesta. Base URL default: data-api.binance.vision
 * (mirror ufficiale solo-market-data; api.binance.com come fallback).
 */
interface BinanceApi {

    @GET("api/v3/ping")
    suspend fun ping()

    @GET("api/v3/exchangeInfo")
    suspend fun exchangeInfo(
        @Query("permissions") permissions: String = "SPOT",
    ): ExchangeInfoDto

    /** Istantanee 24h per i simboli richiesti (max ~100 per chiamata). */
    @GET("api/v3/ticker/24hr")
    suspend fun tickers24h(@Query("symbols") symbols: String): List<Ticker24hDto>

    @GET("api/v3/klines")
    suspend fun klines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 500,
    ): List<JsonArray>
}

// ---------- DTO (Binance restituisce numeri come stringhe) ----------

@kotlinx.serialization.Serializable
data class Ticker24hDto(
    val symbol: String,
    val lastPrice: String,
    val openPrice: String,
    val highPrice: String,
    val lowPrice: String,
    val quoteVolume: String,
    val priceChangePercent: String,
)

@kotlinx.serialization.Serializable
data class ExchangeInfoDto(
    val symbols: List<SymbolEntryDto>,
)

@kotlinx.serialization.Serializable
data class SymbolEntryDto(
    val symbol: String,
    val status: String,
    val baseAsset: String,
    val quoteAsset: String,
)

// ---------- Mapper DTO → modello ----------

fun Ticker24hDto.toModel(): Ticker = Ticker(
    symbol = symbol,
    price = lastPrice.toDoubleOrNull() ?: 0.0,
    open24h = openPrice.toDoubleOrNull() ?: 0.0,
    high24h = highPrice.toDoubleOrNull() ?: 0.0,
    low24h = lowPrice.toDoubleOrNull() ?: 0.0,
    quoteVolume24h = quoteVolume.toDoubleOrNull() ?: 0.0,
    changePercent24h = priceChangePercent.toDoubleOrNull() ?: 0.0,
)

fun SymbolEntryDto.toModel(): SymbolInfo? =
    if (status == "TRADING") SymbolInfo(symbol, baseAsset, quoteAsset) else null

fun JsonArray.toKline(): Kline = Kline(
    openTime = this[0].jsonPrimitive.content.toLong(),
    open = this[1].jsonPrimitive.content.toDouble(),
    high = this[2].jsonPrimitive.content.toDouble(),
    low = this[3].jsonPrimitive.content.toDouble(),
    close = this[4].jsonPrimitive.content.toDouble(),
    volume = this[5].jsonPrimitive.content.toDouble(),
    closeTime = this[6].jsonPrimitive.content.toLong(),
)

/** Serializza la lista simboli nel formato atteso da ?symbols=["A","B"]. */
fun symbolsParam(symbols: Collection<String>): String =
    symbols.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
