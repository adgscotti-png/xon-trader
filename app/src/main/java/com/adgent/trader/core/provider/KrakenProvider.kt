package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.network.KrakenApi
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Adapter Kraken sul port [MarketDataProvider].
 * REST: api.kraken.com/0/public — nessun bulk, MA il Ticker accetta più coppie
 * comma-separate (una chiamata per il ranking). Niente coppie USDT: quote
 * USD/USDC/EUR/... I simboli REST sono "XBTUSD" (base alias XBT→BTC), la WS usa
 * il wsname "XBT/USD". Il WS richiede ping applicativo ogni ~30s → [keepAlive].
 */
class KrakenProvider(
    private val api: KrakenApi,
    client: OkHttpClient,
    mapper: SymbolMapper,
) : BaseMarketProvider(
    id = ProviderId.KRAKEN,
    displayName = "Kraken",
    rateLimit = RateLimit(maxRequests = 80, windowMs = 60_000),
    mapper = mapper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ws = KrakenStream(client)

    private fun nativeOf(symbol: String): String? {
        val pair = mapper.fromCompact(symbol) ?: return null
        return mapper.toProviderSymbol(ProviderId.KRAKEN, pair)
    }

    /** wsname per la WS (es. "XBT/USD", "USDC/USD"). */
    private fun wsnameOf(symbol: String): String? {
        val pair = mapper.fromCompact(symbol) ?: return null
        val native = mapper.toProviderSymbol(ProviderId.KRAKEN, pair) ?: return null
        return native.removeSuffix(pair.quote) + "/" + pair.quote
    }

    override suspend fun ping(): Boolean =
        runCatching { guarded { api.time() }; true }.getOrDefault(false)

    override suspend fun refreshCatalog(): List<ProviderSymbol> = guarded {
        val r = parseResult(api.assetPairs()) ?: return@guarded emptyList()
        r.entries.mapNotNull { (_, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            if (o["status"]?.jsonPrimitive?.content != "online") return@mapNotNull null
            val wsname = o["wsname"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val slash = wsname.indexOf('/')
            if (slash <= 0 || slash == wsname.lastIndex) return@mapNotNull null
            val pair = mapper.canonical(wsname.substring(0, slash), wsname.substring(slash + 1))
            ProviderSymbol(ProviderId.KRAKEN, pair, wsname)
        }
    }

    /** Watchlist: per-simbolo isolato (una coin non sul provider non fa fallire le altre). */
    override suspend fun tickers24h(symbols: Collection<String>): List<Ticker> =
        symbols.distinct().mapNotNull { sym ->
            val n = nativeOf(sym) ?: return@mapNotNull null
            runCatching {
                val r = parseResult(guarded { api.ticker(n) }) ?: return@runCatching null
                r.values.firstNotNullOfOrNull { it as? JsonObject }?.let { tickerFrom(it, sym) }
            }.getOrNull()
        }

    /** Ranking: lista curata top-~24 in UNA chiamata (Ticker accetta coppie CSV). */
    override suspend fun tickers24hAll(): List<Ticker> = guarded {
        val r = parseResult(api.ticker(KRAKEN_TOP.mapNotNull { nativeOf(it) }.joinToString(",")))
            ?: return@guarded emptyList()
        KRAKEN_TOP.mapNotNull { sym ->
            val n = nativeOf(sym) ?: return@mapNotNull null
            (r[n] as? JsonObject)?.let { tickerFrom(it, sym) }
        }
    }

    override suspend fun klines(symbol: String, tf: Timeframe, limit: Int): List<Kline> {
        val n = nativeOf(symbol) ?: return emptyList()
        val interval = tf.intervalFor(ProviderId.KRAKEN)
        val intervalSec = (interval.toLongOrNull() ?: 1L) * 60L
        val resp = guarded { api.ohlc(n, interval) }
        val r = parseResult(resp) ?: return emptyList()
        // result = { "XBTUSD": [[t,o,h,l,c,vwap,vol,count], ...], "last": ... } → la JsonArray
        val rows = r.values.firstNotNullOfOrNull { it as? JsonArray } ?: return emptyList()
        return rows.mapNotNull { el ->
            val row = el as? JsonArray ?: return@mapNotNull null
            val t = row.getOrNull(0)?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
            fun v(i: Int) = row.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            Kline(
                openTime = t * 1000L,
                open = v(1),
                high = v(2),
                low = v(3),
                close = v(4),
                volume = v(6),
                closeTime = (t + intervalSec) * 1000L,
            )
        }.takeLast(limit)
    }

    override fun tickFlow(): Flow<List<PriceTick>> = ws.ticks
    override fun connectStream() = ws.connect()
    override fun disconnectStream() = ws.disconnect()
    override fun subscribeStream(symbols: Collection<String>) = ws.subscribe(symbols)
    override fun unsubscribeStream(symbols: Collection<String>) = ws.unsubscribe(symbols)

    /** Istantanea REST: {"error":[],"result":{...}} → result, o null se c'è errore. */
    private fun parseResult(resp: JsonObject): JsonObject? {
        val err = resp["error"]?.jsonArray
        if (err != null && err.isNotEmpty()) return null
        return resp["result"] as? JsonObject
    }

    private fun tickerFrom(o: JsonObject, symbol: String): Ticker {
        fun arr(key: String, idx: Int): Double =
            (o[key] as? JsonArray)?.getOrNull(idx)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
        val price = arr("c", 0)
        val open24h = arr("o", 1) // o = [open oggi, open 24h fa]
        val high24h = arr("h", 1)
        val low24h = arr("l", 1)
        val quoteVol = arr("q", 1) // q = [quote vol oggi, quote vol 24h]
        val change = if (open24h > 0) (price - open24h) / open24h * 100.0 else 0.0
        return Ticker(
            symbol = symbol,
            price = price,
            changePercent24h = change,
            high24h = high24h,
            low24h = low24h,
            quoteVolume24h = quoteVol,
            open24h = open24h,
        )
    }

    private inner class KrakenStream(
        client: OkHttpClient,
    ) : ProviderWebSocket(WS_URL, client) {
        override fun subscribeMessage(symbols: Set<String>): String? {
            val pairs = symbols.mapNotNull { wsnameOf(it) }
            if (pairs.isEmpty()) return null
            return buildJsonObject {
                put("event", "subscribe")
                put("pair", JsonArray(pairs.map { JsonPrimitive(it) }))
                put("subscription", buildJsonObject { put("name", "ticker") })
            }.toString()
        }

        override fun unsubscribeMessage(symbols: Set<String>): String? {
            val pairs = symbols.mapNotNull { wsnameOf(it) }
            if (pairs.isEmpty()) return null
            return buildJsonObject {
                put("event", "unsubscribe")
                put("pair", JsonArray(pairs.map { JsonPrimitive(it) }))
                put("subscription", buildJsonObject { put("name", "ticker") })
            }.toString()
        }

        override fun keepAliveIntervalMs(): Long = 30_000
        override fun keepAliveMessage(): String = """{"event":"ping"}"""

        override fun parseFrame(text: String): List<PriceTick> {
            // Frame ticker: [channelId, {c:[[prezzo,lotto]],o,h,l,...}, "ticker", "XBT/USD"]
            val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
            if (el !is JsonArray || el.size < 4) return emptyList()
            if (el[2]?.jsonPrimitive?.content != "ticker") return emptyList()
            val o = el[1] as? JsonObject ?: return emptyList()
            val wsname = el[3]?.jsonPrimitive?.content ?: return emptyList()
            val slash = wsname.indexOf('/')
            if (slash <= 0) return emptyList()
            val pair = mapper.canonical(wsname.substring(0, slash), wsname.substring(slash + 1))
            fun arr(key: String, idx: Int): Double =
                (o[key] as? JsonArray)?.getOrNull(idx)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            return listOf(
                PriceTick(
                    symbol = pair.compact,
                    provider = ProviderId.KRAKEN,
                    price = arr("c", 0),
                    open24h = arr("o", 1),
                    high24h = arr("h", 1),
                    low24h = arr("l", 1),
                    quoteVolume24h = 0.0, // il ticker WS di Kraken non espone il volume in quota
                )
            )
        }
    }

    companion object {
        const val WS_URL = "wss://ws.kraken.com"
        /** Top coin scambiate su Kraken in quote USD (per il ranking senza bulk). */
        private val KRAKEN_TOP: List<String> = listOf(
            "BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD", "ADAUSD", "DOGEUSD",
            "LTCUSD", "LINKUSD", "AVAXUSD", "DOTUSD", "XLMUSD", "ETCUSD",
            "BCHUSD", "TRXUSD", "NEARUSD", "APTUSD", "ARBUSD", "UNIUSD",
            "FILUSD", "ATOMUSD", "HBARUSD", "USDCUSD",
        )
    }
}
