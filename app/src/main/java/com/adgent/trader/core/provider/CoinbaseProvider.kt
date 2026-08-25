package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.network.CoinbaseApi
import com.adgent.trader.core.network.CoinbaseStatsDto
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
 * Adapter Coinbase Exchange sul port [MarketDataProvider].
 * REST: api.exchange.coinbase.com — nessun bulk per i ticker 24h → stats
 * per-simbolo (pattern curato, come Kraken); le candele hanno granularity in
 * secondi e ordine [time, low, high, open, close, volume]. Simboli "BTC-USD".
 * WS: ch. `ticker` con product_id nel frame (nessun chanId), ping non richiesto.
 */
class CoinbaseProvider(
    private val api: CoinbaseApi,
    client: OkHttpClient,
    mapper: SymbolMapper,
) : BaseMarketProvider(
    id = ProviderId.COINBASE,
    displayName = "Coinbase",
    rateLimit = RateLimit(maxRequests = 10, windowMs = 1000),
    mapper = mapper,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val ws = CoinbaseStream(client)

    private fun nativeOf(symbol: String): String? {
        val pair = mapper.fromCompact(symbol) ?: return null
        return mapper.toProviderSymbol(ProviderId.COINBASE, pair)
    }

    override suspend fun ping(): Boolean =
        runCatching { guarded { api.products() }; true }.getOrDefault(false)

    override suspend fun refreshCatalog(): List<ProviderSymbol> = guarded {
        api.products().mapNotNull { p ->
            if (p.status != "online") return@mapNotNull null
            // canonical() applica gli alias: basi a 4 char (SHIB, WLD...) gestite dal prodotto.
            val pair = mapper.canonical(p.base_currency, p.quote_currency)
            ProviderSymbol(ProviderId.COINBASE, pair, p.id)
        }
    }

    /** Watchlist: stats per-simbolo isolato (una coin assente non fa fallire le altre). */
    override suspend fun tickers24h(symbols: Collection<String>): List<Ticker> =
        symbols.distinct().mapNotNull { sym ->
            val n = nativeOf(sym) ?: return@mapNotNull null
            runCatching { tickerFrom(guarded { api.stats(n) }, sym) }.getOrNull()
        }

    /** Ranking: lista curata top-~20 in quote USD, stats per-simbolo. */
    override suspend fun tickers24hAll(): List<Ticker> =
        COINBASE_TOP.mapNotNull { sym ->
            val n = nativeOf(sym) ?: return@mapNotNull null
            runCatching { tickerFrom(guarded { api.stats(n) }, sym) }.getOrNull()
        }

    private fun tickerFrom(s: CoinbaseStatsDto, symbol: String): Ticker? {
        val price = s.last.toDoubleOrNull() ?: return null
        val open = s.open.toDoubleOrNull() ?: return null
        val baseVol = s.volume.toDoubleOrNull() ?: 0.0
        return Ticker(
            symbol = symbol,
            price = price,
            changePercent24h = if (open > 0) (price - open) / open * 100.0 else 0.0,
            high24h = s.high.toDoubleOrNull() ?: 0.0,
            low24h = s.low.toDoubleOrNull() ?: 0.0,
            quoteVolume24h = baseVol * price, // le stats non danno il quote volume: approssimazione
            open24h = open,
        )
    }

    override suspend fun klines(symbol: String, tf: Timeframe, limit: Int): List<Kline> {
        val n = nativeOf(symbol) ?: return emptyList()
        val granularity = tf.intervalFor(ProviderId.COINBASE).toIntOrNull() ?: 60
        return guarded { api.candles(n, granularity, limit) }
            .mapNotNull { row ->
                // [time, low, high, open, close, volume] — time in secondi
                val t = row.getOrNull(0)?.toLong() ?: return@mapNotNull null
                Kline(
                    openTime = t * 1000L,
                    open = row.getOrNull(3) ?: 0.0,
                    high = row.getOrNull(2) ?: 0.0,
                    low = row.getOrNull(1) ?: 0.0,
                    close = row.getOrNull(4) ?: 0.0,
                    volume = row.getOrNull(5) ?: 0.0,
                    closeTime = t * 1000L,
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

    private inner class CoinbaseStream(
        client: OkHttpClient,
    ) : ProviderWebSocket(WS_URL, client) {
        override fun subscribeMessage(symbols: Set<String>): String? {
            val ids = symbols.mapNotNull { nativeOf(it) }
            if (ids.isEmpty()) return null
            return buildJsonObject {
                put("type", "subscribe")
                put("product_ids", JsonArray(ids.map { JsonPrimitive(it) }))
                put("channels", JsonArray(listOf(JsonPrimitive("ticker"))))
            }.toString()
        }

        override fun unsubscribeMessage(symbols: Set<String>): String? {
            val ids = symbols.mapNotNull { nativeOf(it) }
            if (ids.isEmpty()) return null
            return buildJsonObject {
                put("type", "unsubscribe")
                put("product_ids", JsonArray(ids.map { JsonPrimitive(it) }))
                put("channels", JsonArray(listOf(JsonPrimitive("ticker"))))
            }.toString()
        }

        override fun parseFrame(text: String): List<PriceTick> {
            // {"type":"ticker","product_id":"BTC-USD","price":"...","open_24h":"...",
            //  "high_24h":"...","low_24h":"...","volume_24h":"...",...}
            val root = json.parseToJsonElement(text).jsonObject
            if (root["type"]?.jsonPrimitive?.content != "ticker") return emptyList()
            val pid = root["product_id"]?.jsonPrimitive?.content ?: return emptyList()
            val c = mapper.toCanonical(ProviderId.COINBASE, pid)?.compact ?: return emptyList()
            val price = root["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val baseVol = root["volume_24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            return listOf(
                PriceTick(
                    symbol = c,
                    provider = ProviderId.COINBASE,
                    price = price,
                    open24h = root["open_24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    high24h = root["high_24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    low24h = root["low_24h"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    quoteVolume24h = baseVol * price,
                )
            )
        }
    }

    companion object {
        const val WS_URL = "wss://ws-feed.exchange.coinbase.com"
        /** Top coin scambiate su Coinbase Exchange in quote USD (ranking senza bulk). */
        private val COINBASE_TOP: List<String> = listOf(
            "BTCUSD", "ETHUSD", "SOLUSD", "XRPUSD", "ADAUSD", "DOGEUSD",
            "LTCUSD", "LINKUSD", "AVAXUSD", "DOTUSD", "ATOMUSD", "UNIUSD",
            "FILUSD", "NEARUSD", "APTUSD", "ARBUSD", "OPUSD", "SUIUSD",
            "PEPEUSD", "SHIBUSD", "USDCUSD", "XLMUSD",
        )
    }
}
