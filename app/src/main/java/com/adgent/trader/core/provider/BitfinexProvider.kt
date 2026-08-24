package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.network.BitfinexApi
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter Bitfinex v2 sul port [MarketDataProvider].
 * REST: api-pub.bitfinex.com — intero mercato in 1 chiamata (`tickers?symbols=ALL`),
 * oppure CSV di simboli per la watchlist. Simboli nativi "tBTCUST" (USDT=UST,
 * prefisso t). Il Ticker REST è un array eterogeneo: [sym, bid, ..., DAILY_CHANGE,
 * DAILY_CHANGE_REL, LAST, VOL(base), HIGH, LOW, ...] → indici 5/6/7/8/9/10.
 * WS: UN messaggio per simbolo (chanId→simbolo da "subscribed"), ping applicativo.
 */
class BitfinexProvider(
    private val api: BitfinexApi,
    client: OkHttpClient,
    mapper: SymbolMapper,
) : BaseMarketProvider(
    id = ProviderId.BITFINEX,
    displayName = "Bitfinex",
    rateLimit = RateLimit(maxRequests = 30, windowMs = 60_000),
    mapper = mapper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ws = BitfinexStream(client)

    private fun nativeOf(symbol: String): String? {
        val pair = mapper.fromCompact(symbol) ?: return null
        return mapper.toProviderSymbol(ProviderId.BITFINEX, pair)
    }

    private fun canonicalNative(native: String): String? {
        if (!native.startsWith("t")) return null
        return mapper.toCanonical(ProviderId.BITFINEX, native)?.compact
    }

    override suspend fun ping(): Boolean =
        runCatching { guarded { api.tickers("tBTCUSD") }; true }.getOrDefault(false)

    override suspend fun refreshCatalog(): List<ProviderSymbol> = guarded {
        api.tickers("ALL").mapNotNull { el ->
            val row = el as? JsonArray ?: return@mapNotNull null
            val sym = row.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!sym.startsWith("t")) return@mapNotNull null
            val pair = mapper.toCanonical(ProviderId.BITFINEX, sym) ?: return@mapNotNull null
            ProviderSymbol(ProviderId.BITFINEX, pair, sym)
        }
    }

    override suspend fun tickers24h(symbols: Collection<String>): List<Ticker> {
        val byNative = symbols.distinct()
            .mapNotNull { sym -> nativeOf(sym)?.let { it to sym } }
            .toMap()
        if (byNative.isEmpty()) return emptyList()
        return byNative.keys.chunked(30).flatMap { chunk ->
            runCatching {
                parseTickers(guarded { api.tickers(chunk.joinToString(",")) }, byNative)
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun tickers24hAll(): List<Ticker> = guarded {
        parseTickers(api.tickers("ALL"), null)
    }

    private fun parseTickers(arr: JsonArray, byNative: Map<String, String>?): List<Ticker> {
        val out = mutableListOf<Ticker>()
        for (el in arr) {
            val row = el as? JsonArray ?: continue
            val sym = row.getOrNull(0)?.jsonPrimitive?.content ?: continue
            if (!sym.startsWith("t")) continue
            val c = byNative?.get(sym) ?: canonicalNative(sym) ?: continue
            tickerFrom(row, c)?.let { out += it }
        }
        return out
    }

    private fun tickerFrom(row: JsonArray, symbol: String): Ticker? {
        fun f(i: Int): Double = row.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val price = f(7)
        if (price <= 0) return null
        val dailyChange = f(5)
        val open = price - dailyChange
        val baseVol = f(8)
        return Ticker(
            symbol = symbol,
            price = price,
            changePercent24h = f(6) * 100.0,
            high24h = f(9),
            low24h = f(10),
            quoteVolume24h = baseVol * price, // il ticker v2 non ha il quote volume: approssimazione
            open24h = open,
        )
    }

    override suspend fun klines(symbol: String, tf: Timeframe, limit: Int): List<Kline> {
        val n = nativeOf(symbol) ?: return emptyList()
        return guarded { api.candles(tf.intervalFor(ProviderId.BITFINEX), n, limit) }
            .mapNotNull { el ->
                val row = el as? JsonArray ?: return@mapNotNull null
                val t = row.getOrNull(0)?.jsonPrimitive?.content?.toLongOrNull()
                    ?: return@mapNotNull null
                fun v(i: Int) = row.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                Kline(
                    openTime = t,
                    open = v(1),
                    high = v(3),
                    low = v(4),
                    close = v(2),
                    volume = v(5),
                    closeTime = t,
                )
            }
            .sortedBy { it.openTime }
            .takeLast(limit)
    }

    override fun tickFlow(): Flow<List<PriceTick>> = ws.ticks
    override fun connectStream() = ws.connect()
    override fun disconnectStream() = ws.disconnect()
    override fun subscribeStream(symbols: Collection<String>) = ws.subscribe(symbols)
    override fun unsubscribeStream(symbols: Collection<String>) = ws.unsubscribe(symbols)

    private inner class BitfinexStream(
        client: OkHttpClient,
    ) : ProviderWebSocket(WS_URL, client) {
        private val chanToSymbol = ConcurrentHashMap<Int, String>()

        override fun subscribeMessages(symbols: Set<String>): List<String> =
            symbols.mapNotNull { nativeOf(it) }.map { n ->
                buildJsonObject {
                    put("event", "subscribe")
                    put("channel", "ticker")
                    put("symbol", n)
                }.toString()
            }

        override fun unsubscribeMessages(symbols: Set<String>): List<String> =
            symbols.mapNotNull { nativeOf(it) }.map { n ->
                buildJsonObject {
                    put("event", "unsubscribe")
                    put("channel", "ticker")
                    put("symbol", n)
                }.toString()
            }

        override fun keepAliveIntervalMs(): Long = 20_000
        override fun keepAliveMessage(): String = """{"event":"ping"}"""

        override fun controlReply(text: String): String? {
            val el = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return null
            if (el["event"]?.jsonPrimitive?.content == "ping") return """{"event":"pong"}"""
            return null
        }

        override fun parseFrame(text: String): List<PriceTick> {
            val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
            return when (el) {
                is JsonObject -> {
                    // {"event":"subscribed","channel":"ticker","chanId":N,"symbol":"tBTCUST"}
                    if (el["event"]?.jsonPrimitive?.content == "subscribed") {
                        val chan = el["chanId"]?.jsonPrimitive?.content?.toIntOrNull()
                        val sym = el["symbol"]?.jsonPrimitive?.content
                        val c = sym?.let { canonicalNative(it) }
                        if (chan != null && c != null) chanToSymbol[chan] = c
                    }
                    emptyList<PriceTick>()
                }
                is JsonArray -> {
                    val chan = el.getOrNull(0)?.jsonPrimitive?.content?.toIntOrNull()
                        ?: return emptyList()
                    val c = chanToSymbol[chan] ?: return emptyList()
                    val data = el.getOrNull(1) ?: return emptyList()
                    if (data is JsonPrimitive && data.content == "hb") return emptyList()
                    val row = data as? JsonArray ?: return emptyList()
                    fun f(i: Int) = row.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                    val price = f(7)
                    val dailyChange = f(5)
                    val baseVol = f(8)
                    listOf(
                        PriceTick(
                            symbol = c,
                            provider = ProviderId.BITFINEX,
                            price = price,
                            open24h = price - dailyChange,
                            high24h = f(9),
                            low24h = f(10),
                            quoteVolume24h = baseVol * price,
                        )
                    )
                }
                else -> emptyList<PriceTick>()
            }
        }
    }

    companion object {
        const val WS_URL = "wss://api-pub.bitfinex.com/ws/2"
    }
}
