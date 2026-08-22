package com.adgent.trader.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// ---------- Simboli (exchangeInfo, refresh settimanale) ----------

@Entity(tableName = "symbols")
data class SymbolEntity(
    @PrimaryKey val symbol: String,
    val base: String,
    val quote: String,
)

@Dao
interface SymbolsDao {
    @Query("SELECT * FROM symbols")
    suspend fun all(): List<SymbolEntity>

    @Query("SELECT * FROM symbols WHERE symbol = :symbol LIMIT 1")
    suspend fun bySymbol(symbol: String): SymbolEntity?

    @Query("DELETE FROM symbols")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SymbolEntity>)

    @Query("SELECT count(*) FROM symbols")
    suspend fun count(): Int
}

// ---------- Cache ticker (offline + widget) ----------

@Entity(tableName = "ticker_cache")
data class TickerCacheEntity(
    @PrimaryKey val symbol: String,
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

    @Query("SELECT * FROM ticker_cache WHERE symbol = :symbol")
    fun observe(symbol: String): Flow<TickerCacheEntity?>

    @Query("SELECT * FROM ticker_cache ORDER BY quoteVolume24h DESC LIMIT :limit")
    fun topByVolume(limit: Int): Flow<List<TickerCacheEntity>>

    @Query("SELECT * FROM ticker_cache")
    suspend fun all(): List<TickerCacheEntity>
}

// ---------- Klines cache per grafico offline ----------

@Entity(tableName = "klines", primaryKeys = ["symbol", "interval", "openTime"])
data class KlineEntity(
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
        "SELECT * FROM klines WHERE symbol = :symbol AND interval = :interval " +
            "ORDER BY openTime ASC"
    )
    suspend fun load(symbol: String, interval: String): List<KlineEntity>

    @Upsert
    suspend fun upsertAll(items: List<KlineEntity>)

    @Query(
        "DELETE FROM klines WHERE symbol = :symbol AND interval = :interval " +
            "AND openTime < :before"
    )
    suspend fun prune(symbol: String, interval: String, before: Long)
}

// ---------- Watchlist ----------

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val symbol: String,
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

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun delete(symbol: String)

    @Query("SELECT count(*) FROM watchlist")
    suspend fun count(): Int

    @Query("SELECT MAX(position) FROM watchlist")
    suspend fun maxPosition(): Int?
}

// ---------- Regole avvisi prezzo ----------

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
)

@Dao
interface AlertDao {
    @Query("SELECT * FROM alert_rules ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rules WHERE enabled = 1")
    suspend fun enabledRules(): List<AlertRuleEntity>

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
}
