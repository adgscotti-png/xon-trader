package com.adgent.trader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2: PK composite (provider, symbol) su tutte le tabelle di mercato.
 * I dati esistenti vengono copiati con provider='BINANCE' (unico provider in
 * v0.2.8): watchlist, cache, klines e alert restano INTATTI (mai distruttivo).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `symbols_new` (" +
                "`provider` TEXT NOT NULL, `symbol` TEXT NOT NULL, " +
                "`base` TEXT NOT NULL, `quote` TEXT NOT NULL, " +
                "PRIMARY KEY(`provider`, `symbol`))"
        )
        db.execSQL(
            "INSERT INTO `symbols_new` (`provider`, `symbol`, `base`, `quote`) " +
                "SELECT 'BINANCE', `symbol`, `base`, `quote` FROM `symbols`"
        )
        db.execSQL("DROP TABLE `symbols`")
        db.execSQL("ALTER TABLE `symbols_new` RENAME TO `symbols`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ticker_cache_new` (" +
                "`provider` TEXT NOT NULL, `symbol` TEXT NOT NULL, " +
                "`price` REAL NOT NULL, `changePercent24h` REAL NOT NULL, " +
                "`high24h` REAL NOT NULL, `low24h` REAL NOT NULL, " +
                "`quoteVolume24h` REAL NOT NULL, `sparkline` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`provider`, `symbol`))"
        )
        db.execSQL(
            "INSERT INTO `ticker_cache_new` (`provider`, `symbol`, `price`, `changePercent24h`, " +
                "`high24h`, `low24h`, `quoteVolume24h`, `sparkline`, `updatedAt`) " +
                "SELECT 'BINANCE', `symbol`, `price`, `changePercent24h`, `high24h`, `low24h`, " +
                "`quoteVolume24h`, `sparkline`, `updatedAt` FROM `ticker_cache`"
        )
        db.execSQL("DROP TABLE `ticker_cache`")
        db.execSQL("ALTER TABLE `ticker_cache_new` RENAME TO `ticker_cache`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `klines_new` (" +
                "`provider` TEXT NOT NULL, `symbol` TEXT NOT NULL, `interval` TEXT NOT NULL, " +
                "`openTime` INTEGER NOT NULL, `closeTime` INTEGER NOT NULL, " +
                "`open` REAL NOT NULL, `high` REAL NOT NULL, `low` REAL NOT NULL, " +
                "`close` REAL NOT NULL, `volume` REAL NOT NULL, " +
                "PRIMARY KEY(`provider`, `symbol`, `interval`, `openTime`))"
        )
        db.execSQL(
            "INSERT INTO `klines_new` (`provider`, `symbol`, `interval`, `openTime`, `closeTime`, " +
                "`open`, `high`, `low`, `close`, `volume`) " +
                "SELECT 'BINANCE', `symbol`, `interval`, `openTime`, `closeTime`, " +
                "`open`, `high`, `low`, `close`, `volume` FROM `klines`"
        )
        db.execSQL("DROP TABLE `klines`")
        db.execSQL("ALTER TABLE `klines_new` RENAME TO `klines`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `watchlist_new` (" +
                "`provider` TEXT NOT NULL, `symbol` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`provider`, `symbol`))"
        )
        db.execSQL(
            "INSERT INTO `watchlist_new` (`provider`, `symbol`, `position`, `addedAt`) " +
                "SELECT 'BINANCE', `symbol`, `position`, `addedAt` FROM `watchlist`"
        )
        db.execSQL("DROP TABLE `watchlist`")
        db.execSQL("ALTER TABLE `watchlist_new` RENAME TO `watchlist`")

        db.execSQL("ALTER TABLE `alert_rules` ADD COLUMN `provider` TEXT NOT NULL DEFAULT 'BINANCE'")
    }
}
