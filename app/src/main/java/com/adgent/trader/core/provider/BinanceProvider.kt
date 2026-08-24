package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.network.BinanceApi
import com.adgent.trader.core.network.symbolsParam
import com.adgent.trader.core.network.toKline
import com.adgent.trader.core.network.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter Binance sul port [MarketDataProvider].
 * REST: data-api.binance.vision · WS: !miniTicker@arr (stream globale, filtro
 * client-side via [ProviderWebSocket]). Simboli nativi == canonici (BTCUSDT).
 */
class BinanceProvider(
    private val api: BinanceApi,
    client: OkHttpClient,
    mapper: SymbolMapper,
) : BaseMarketProvider(
    id = ProviderId.BINANCE,
    displayName = "Binance",
    rateLimit = RateLimit(maxRequests = 60, windowMs = 60_000),
    mapper = mapper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ws = BinanceStream(client)

    private fun canonical(symbol: String): String? =
        mapper.toCanonical(ProviderId.BINANCE, symbol)?.compact

    override suspend fun ping(): Boolean =
        runCatching { guarded { api.ping() }; true }.getOrDefault(false)

    override suspend fun refreshCatalog(): List<ProviderSymbol> = guarded {
        api.exchangeInfo().symbols.mapNotNull { entry ->
            val pair = mapper.toCanonical(ProviderId.BINANCE, entry.symbol) ?: return@mapNotNull null
            ProviderSymbol(ProviderId.BINANCE, pair, entry.symbol)
        }
    }

    override suspend fun tickers24h(symbols: Collection<String>): List<Ticker> {
        val chunks = symbols.distinct().filter { canonical(it) != null }.chunked(80)
        val out = mutableListOf<Ticker>()
        for (chunk in chunks) {
            out += guarded { api.tickers24h(symbolsParam(chunk)) }
                .mapNotNull { dto -> canonical(dto.symbol)?.let { c -> dto.toModel().copy(symbol = c) } }
        }
        return out
    }

    override suspend fun tickers24hAll(): List<Ticker> = guarded {
        api.tickers24hAll().mapNotNull { dto ->
            canonical(dto.symbol)?.let { c -> dto.toModel().copy(symbol = c) }
        }
    }

    override suspend fun klines(symbol: String, tf: Timeframe, limit: Int): List<Kline> =
        guarded { api.klines(symbol, tf.intervalFor(ProviderId.BINANCE), limit).map { it.toKline() } }

    override fun tickFlow(): Flow<List<PriceTick>> = ws.ticks

    override fun connectStream() = ws.connect()
    override fun disconnectStream() = ws.disconnect()
    override fun subscribeStream(symbols: Collection<String>) = ws.subscribe(symbols)
    override fun unsubscribeStream(symbols: Collection<String>) = ws.unsubscribe(symbols)

    private inner class BinanceStream(
        client: OkHttpClient,
    ) : ProviderWebSocket(WS_URL, client) {
        override fun buildRequest(): Request =
            Request.Builder().url("$baseUrl/stream?streams=!miniTicker@arr").build()

        override fun subscribeMessage(symbols: Set<String>): String? = null
        override fun unsubscribeMessage(symbols: Set<String>): String? = null

        override fun parseFrame(text: String): List<PriceTick> {
            val root = json.parseToJsonElement(text).jsonObject
            val payload = root["data"] ?: return emptyList()
            val array = when (payload) {
                is JsonArray -> payload
                else -> listOf(payload)
            }
            return array.mapNotNull { el ->
                val o = el.jsonObject
                val raw = o["s"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val c = canonical(raw) ?: return@mapNotNull null
                PriceTick(
                    symbol = c,
                    provider = ProviderId.BINANCE,
                    price = o["c"]!!.jsonPrimitive.content.toDouble(),
                    open24h = o["o"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    high24h = o["h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    low24h = o["l"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    quoteVolume24h = o["q"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            }
        }
    }

    companion object {
        const val WS_URL = "wss://data-stream.binance.vision"
    }
}
