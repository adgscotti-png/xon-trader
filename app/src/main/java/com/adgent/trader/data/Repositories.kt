package com.adgent.trader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adgent.trader.core.database.AlertDao
import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.database.WatchlistDao
import com.adgent.trader.core.database.WatchlistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ---------- Watchlist ----------

class WatchlistRepository(private val dao: WatchlistDao) {

    /** Notifica i listener (es. widget home screen) dopo ogni modifica. */
    var onChanged: (suspend () -> Unit)? = null

    companion object {
        val DEFAULTS = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT")
    }

    fun observe(): Flow<List<WatchlistEntity>> = dao.observeAll()

    suspend fun ensureDefaults() {
        if (dao.count() == 0) {
            DEFAULTS.forEachIndexed { i, s ->
                dao.upsert(WatchlistEntity(s, i, System.currentTimeMillis()))
            }
        }
    }

    suspend fun all(): List<WatchlistEntity> = dao.all()

    suspend fun add(symbol: String) {
        dao.upsert(WatchlistEntity(symbol, (dao.maxPosition() ?: -1) + 1, System.currentTimeMillis()))
        onChanged?.invoke()
    }

    suspend fun remove(symbol: String) {
        dao.delete(symbol)
        onChanged?.invoke()
    }

    suspend fun contains(symbol: String): Boolean = dao.all().any { it.symbol == symbol }

    /** Sostituisce la watchlist con l'ordine del backup. */
    suspend fun replaceAll(symbols: List<String>) {
        dao.clear()
        symbols.forEachIndexed { i, s ->
            dao.upsert(WatchlistEntity(s, i, System.currentTimeMillis()))
        }
        onChanged?.invoke()
    }
}

// ---------- Avvisi ----------

class AlertRepository(private val dao: AlertDao) {
    fun observeAll(): Flow<List<AlertRuleEntity>> = dao.observeAll()
    suspend fun enabledRules(): List<AlertRuleEntity> = dao.enabledRules()
    suspend fun all(): List<AlertRuleEntity> = dao.all()
    suspend fun byId(id: Long): AlertRuleEntity? = dao.byId(id)
    suspend fun save(rule: AlertRuleEntity): Long = dao.upsert(rule)
    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
    suspend fun markTriggered(id: Long, at: Long, enabled: Boolean) = dao.markTriggered(id, at, enabled)
    suspend fun delete(id: Long) = dao.delete(id)

    /** Ripristino da backup: sostituisce tutte le regole (nuovi id). */
    suspend fun replaceAll(rules: List<AlertRuleEntity>) {
        dao.clear()
        rules.forEach { dao.upsert(it) }
    }
}

// ---------- Impostazioni ----------

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Modalità dati: Realtime (WS in foreground service) o Risparmio (poll 15 min). */
enum class DataMode { REALTIME, SAVER }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dataMode: DataMode = DataMode.REALTIME,
    /** Blocco app con biometria/PIN del dispositivo (F5). */
    val appLock: Boolean = false,
    val onboarded: Boolean = false,
)

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dataMode = p[KEY_MODE]?.let { runCatching { DataMode.valueOf(it) }.getOrNull() }
                ?: DataMode.REALTIME,
            onboarded = p[KEY_ONBOARDED] == "1",
            appLock = p[KEY_APP_LOCK] == "1",
        )
    }

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[KEY_THEME] = mode.name }
    suspend fun setDataMode(mode: DataMode) = store.edit { it[KEY_MODE] = mode.name }
    suspend fun setOnboarded() = store.edit { it[KEY_ONBOARDED] = "1" }
    suspend fun setAppLock(enabled: Boolean) = store.edit { it[KEY_APP_LOCK] = if (enabled) "1" else "0" }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_MODE = stringPreferencesKey("data_mode")
        val KEY_ONBOARDED = stringPreferencesKey("onboarded")
        val KEY_APP_LOCK = stringPreferencesKey("app_lock")
    }
}
