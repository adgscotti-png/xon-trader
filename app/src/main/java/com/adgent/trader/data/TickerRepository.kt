package com.adgent.trader.data

import com.adgent.trader.core.database.KlineEntity
import com.adgent.trader.core.database.KlinesDao
import com.adgent.trader.core.database.SymbolEntity
import com.adgent.trader.core.database.SymbolsDao
import com.adgent.trader.core.database.TickerCacheDao
import com.adgent.trader.core.database.TickerCacheEntity
import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.network.BinanceApi
import com.adgent.trader.core.network.BinanceWebSocket
import com.adgent.trader.core.network.toKline
import com.adgent.trader.core.network.toModel
import com.adgent.trader.core.network.symbolsParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.ConcurrentHashMap

/** Riga della lista mercati: prezzo corrente (live se disponibile) + sparkline 24h. */
data class MarketRow(
    val symbol: String,
    val base: String,
    val price: Double,
    val changePercent24h: Double,
    val high24h: Double,
    val low24h: Double,
    val quoteVolume24h: Double,
    val sparkline: List<Double>,
    val isFavorite: Boolean,
)

class TickerRepository(
    private val api: BinanceApi,
    private val ws: BinanceWebSocket,
    private val symbolsDao: SymbolsDao,
    private val tickerCacheDao: TickerCacheDao,
    private val klinesDao: KlinesDao,
    private val scope: CoroutineScope,
) {
    /** Ultimi tick live per simbolo (dal WebSocket). */
    private val live = ConcurrentHashMap<String, PriceTick>()

    /** Bump ad ogni frame WS ricevuto: i collector lo usano per ricalcolare le righe. */
    private val _liveVersion = MutableStateFlow(0L)
    val liveVersion = _liveVersion.asStateFlow()

    @Volatile private var snapshotAt = 0L
    @Volatile private var wsCollectorStarted = false

    // ---------- Simboli ----------

    suspend fun ensureSymbols(): List<SymbolEntity> {
        symbolsDao.all().takeIf { it.isNotEmpty() }?.let { return it }
        val info = runCatching { api.exchangeInfo() }.getOrNull() ?: return emptyList()
        val usdt = info.symbols.mapNotNull { it.toModel() }
            .filter { it.quote == "USDT" }
            .map { SymbolEntity(it.symbol, it.base, it.quote) }
        symbolsDao.clear()
        symbolsDao.insertAll(usdt)
        return usdt
    }

    suspend fun allSymbols(): List<SymbolEntity> = symbolsDao.all()

    /** Mappa symbol → base asset per le etichette della lista mercati. */
    fun symbolMap(symbols: List<SymbolEntity>): Map<String, String> =
        symbols.associate { it.symbol to it.base }

    // ---------- Live WebSocket ----------

    /** Collega il WS (idempotente) e tiene aggiornata la mappa dei tick. */
    fun ensureLive() {
        ws.connect()
        if (wsCollectorStarted) return
        wsCollectorStarted = true
        scope.launch {
            ws.ticks.collect { batch ->
                batch.forEach { live[it.symbol] = it }
                _liveVersion.value += 1
            }
        }
    }

    fun liveTick(symbol: String): PriceTick? = live[symbol]

    // ---------- Snapshot REST → cache Room ----------

    suspend fun refreshTickers(symbols: Collection<String>, force: Boolean = false): Result<Unit> {
        if (symbols.isEmpty()) return Result.success(Unit)
        if (!force && System.currentTimeMillis() - snapshotAt < SNAPSHOT_TTL_MS) return Result.success(Unit)
        return runCatching {
            val existing = tickerCacheDao.all().associateBy { it.symbol }
            symbols.chunked(80).forEach { chunk ->
                tickerCacheDao.upsertAll(
                    api.tickers24h(symbolsParam(chunk)).map { dto ->
                        val t = dto.toModel()
                        TickerCacheEntity(
                            symbol = t.symbol,
                            price = t.price,
                            changePercent24h = t.changePercent24h,
                            high24h = t.high24h,
                            low24h = t.low24h,
                            quoteVolume24h = t.quoteVolume24h,
                            sparkline = existing[t.symbol]?.sparkline.orEmpty(),
                            updatedAt = existing[t.symbol]?.updatedAt ?: 0L,
                        )
                    }
                )
            }
            snapshotAt = System.currentTimeMillis()
        }
    }

    // ---------- Sparkline 24h (klines 1h × 24) ----------

    suspend fun refreshSparklines(symbols: Collection<String>, maxAgeMs: Long = SPARK_TTL_MS) {
        if (symbols.isEmpty()) return
        val byId = tickerCacheDao.all().associateBy { it.symbol }
        symbols.forEach { sym ->
            val old = byId[sym]
            val fresh = old != null &&
                System.currentTimeMillis() - old.updatedAt < maxAgeMs &&
                old.sparkline.isNotBlank()
            if (!fresh) {
                runCatching {
                    val raw: List<JsonArray> = api.klines(sym, "1h", 24)
                    val closes = raw.map { it.toKline().close }
                    tickerCacheDao.upsertAll(
                        listOf(
                            (old ?: placeholder(sym)).copy(
                                sparkline = closes.joinToString(","),
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                    )
                }
            }
        }
    }

    private fun placeholder(symbol: String) = TickerCacheEntity(
        symbol = symbol, price = 0.0, changePercent24h = 0.0,
        high24h = 0.0, low24h = 0.0, quoteVolume24h = 0.0,
        sparkline = "", updatedAt = 0L,
    )

    // ---------- Lettura reattiva per la lista mercati / widget ----------

    fun observeCached(limit: Int): Flow<List<TickerCacheEntity>> =
        tickerCacheDao.topByVolume(limit)

    companion object {
        private const val SNAPSHOT_TTL_MS = 60_000L
        private const val SPARK_TTL_MS = 15 * 60_000L
    }
}

/** Grafico: klines con cache offline-first. */
class ChartRepository(
    private val api: BinanceApi,
    private val klinesDao: KlinesDao,
) {
    suspend fun cached(symbol: String, tf: Timeframe): List<Kline> =
        klinesDao.load(symbol, tf.binanceInterval).map { it.toModel() }

    suspend fun refresh(symbol: String, tf: Timeframe): Result<List<Kline>> = runCatching {
        val raw: List<JsonArray> = api.klines(symbol, tf.binanceInterval, tf.defaultLimit)
        val klines = raw.map { it.toKline() }
        klinesDao.upsertAll(klines.map { it.toEntity(symbol, tf.binanceInterval) })
        klinesDao.prune(
            symbol, tf.binanceInterval,
            before = System.currentTimeMillis() - maxAgeMs(tf),
        )
        klines
    }

    private fun Kline.toEntity(symbol: String, interval: String) = KlineEntity(
        symbol = symbol, interval = interval, openTime = openTime, closeTime = closeTime,
        open = open, high = high, low = low, close = close, volume = volume,
    )

    private fun KlineEntity.toModel() = Kline(openTime, open, high, low, close, volume, closeTime)

    private fun maxAgeMs(tf: Timeframe): Long = when (tf) {
        Timeframe.M15 -> 7L
        Timeframe.H1 -> 30L
        Timeframe.H4 -> 120L
        Timeframe.D1 -> 730L
        Timeframe.W1 -> 3_650L
    } * 86_400_000L
}
