package com.adgent.trader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SymbolEntity::class,
        TickerCacheEntity::class,
        KlineEntity::class,
        WatchlistEntity::class,
        AlertRuleEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class TraderDatabase : RoomDatabase() {
    abstract fun symbolsDao(): SymbolsDao
    abstract fun tickerCacheDao(): TickerCacheDao
    abstract fun klinesDao(): KlinesDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun alertDao(): AlertDao

    companion object {
        fun build(context: Context): TraderDatabase =
            Room.databaseBuilder(context, TraderDatabase::class.java, "adgent_trader.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
