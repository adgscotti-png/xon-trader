package com.adgent.trader.ui.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import com.adgent.trader.core.common.NumberFormatMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Tipo widget: determina quali opzioni di configurazione sono mostrate. */
enum class WidgetKind { TICKER, WATCHLIST }

/**
 * Configurazione per-istanza del widget home screen (persistita in
 * SharedPreferences, una voce JSON per appWidgetId).
 *
 * - [symbol]: vuoto = automatico (primo preferito, altrimenti top volume).
 * - [textSizeSp]: dimensione del prezzo principale (preset per-kind in UI).
 * - [numberFormat]: come formattare il prezzo (virgole / senza / approssimato).
 * - [showChange] / [showTimestamp]: elementi opzionali per il testo essenziale.
 * - [rows]: righe visibili nel widget watchlist.
 */
@Serializable
data class WidgetConfig(
    val symbol: String = "",
    /** Provider del simbolo configurato (default Binance; il JSON vecchio resta valido). */
    val provider: String = "BINANCE",
    val textSizeSp: Int = 17,
    val numberFormat: NumberFormatMode = NumberFormatMode.AUTO,
    val showChange: Boolean = true,
    val showTimestamp: Boolean = true,
    val rows: Int = 5,
) {
    companion object {
        /** Preset dimensioni testo per il widget ticker (prezzo grande). */
        val TICKER_SIZES = listOf(14, 18, 24, 32, 40)
        /** Preset dimensioni testo per le righe del widget watchlist. */
        val LIST_SIZES = listOf(10, 12, 14, 17, 20)

        fun defaultFor(kind: WidgetKind): WidgetConfig = when (kind) {
            WidgetKind.TICKER -> WidgetConfig(textSizeSp = 18)
            WidgetKind.WATCHLIST -> WidgetConfig(textSizeSp = 12, rows = 5)
        }
    }
}

/**
 * Storage della configurazione widget + timestamp globale dell'ultimo refresh
 * dati (mostrato nei widget così l'utente sa a quando risalgono i prezzi).
 */
object WidgetConfigStore {

    private const val FILE = "adgent_widget_config"
    private const val KEY_REFRESH_MINUTES = "refresh_minutes"
    private const val KEY_LAST_UPDATE = "last_update_at"
    private const val KEY_LAST_MANUAL_REFRESH = "last_manual_refresh_at"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context, kind: WidgetKind, appWidgetId: Int): WidgetConfig {
        val raw = runCatching {
            prefs(context).getString(key(kind, appWidgetId), null)
        }.getOrNull() ?: return WidgetConfig.defaultFor(kind)
        return runCatching { json.decodeFromString<WidgetConfig>(raw) }
            .getOrDefault(WidgetConfig.defaultFor(kind))
    }

    fun save(context: Context, kind: WidgetKind, appWidgetId: Int, config: WidgetConfig) {
        runCatching {
            prefs(context).edit()
                .putString(key(kind, appWidgetId), json.encodeToString(config))
                .apply()
        }
    }

    fun delete(context: Context, kind: WidgetKind, appWidgetId: Int) {
        runCatching { prefs(context).edit().remove(key(kind, appWidgetId)).apply() }
    }

    /** True se questo widget ha una configurazione salvata (non i default). */
    fun isConfigured(context: Context, kind: WidgetKind, appWidgetId: Int): Boolean =
        prefs(context).contains(key(kind, appWidgetId))

    /** Tutte le voci memorizzate, per la schermata di diagnostica widget. */
    fun snapshot(context: Context): Map<String, String> =
        prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private fun key(kind: WidgetKind, appWidgetId: Int) =
        "${kind.name.lowercase()}_$appWidgetId"

    // ---------- Intervallo di refresh automatico (condiviso tra i widget) ----------

    /** Minimo Android per WorkManager periodico con app chiusa: 15 minuti. */
    const val MIN_REFRESH_MINUTES = 15

    fun getRefreshMinutes(context: Context): Int =
        prefs(context).getInt(KEY_REFRESH_MINUTES, MIN_REFRESH_MINUTES)
            .coerceAtLeast(MIN_REFRESH_MINUTES)

    fun setRefreshMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_REFRESH_MINUTES, minutes.coerceAtLeast(MIN_REFRESH_MINUTES))
            .apply()
    }

    // ---------- Timestamp ultimo aggiornamento dati ----------

    fun stampUpdate(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
    }

    fun lastUpdate(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UPDATE, 0L)

    // ---------- Debounce refresh manuale (pulsantino ↻ del widget) ----------

    /** Segna il refresh manuale (prima della fetch: i tap rapidi vengono ignorati). */
    fun stampManualRefresh(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_MANUAL_REFRESH, System.currentTimeMillis()).apply()
    }

    fun lastManualRefresh(context: Context): Long =
        prefs(context).getLong(KEY_LAST_MANUAL_REFRESH, 0L)
}
