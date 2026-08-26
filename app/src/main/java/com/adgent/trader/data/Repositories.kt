package com.adgent.trader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.adgent.trader.core.database.AlertDao
import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.database.WatchlistDao
import com.adgent.trader.core.database.WatchlistEntity
import com.adgent.trader.core.provider.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---------- Watchlist (coppia provider+simbolo, cap 20) ----------

class WatchlistRepository(private val dao: WatchlistDao) {

    /** Notifica i listener (es. widget home screen) dopo ogni modifica. */
    var onChanged: (suspend () -> Unit)? = null

    companion object {
        const val MAX_SIZE = 20
        val DEFAULTS = listOf(
            "BTCUSDT" to "BINANCE",
            "ETHUSDT" to "BINANCE",
            "SOLUSDT" to "BINANCE",
            "XRPUSDT" to "BINANCE",
            "DOGEUSDT" to "BINANCE",
        )
    }

    fun observe(): Flow<List<WatchlistEntity>> = dao.observeAll()

    suspend fun ensureDefaults() {
        if (dao.count() == 0) {
            DEFAULTS.forEachIndexed { i, (symbol, provider) ->
                dao.upsert(WatchlistEntity(provider, symbol, i, System.currentTimeMillis()))
            }
        }
    }

    suspend fun all(): List<WatchlistEntity> = dao.all()

    /** Aggiunge una coppia. Ritorna false se la watchlist ha già [MAX_SIZE] voci. */
    suspend fun add(provider: ProviderId, symbol: String): Boolean {
        if (dao.count() >= MAX_SIZE) return false
        dao.upsert(
            WatchlistEntity(provider.name, symbol, (dao.maxPosition() ?: -1) + 1, System.currentTimeMillis())
        )
        onChanged?.invoke()
        return true
    }

    suspend fun remove(provider: ProviderId, symbol: String) {
        dao.delete(provider.name, symbol)
        onChanged?.invoke()
    }

    suspend fun contains(provider: ProviderId, symbol: String): Boolean =
        dao.bySymbol(provider.name, symbol) != null

    /** Sostituisce la watchlist con l'ordine del backup. */
    suspend fun replaceAll(items: List<WatchlistEntity>) {
        dao.clear()
        items.forEachIndexed { i, it -> dao.upsert(it.copy(position = i, addedAt = System.currentTimeMillis())) }
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

/** Stile visivo: Classic (flat AMOLED) o Neon (contorno ciano glow). */
enum class AppStyle { CLASSIC, NEON }

/** Stile grafico della pagina Preferiti: indipendente dallo stile globale. */
enum class FavoritesStyle(val label: String) {
    CLASSIC("Classic"),
    NEON("Neon"),
    RETRO("Retro 8-bit"),
    SPLITFLAP("Split-flap"),
}

/** Modalità dati: Realtime (WS in foreground service) o Risparmio (poll 15 min). */
enum class DataMode { REALTIME, SAVER }

/**
 * Sorgente dati selezionabile dall'utente. AUTO NON è un provider: delega la
 * scelta ad [AutoProviderRouter] (override per-coin → default → priorità → failover).
 */
enum class ProviderSelection(val label: String) {
    AUTO("Auto"),
    BINANCE("Binance"),
    BYBIT("Bybit"),
    KRAKEN("Kraken"),
    COINBASE("Coinbase"),
    OKX("OKX"),
    BITFINEX("Bitfinex"),
    KUCOIN("KuCoin"),
    ;

    val providerId: ProviderId? get() = ProviderId.fromName(name)

    companion object {
        fun fromName(name: String?): ProviderSelection? =
            name?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}

@Serializable
data class PerCoinProviders(val map: Map<String, String> = emptyMap())

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appStyle: AppStyle = AppStyle.CLASSIC,
    /** Stile grafico della pagina Preferiti (Classic/Neon/Retro/Split-flap). */
    val favoritesStyle: FavoritesStyle = FavoritesStyle.CLASSIC,
    val dataMode: DataMode = DataMode.SAVER,
    /** Sorgente dati di default per le nuove coppie (AUTO = migliore disponibile). */
    val defaultProvider: ProviderSelection = ProviderSelection.AUTO,
    /** Override per-coin: CanonicalPair.key ("BTC/USDT") → ProviderId.name. */
    val perCoinProviders: Map<String, String> = emptyMap(),
    /** Blocco app con biometria/PIN del dispositivo (F5). */
    val appLock: Boolean = false,
    val onboarded: Boolean = false,
)

class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsStore
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            themeMode = p[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            appStyle = p[KEY_APP_STYLE]?.let { runCatching { AppStyle.valueOf(it) }.getOrNull() }
                ?: AppStyle.CLASSIC,
            favoritesStyle = p[KEY_FAVORITES_STYLE]
                ?.let { runCatching { FavoritesStyle.valueOf(it) }.getOrNull() }
                ?: FavoritesStyle.CLASSIC,
            dataMode = p[KEY_MODE]?.let { runCatching { DataMode.valueOf(it) }.getOrNull() }
                ?: DataMode.SAVER,
            defaultProvider = p[KEY_DEFAULT_PROVIDER]
                ?.let { ProviderSelection.fromName(it) } ?: ProviderSelection.AUTO,
            perCoinProviders = p[KEY_PER_COIN_PROVIDERS]
                ?.let { runCatching { json.decodeFromString<PerCoinProviders>(it).map }.getOrNull() }
                ?: emptyMap(),
            onboarded = p[KEY_ONBOARDED] == "1",
            appLock = p[KEY_APP_LOCK] == "1",
        )
    }

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[KEY_THEME] = mode.name }
    suspend fun setAppStyle(style: AppStyle) = store.edit { it[KEY_APP_STYLE] = style.name }
    suspend fun setFavoritesStyle(style: FavoritesStyle) =
        store.edit { it[KEY_FAVORITES_STYLE] = style.name }
    suspend fun setDataMode(mode: DataMode) = store.edit { it[KEY_MODE] = mode.name }
    suspend fun setDefaultProvider(sel: ProviderSelection) =
        store.edit { it[KEY_DEFAULT_PROVIDER] = sel.name }
    suspend fun setOnboarded() = store.edit { it[KEY_ONBOARDED] = "1" }
    suspend fun setAppLock(enabled: Boolean) = store.edit { it[KEY_APP_LOCK] = if (enabled) "1" else "0" }

    /** Override per-coin: [providerId] null rimuove l'override (torna ad Auto/default). */
    suspend fun setPerCoinProvider(pairKey: String, providerId: ProviderId?) = store.edit { p ->
        val current = p[KEY_PER_COIN_PROVIDERS]
            ?.let { runCatching { json.decodeFromString<PerCoinProviders>(it).map }.getOrNull() }
            ?: emptyMap()
        val next = if (providerId == null) current - pairKey else current + (pairKey to providerId.name)
        p[KEY_PER_COIN_PROVIDERS] = json.encodeToString(PerCoinProviders.serializer(), PerCoinProviders(next))
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_APP_STYLE = stringPreferencesKey("app_style")
        val KEY_FAVORITES_STYLE = stringPreferencesKey("favorites_style")
        val KEY_MODE = stringPreferencesKey("data_mode")
        val KEY_DEFAULT_PROVIDER = stringPreferencesKey("default_provider")
        val KEY_PER_COIN_PROVIDERS = stringPreferencesKey("per_coin_providers")
        val KEY_ONBOARDED = stringPreferencesKey("onboarded")
        val KEY_APP_LOCK = stringPreferencesKey("app_lock")
    }
}
