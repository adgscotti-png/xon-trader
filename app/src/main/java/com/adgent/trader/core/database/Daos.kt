package com.adgent.trader.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// ---------- Simboli per provider (exchangeInfo, refresh per-provider) ----------

@Entity(tableName = "symbols", primaryKeys = ["provider", "symbol"])
data class SymbolEntity(
    val provider: String,
    val symbol: String,
    val base: String,
    val quote: String,
)

@Dao
interface SymbolsDao {
    @Query("SELECT * FROM symbols")
    suspend fun all(): List<SymbolEntity>

    @Query("SELECT * FROM symbols WHERE provider = :provider")
    suspend fun allFor(provider: String): List<SymbolEntity>

    @Query("SELECT * FROM symbols WHERE provider = :provider AND symbol = :symbol LIMIT 1")
    suspend fun bySymbol(provider: String, symbol: String): SymbolEntity?

    @Query("DELETE FROM symbols WHERE provider = :provider")
    suspend fun deleteForProvider(provider: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SymbolEntity>)

    @Query("SELECT count(*) FROM symbols")
    suspend fun count(): Int
}

// ---------- Cache ticker per provider (offline + widget) ----------

@Entity(tableName = "ticker_cache", primaryKeys = ["provider", "symbol"])
data class TickerCacheEntity(
    val provider: String,
    val symbol: String,
    val price: Double,
    val changePercent24h: Double,
    val high24h: Double,
    val low24h: Double,
    val quoteVolume24h: Double,
    /** Chiusure delle ultime 24h (intervallo orario), separate da virgola — per sparkline widget/offline. */
    val sparkline: String,
    val updatedAt: Long,
)

@Dao
interface TickerCacheDao {
    @Upsert
    suspend fun upsertAll(items: List<TickerCacheEntity>)

    @Query("SELECT * FROM ticker_cache WHERE provider = :provider AND symbol = :symbol")
    fun observe(provider: String, symbol: String): Flow<TickerCacheEntity?>

    @Query("SELECT * FROM ticker_cache ORDER BY quoteVolume24h DESC LIMIT :limit")
    fun topByVolumeAll(limit: Int): Flow<List<TickerCacheEntity>>

    @Query("SELECT * FROM ticker_cache WHERE provider = :provider ORDER BY quoteVolume24h DESC LIMIT :limit")
    fun topByVolume(provider: String, limit: Int): Flow<List<TickerCacheEntity>>

    @Query("SELECT * FROM ticker_cache")
    suspend fun all(): List<TickerCacheEntity>

    @Query("SELECT * FROM ticker_cache WHERE provider = :provider")
    suspend fun allFor(provider: String): List<TickerCacheEntity>

    /** Aggiorna il tick live PRESERVANDO sparkline e updatedAt storico (per widget/offline). */
    @Query(
        "UPDATE ticker_cache SET price = :price, changePercent24h = :change, high24h = :high, " +
            "low24h = :low, quoteVolume24h = :volume, updatedAt = :updatedAt " +
            "WHERE provider = :provider AND symbol = :symbol"
    )
    suspend fun updateTick(
        provider: String,
        symbol: String,
        price: Double,
        change: Double,
        high: Double,
        low: Double,
        volume: Double,
        updatedAt: Long,
    )
}

// ---------- Klines cache per grafico offline (per provider) ----------

@Entity(tableName = "klines", primaryKeys = ["provider", "symbol", "interval", "openTime"])
data class KlineEntity(
    val provider: String,
    val symbol: String,
    val interval: String,
    val openTime: Long,
    val closeTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

@Dao
interface KlinesDao {
    @Query(
        "SELECT * FROM klines WHERE provider = :provider AND symbol = :symbol AND interval = :interval " +
            "ORDER BY openTime ASC"
    )
    suspend fun load(provider: String, symbol: String, interval: String): List<KlineEntity>

    @Upsert
    suspend fun upsertAll(items: List<KlineEntity>)

    @Query(
        "DELETE FROM klines WHERE provider = :provider AND symbol = :symbol AND interval = :interval " +
            "AND openTime < :before"
    )
    suspend fun prune(provider: String, symbol: String, interval: String, before: Long)
}

// ---------- Watchlist (coppia provider+simbolo, max 20) ----------

@Entity(tableName = "watchlist", primaryKeys = ["provider", "symbol"])
data class WatchlistEntity(
    val provider: String,
    val symbol: String,
    val position: Int,
    val addedAt: Long,
)

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY position ASC")
    fun observeAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist ORDER BY position ASC")
    suspend fun all(): List<WatchlistEntity>

    @Upsert
    suspend fun upsert(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE provider = :provider AND symbol = :symbol")
    suspend fun delete(provider: String, symbol: String)

    @Query("DELETE FROM watchlist")
    suspend fun clear()

    @Query("SELECT count(*) FROM watchlist")
    suspend fun count(): Int

    @Query("SELECT MAX(position) FROM watchlist")
    suspend fun maxPosition(): Int?

    @Query("SELECT * FROM watchlist WHERE provider = :provider AND symbol = :symbol LIMIT 1")
    suspend fun bySymbol(provider: String, symbol: String): WatchlistEntity?
}

// ---------- Regole avvisi prezzo (provider del simbolo, default BINANCE) ----------

@Entity(tableName = "alert_rules")
data class AlertRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    /** PRICE_ABOVE | PRICE_BELOW | PERCENT_UP | PERCENT_DOWN */
    val type: String,
    val threshold: Double,
    val repeatable: Boolean,
    val note: String,
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastTriggeredAt: Long? = null,
    /** Provider a cui appartiene il simbolo della regola (serve all'engine live). */
    @ColumnInfo(defaultValue = "BINANCE") val provider: String = "BINANCE",
)

@Dao
interface AlertDao {
    @Query("SELECT * FROM alert_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<AlertRuleEntity>

    @Query("SELECT * FROM alert_rules ORDER BY createdAt DESC")
    suspend fun all(): List<AlertRuleEntity>

    @Query("SELECT * FROM alert_rules WHERE id = :id")
    suspend fun byId(id: Long): AlertRuleEntity?

    @Upsert
    suspend fun upsert(rule: AlertRuleEntity): Long

    @Query("UPDATE alert_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE alert_rules SET lastTriggeredAt = :at, enabled = :enabled WHERE id = :id")
    suspend fun markTriggered(id: Long, at: Long, enabled: Boolean)

    @Query("DELETE FROM alert_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM alert_rules")
    suspend fun clear()
}
