package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.network.BybitApi
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Adapter Bybit V5 sul port [MarketDataProvider].
 * REST: api.bybit.com/v5/market (intero spot in una chiamata) · WS: ch. `tickers`
 * per-simbolo (subscribe in batch). Simboli nativi == canonici (BTCUSDT).
 * Il WS richiede un ping applicativo ogni 20s → [keepAlive].
 */
class BybitProvider(
    private val api: BybitApi,
    client: OkHttpClient,
    mapper: SymbolMapper,
) : BaseMarketProvider(
    id = ProviderId.BYBIT,
    displayName = "Bybit",
    rateLimit = RateLimit(maxRequests = 50, windowMs = 60_000),
    mapper = mapper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ws = BybitStream(client)

    private fun canonical(symbol: String): String? =
        mapper.toCanonical(ProviderId.BYBIT, symbol)?.compact

    override suspend fun ping(): Boolean =
        runCatching { guarded { api.time() }; true }.getOrDefault(false)

    override suspend fun refreshCatalog(): List<ProviderSymbol> = guarded {
        api.instruments().result.list.mapNotNull { d ->
            if (d.status != "Trading") return@mapNotNull null
            val pair = mapper.toCanonical(ProviderId.BYBIT, d.symbol) ?: return@mapNotNull null
            ProviderSymbol(ProviderId.BYBIT, pair, d.symbol)
        }
    }

    /** Lo snapshot dell'intero spot è una singola chiamata: lo condividiamo per
     *  [tickers24h] (filtro) e [tickers24hAll] (ranking), riusato 30s per non
     *  ripetere il download a ogni refresh del tab. */
    override suspend fun tickers24h(symbols: Collection<String>): List<Ticker> {
        val wanted = symbols.distinct().mapNotNull { canonical(it) }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return allSnapshot().filter { it.symbol in wanted }
    }

    override suspend fun tickers24hAll(): List<Ticker> = allSnapshot()

    private suspend fun allSnapshot(): List<Ticker> {
        val now = System.currentTimeMillis()
        if (now - allFetchedAt < SNAPSHOT_TTL_MS) return allResult
        val fresh = guarded { api.tickers() }.result.list.mapNotNull { d ->
            canonical(d.symbol)?.let { c ->
                Ticker(
                    symbol = c,
                    price = d.lastPrice.toDoubleOrNull() ?: 0.0,
                    open24h = d.prevPrice24h.toDoubleOrNull() ?: 0.0,
                    high24h = d.highPrice24h.toDoubleOrNull() ?: 0.0,
                    low24h = d.lowPrice24h.toDoubleOrNull() ?: 0.0,
                    quoteVolume24h = d.turnover24h.toDoubleOrNull() ?: 0.0,
                    changePercent24h = (d.price24hPcnt.toDoubleOrNull() ?: 0.0) * 100.0,
                )
            }
        }
        if (fresh.isNotEmpty()) {
            allResult = fresh
            allFetchedAt = System.currentTimeMillis()
        }
        return allResult
    }

    override suspend fun klines(symbol: String, tf: Timeframe, limit: Int): List<Kline> =
        guarded { api.klines(symbol = symbol, interval = tf.intervalFor(ProviderId.BYBIT), limit = limit).result.list }
            .mapNotNull { row ->
                // [startTime, open, high, low, close, volume, turnover]
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
            // Bybit restituisce le candele dalla più recente: il grafico vuole ordine crescente.
            .sortedBy { it.openTime }

    override fun tickFlow(): Flow<List<PriceTick>> = ws.ticks
    override fun connectStream() = ws.connect()
    override fun disconnectStream() = ws.disconnect()
    override fun subscribeStream(symbols: Collection<String>) = ws.subscribe(symbols)
    override fun unsubscribeStream(symbols: Collection<String>) = ws.unsubscribe(symbols)

    private inner class BybitStream(
        client: OkHttpClient,
    ) : ProviderWebSocket(WS_URL, client) {
        override fun subscribeMessage(symbols: Set<String>): String? {
            val args = symbols.mapNotNull { canonical(it)?.let { n -> "tickers.$n" } }
            if (args.isEmpty()) return null
            return buildJsonObject {
                put("op", "subscribe")
                put("args", JsonArray(args.map { JsonPrimitive(it) }))
            }.toString()
        }

        override fun unsubscribeMessage(symbols: Set<String>): String? {
            val args = symbols.mapNotNull { canonical(it)?.let { n -> "tickers.$n" } }
            if (args.isEmpty()) return null
            return buildJsonObject {
                put("op", "unsubscribe")
                put("args", JsonArray(args.map { JsonPrimitive(it) }))
            }.toString()
        }

        override fun keepAliveIntervalMs(): Long = 15_000
        override fun keepAliveMessage(): String = """{"op":"ping"}"""

        override fun parseFrame(text: String): List<PriceTick> {
            // {"topic":"tickers.BTCUSDT","data":{"symbol":"BTCUSDT","lastPrice":"...",
            //  "prevPrice24h":"...","highPrice24h":"...","lowPrice24h":"...",
            //  "turnover24h":"...","price24hPcnt":"..."}}
            val root = json.parseToJsonElement(text).jsonObject
            val data = root["data"] ?: return emptyList()
            val o = data.jsonObject
            val raw = o["symbol"]?.jsonPrimitive?.content ?: return emptyList()
            val c = canonical(raw) ?: return emptyList()
            return listOf(
                PriceTick(
                    symbol = c,
                    provider = ProviderId.BYBIT,
                    price = o["lastPrice"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    open24h = o["prevPrice24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    high24h = o["highPrice24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    low24h = o["lowPrice24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    quoteVolume24h = o["turnover24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                )
            )
        }
    }

    companion object {
        const val WS_URL = "wss://stream.bybit.com/v5/public/spot"
        private const val SNAPSHOT_TTL_MS = 30_000L
    }

    @Volatile private var allResult: List<Ticker> = emptyList()
    @Volatile private var allFetchedAt: Long = 0L
}
