package com.adgent.trader.data

import com.adgent.trader.core.database.KlineEntity
import com.adgent.trader.core.database.KlinesDao
import com.adgent.trader.core.database.SymbolEntity
import com.adgent.trader.core.database.TickerCacheDao
import com.adgent.trader.core.database.TickerCacheEntity
import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import com.adgent.trader.core.provider.CanonicalPair
import com.adgent.trader.core.provider.MarketCatalog
import com.adgent.trader.core.provider.PriceFeedHub
import com.adgent.trader.core.provider.ProviderId
import com.adgent.trader.core.provider.ProviderRegistry
import com.adgent.trader.core.provider.SymbolMapper
import com.adgent.trader.core.provider.intervalFor
import kotlinx.coroutines.flow.Flow
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
    val provider: ProviderId,
)

/**
 * Facciata unica sui dati di mercato multi-provider: cache Room per-provider,
 * snapshot REST throttlati per provider, live della watchlist via [PriceFeedHub]
 * e catalogo per provider. Sostituisce TickerRepository (stessa semantica, ora
 * con chiave (provider, symbol)).
 */
class MarketDataRepository(
    val catalog: MarketCatalog,
    val registry: ProviderRegistry,
    val hub: PriceFeedHub,
    private val tickerCacheDao: TickerCacheDao,
    private val mapper: SymbolMapper,
) {
    private val snapshots = ConcurrentHashMap<String, Long>()

    // ---------- Live (watchlist) ----------

    fun liveVersion(): Flow<Long> = hub.liveVersion
    fun liveTick(provider: ProviderId, symbol: String): PriceTick? = hub.liveTick(provider, symbol)

    // ---------- Catalogo ----------

    suspend fun ensureCatalog(provider: ProviderId) {
        registry.getOrNull(provider)?.let { catalog.ensureProviderCatalog(it) }
    }

    suspend fun catalogFor(provider: ProviderId): List<SymbolEntity> = catalog.allFor(provider)

    fun symbolMap(symbols: List<SymbolEntity>): Map<String, String> =
        symbols.associate { it.symbol to it.base }

    fun fromCompact(symbol: String): CanonicalPair? = mapper.fromCompact(symbol)

    /** Coppia canonica (base/quote) a partire dal simbolo di un provider.
     * Il coin detail arriva con il compatto canonico (es. "BTCUSD"): prima
     * prova [SymbolMapper.fromCompact], poi il formato nativo (es. "BTC-USD"). */
    fun canonicalOf(provider: ProviderId, symbol: String): CanonicalPair? =
        mapper.fromCompact(symbol) ?: mapper.toCanonical(provider, symbol)

    /** Simbolo provider-specifico per una coppia canonica (null se non listata). */
    fun providerSymbol(provider: ProviderId, pair: CanonicalPair): String? =
        mapper.toProviderSymbol(provider, pair)

    // ---------- Cache snapshot REST ----------

    fun observeCached(provider: ProviderId?, limit: Int): Flow<List<TickerCacheEntity>> =
        if (provider == null) tickerCacheDao.topByVolumeAll(limit)
        else tickerCacheDao.topByVolume(provider.name, limit)

    /** Osserva una singola riga cache: fallback per le stats di coin NON in watchlist
     * (il liveTick dell'hub è solo per la watchlist, quindi il coin detail deve
     * rileggere la cache popolata dal refreshTickers on-open). */
    fun observeCachedSymbol(provider: ProviderId, symbol: String): Flow<TickerCacheEntity?> =
        tickerCacheDao.observe(provider.name, symbol)

    suspend fun refreshTickers(
        provider: ProviderId,
        symbols: Collection<String>,
        force: Boolean = false,
    ): Result<Unit> {
        if (symbols.isEmpty()) return Result.success(Unit)
        val now = System.currentTimeMillis()
        if (!force && now - (snapshots[provider.name] ?: 0L) < SNAPSHOT_TTL_MS) return Result.success(Unit)
        return runCatching {
            val existing = tickerCacheDao.allFor(provider.name).associateBy { it.symbol }
            val tickers = registry.get(provider).tickers24h(symbols)
            tickerCacheDao.upsertAll(tickers.map { toEntity(provider, it, existing) })
            snapshots[provider.name] = System.currentTimeMillis()
        }
    }

    /**
     * Seeding del mercato largo di un provider (una sola chiamata bulk quando
     * l'exchange la offre; throttlata altrimenti): cache dei top per volume.
     * Serve al primo avvio, quando la cache del provider è vuota.
     */
    suspend fun refreshTopMarket(
        provider: ProviderId,
        limit: Int = 120,
        minQuoteVolume: Double = 0.0,
    ): Result<Unit> = runCatching {
        val existing = tickerCacheDao.allFor(provider.name).associateBy { it.symbol }
        val top = registry.get(provider).tickers24hAll()
            .filter { it.quoteVolume24h >= minQuoteVolume }
            .sortedByDescending { it.quoteVolume24h }
            .take(limit)
        tickerCacheDao.upsertAll(top.map { toEntity(provider, it, existing) })
    }

    /** Sparkline 24h (klines 1h × 24) per i simboli indicati, se non fresca. */
    suspend fun refreshSparklines(provider: ProviderId, symbols: Collection<String>) {
        if (symbols.isEmpty()) return
        val now = System.currentTimeMillis()
        val byId = tickerCacheDao.allFor(provider.name).associateBy { it.symbol }
        symbols.forEach { sym ->
            val old = byId[sym]
            val fresh = old != null &&
                now - old.updatedAt < SPARK_TTL_MS &&
                old.sparkline.isNotBlank()
            if (!fresh) {
                runCatching {
                    val closes = registry.get(provider).klines(sym, Timeframe.H1, 24).map { it.close }
                    tickerCacheDao.upsertAll(
                        listOf(
                            (old ?: placeholder(provider, sym)).copy(
                                sparkline = closes.joinToString(","),
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                    )
                }
            }
        }
    }

    private fun toEntity(
        provider: ProviderId,
        t: Ticker,
        existing: Map<String, TickerCacheEntity>,
    ) = TickerCacheEntity(
        provider = provider.name,
        symbol = t.symbol,
        price = t.price,
        changePercent24h = t.changePercent24h,
        high24h = t.high24h,
        low24h = t.low24h,
        quoteVolume24h = t.quoteVolume24h,
        sparkline = existing[t.symbol]?.sparkline.orEmpty(),
        updatedAt = existing[t.symbol]?.updatedAt ?: 0L,
    )

    private fun placeholder(provider: ProviderId, symbol: String) = TickerCacheEntity(
        provider = provider.name, symbol = symbol, price = 0.0, changePercent24h = 0.0,
        high24h = 0.0, low24h = 0.0, quoteVolume24h = 0.0,
        sparkline = "", updatedAt = 0L,
    )

    companion object {
        private const val SNAPSHOT_TTL_MS = 60_000L
        private const val SPARK_TTL_MS = 15 * 60_000L
    }
}

/** Grafico: klines con cache offline-first, per provider. */
class ChartRepository(
    private val registry: ProviderRegistry,
    private val klinesDao: KlinesDao,
) {
    suspend fun cached(provider: ProviderId, symbol: String, tf: Timeframe): List<Kline> =
        klinesDao.load(provider.name, symbol, tf.intervalFor(provider)).map { it.toModel() }

    suspend fun refresh(provider: ProviderId, symbol: String, tf: Timeframe): Result<List<Kline>> =
        runCatching {
            val interval = tf.intervalFor(provider)
            val klines = registry.get(provider).klines(symbol, tf, tf.defaultLimit)
            klinesDao.upsertAll(klines.map { it.toEntity(provider.name, symbol, interval) })
            klinesDao.prune(
                provider.name, symbol, interval,
                before = System.currentTimeMillis() - maxAgeMs(tf),
            )
            klines
        }

    private fun Kline.toEntity(provider: String, symbol: String, interval: String) = KlineEntity(
        provider = provider, symbol = symbol, interval = interval,
        openTime = openTime, closeTime = closeTime,
        open = open, high = high, low = low, close = close, volume = volume,
    )

    private fun KlineEntity.toModel() = Kline(openTime, open, high, low, close, volume, closeTime)

    private fun maxAgeMs(tf: Timeframe): Long = when (tf) {
        Timeframe.M1 -> 3L
        Timeframe.M15 -> 7L
        Timeframe.H1 -> 30L
        Timeframe.H4 -> 120L
        Timeframe.D1 -> 730L
        Timeframe.W1 -> 3_650L
        Timeframe.MO -> 7_300L
    } * 86_400_000L
}
