package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.network.KucoinApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adapter KuCoin sul port [MarketDataProvider].
 * REST: api.kucoin.com — intero mercato in 1 chiamata (`market/allTickers`).
 * WS: endpoint+token da `POST /bullet-public` (endpoint variabile, token
 * obbligatorio); ch. `/market/ticker:{SYMBOL}`. È il SERVER a pingare
 * (pingInterval dal bullet, ~18s): il client deve rispondere "pong".
 * Simboli nativi "BTC-USDT".
 */
class KucoinProvider(
    private val api: KucoinApi,
    private val scope: CoroutineScope,
    client: OkHttpClient,
    mapper: SymbolMapper,
) : BaseMarketProvider(
    id = ProviderId.KUCOIN,
    displayName = "KuCoin",
    rateLimit = RateLimit(maxRequests = 10, windowMs = 1000),
    mapper = mapper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ws = KucoinStream(client)

    private fun canonical(symbol: String): String? =
        mapper.toCanonical(ProviderId.KUCOIN, symbol)?.compact

    private fun nativeOf(symbol: String): String? {
        val pair = mapper.fromCompact(symbol) ?: return null
        return mapper.toProviderSymbol(ProviderId.KUCOIN, pair)
    }

    override suspend fun ping(): Boolean =
        runCatching { guarded { api.timestamp() }; true }.getOrDefault(false)

    override suspend fun refreshCatalog(): List<ProviderSymbol> = guarded {
        api.allTickers().data.ticker.mapNotNull { d ->
            val pair = mapper.toCanonical(ProviderId.KUCOIN, d.symbol) ?: return@mapNotNull null
            ProviderSymbol(ProviderId.KUCOIN, pair, d.symbol)
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
        val fresh = guarded { api.allTickers().data.ticker }.mapNotNull { d ->
            canonical(d.symbol)?.let { c ->
                Ticker(
                    symbol = c,
                    price = d.last.toDoubleOrNull() ?: 0.0,
                    open24h = d.open.toDoubleOrNull() ?: 0.0,
                    high24h = d.high.toDoubleOrNull() ?: 0.0,
                    low24h = d.low.toDoubleOrNull() ?: 0.0,
                    quoteVolume24h = d.volValue.toDoubleOrNull() ?: 0.0,
                    changePercent24h = (d.changeRate.toDoubleOrNull() ?: 0.0) * 100.0,
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
        return guarded { api.candles(tf.intervalFor(ProviderId.KUCOIN), n).data }
            .mapNotNull { row ->
                // [time, open, close, high, low, volume, turnover] — close PRIMA di high/low
                val t = row.getOrNull(0)?.toLongOrNull()?.times(1000L) ?: return@mapNotNull null
                Kline(
                    openTime = t,
                    open = row.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                    high = row.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
                    low = row.getOrNull(4)?.toDoubleOrNull() ?: 0.0,
                    close = row.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
                    volume = row.getOrNull(5)?.toDoubleOrNull() ?: 0.0,
                    closeTime = t,
                )
            }
            .sortedBy { it.openTime }
            .takeLast(limit)
    }

    override fun tickFlow(): Flow<List<PriceTick>> = ws.ticks

    /** Il WS richiede il token da bullet-public: lo chiediamo una volta e poi apriamo. */
    override fun connectStream() {
        if (ws.handshakeDone) {
            ws.connect()
            return
        }
        scope.launch {
            val bullet = runCatching { guarded { api.bulletPublic() } }.getOrNull()
            if (bullet == null) return@launch
            val server = bullet.data.instanceServers.firstOrNull()
            ws.configure(
                endpoint = server?.endpoint?.takeIf { it.isNotBlank() } ?: DEFAULT_WS,
                token = bullet.data.token,
            )
            ws.connect()
        }
    }

    override fun disconnectStream() = ws.disconnect()
    override fun subscribeStream(symbols: Collection<String>) = ws.subscribe(symbols)
    override fun unsubscribeStream(symbols: Collection<String>) = ws.unsubscribe(symbols)

    private inner class KucoinStream(
        client: OkHttpClient,
    ) : ProviderWebSocket(DEFAULT_WS, client) {
        @Volatile private var wsEndpoint: String = DEFAULT_WS
        @Volatile private var wsToken: String = ""

        @Volatile var handshakeDone: Boolean = false
            private set

        fun configure(endpoint: String, token: String) {
            wsEndpoint = endpoint
            wsToken = token
            handshakeDone = true
        }

        override fun buildRequest(): Request =
            Request.Builder().url("$wsEndpoint?token=$wsToken").build()

        override fun subscribeMessage(symbols: Set<String>): String? {
            val topics = symbols.mapNotNull { nativeOf(it)?.let { n -> "/market/ticker:$n" } }
            if (topics.isEmpty()) return null
            return buildJsonObject {
                put("type", "subscribe")
                put("topic", topics.joinToString(","))
                put("privateChannel", JsonPrimitive(false))
                put("response", JsonPrimitive(true))
            }.toString()
        }

        override fun unsubscribeMessage(symbols: Set<String>): String? {
            val topics = symbols.mapNotNull { nativeOf(it)?.let { n -> "/market/ticker:$n" } }
            if (topics.isEmpty()) return null
            return buildJsonObject {
                put("type", "unsubscribe")
                put("topic", topics.joinToString(","))
                put("privateChannel", JsonPrimitive(false))
                put("response", JsonPrimitive(true))
            }.toString()
        }

        /** KuCoin è il server a pingare: rispondiamo "pong" con lo stesso id. */
        override fun controlReply(text: String): String? {
            val el = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return null
            if (el["type"]?.jsonPrimitive?.content != "ping") return null
            val id = el["id"]?.jsonPrimitive?.content
            if (id == null) return """{"type":"pong"}"""
            return buildJsonObject { put("type", "pong"); put("id", id) }.toString()
        }

        override fun parseFrame(text: String): List<PriceTick> {
            val root = json.parseToJsonElement(text).jsonObject
            if (root["type"]?.jsonPrimitive?.content != "message") return emptyList()
            val topic = root["topic"]?.jsonPrimitive?.content ?: return emptyList()
            val raw = topic.substringAfterLast(':')
            val c = canonical(raw) ?: return emptyList()
            val o = root["data"]?.jsonObject ?: return emptyList()
            val price = (o["price"] ?: o["lastTradedPrice"])?.jsonPrimitive?.content
                ?.toDoubleOrNull() ?: 0.0
            return listOf(
                PriceTick(
                    symbol = c,
                    provider = ProviderId.KUCOIN,
                    price = price,
                    open24h = o["open24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    high24h = o["high24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    low24h = o["low24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    quoteVolume24h = o["volValue"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            )
        }
    }

    companion object {
        const val DEFAULT_WS = "wss://ws-api.kucoin.com/endpoint"
        private const val SNAPSHOT_TTL_MS = 30_000L
    }

    @Volatile private var allResult: List<Ticker> = emptyList()
    @Volatile private var allFetchedAt: Long = 0L
}
