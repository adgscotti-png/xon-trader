package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.network.OkxApi
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Adapter OKX V5 sul port [MarketDataProvider].
 * REST: www.okx.com/api/v5 — intero spot in 1 chiamata (`market/tickers`).
 * WS: wss://ws.okx.com:8443/ws/v5/public ch. `tickers` per-simbolo; richiede
 * ping applicativo ogni ~30s (client invia "ping", server risponde "pong",
 * e il server a sua volta manda "ping" a cui rispondere "pong").
 * Simboli nativi "BTC-USDT" (coppia canonica separata da "-").
 */
class OkxProvider(
    private val api: OkxApi,
    client: OkHttpClient,
    mapper: SymbolMapper,
) : BaseMarketProvider(
    id = ProviderId.OKX,
    displayName = "OKX",
    rateLimit = RateLimit(maxRequests = 20, windowMs = 2000),
    mapper = mapper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ws = OkxStream(client)

    private fun canonical(symbol: String): String? =
        mapper.toCanonical(ProviderId.OKX, symbol)?.compact

    private fun nativeOf(symbol: String): String? {
        val pair = mapper.fromCompact(symbol) ?: return null
        return mapper.toProviderSymbol(ProviderId.OKX, pair)
    }

    override suspend fun ping(): Boolean =
        runCatching { guarded { api.time() }; true }.getOrDefault(false)

    override suspend fun refreshCatalog(): List<ProviderSymbol> = guarded {
        api.instruments().data.mapNotNull { d ->
            if (d.state != "live") return@mapNotNull null
            val pair = mapper.toCanonical(ProviderId.OKX, d.instId) ?: return@mapNotNull null
            ProviderSymbol(ProviderId.OKX, pair, d.instId)
        }
    }

    override suspend fun tickers24h(symbols: Collection<String>): List<Ticker> {
        val wanted = symbols.distinct().mapNotNull { canonical(it) }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return allSnapshot().filter { it.symbol in wanted }
    }

    override suspend fun tickers24hAll(): List<Ticker> = allSnapshot()

    private suspend fun allSnapshot(): List<Ticker> {
        val now = System.currentTimeMillis()
        if (now - allFetchedAt < SNAPSHOT_TTL_MS) return allResult
        val fresh = guarded { api.tickers().data }.mapNotNull { d ->
            canonical(d.instId)?.let { c ->
                val price = d.last.toDoubleOrNull() ?: 0.0
                val open = d.open24h.toDoubleOrNull() ?: 0.0
                Ticker(
                    symbol = c,
                    price = price,
                    open24h = open,
                    high24h = d.high24h.toDoubleOrNull() ?: 0.0,
                    low24h = d.low24h.toDoubleOrNull() ?: 0.0,
                    quoteVolume24h = d.volCcy24h.toDoubleOrNull() ?: 0.0,
                    changePercent24h = if (open > 0) (price - open) / open * 100.0 else 0.0,
                )
            }
        }
        if (fresh.isNotEmpty()) {
            allResult = fresh
            allFetchedAt = System.currentTimeMillis()
        }
        return allResult
    }

    override suspend fun klines(symbol: String, tf: Timeframe, limit: Int): List<Kline> {
        val n = nativeOf(symbol) ?: return emptyList()
        return guarded { api.candles(n, tf.intervalFor(ProviderId.OKX), limit).data }
            .mapNotNull { row ->
                // [ts, open, high, low, close, vol, volCcy, volCcyQuote, confirm]
                val t = row.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                Kline(
                    openTime = t,
                    open = row.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                    high = row.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
                    low = row.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
                    close = row.getOrNull(4)?.toDoubleOrNull() ?: 0.0,
                    volume = row.getOrNull(5)?.toDoubleOrNull() ?: 0.0,
                    closeTime = t,
                )
            }
            // OKX restituisce le candele dalla più recente: ordine crescente per il grafico.
            .sortedBy { it.openTime }
            .takeLast(limit)
    }

    override fun tickFlow(): Flow<List<PriceTick>> = ws.ticks
    override fun connectStream() = ws.connect()
    override fun disconnectStream() = ws.disconnect()
    override fun subscribeStream(symbols: Collection<String>) = ws.subscribe(symbols)
    override fun unsubscribeStream(symbols: Collection<String>) = ws.unsubscribe(symbols)

    private inner class OkxStream(
        client: OkHttpClient,
    ) : ProviderWebSocket(WS_URL, client) {
        override fun subscribeMessage(symbols: Set<String>): String? {
            val args = symbols.mapNotNull { nativeOf(it)?.let { n ->
                buildJsonObject { put("channel", "tickers"); put("instId", n) }
            } }
            if (args.isEmpty()) return null
            return buildJsonObject {
                put("op", "subscribe")
                put("args", JsonArray(args))
            }.toString()
        }

        override fun unsubscribeMessage(symbols: Set<String>): String? {
            val args = symbols.mapNotNull { nativeOf(it)?.let { n ->
                buildJsonObject { put("channel", "tickers"); put("instId", n) }
            } }
            if (args.isEmpty()) return null
            return buildJsonObject {
                put("op", "unsubscribe")
                put("args", JsonArray(args))
            }.toString()
        }

        override fun keepAliveIntervalMs(): Long = 20_000
        override fun keepAliveMessage(): String = "\"ping\""

        override fun controlReply(text: String): String? =
            if (text.trim() == "\"ping\"") "\"pong\"" else null

        override fun parseFrame(text: String): List<PriceTick> {
            // {"arg":{"channel":"tickers","instId":"BTC-USDT"},"data":[{instId,last,open24h,...}]}
            val root = json.parseToJsonElement(text).jsonObject
            if (root["arg"]?.jsonObject?.get("channel")?.jsonPrimitive?.content != "tickers") {
                return emptyList()
            }
            val data = root["data"] ?: return emptyList()
            return data.jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val raw = o["instId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val c = canonical(raw) ?: return@mapNotNull null
                PriceTick(
                    symbol = c,
                    provider = ProviderId.OKX,
                    price = o["last"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    open24h = o["open24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    high24h = o["high24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    low24h = o["low24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    quoteVolume24h = o["volCcy24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            }
        }
    }

    companion object {
        const val WS_URL = "wss://ws.okx.com:8443/ws/v5/public"
        private const val SNAPSHOT_TTL_MS = 30_000L
    }

    @Volatile private var allResult: List<Ticker> = emptyList()
    @Volatile private var allFetchedAt: Long = 0L
}
