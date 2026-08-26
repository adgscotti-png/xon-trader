package com.adgent.trader.core.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cosa sta guardando l'utente in questo momento: guida la sottoscrizione WebSocket. */
sealed interface LiveFocusSpec {
    /** Nessuna schermata prezzi attiva (processo in background, Alerts, Settings…). */
    data object Idle : LiveFocusSpec

    /** Tab Preferiti: tutta la watchlist (≤20 coppie). */
    data object Favorites : LiveFocusSpec

    /** Griglia mercati: solo le righe visibili a schermo (viewport). */
    data class Markets(val viewport: Map<ProviderId, Set<String>> = emptyMap()) : LiveFocusSpec

    /** Dettaglio coin: quella coppia su quel provider, anche se non in watchlist. */
    data class Coin(val provider: ProviderId, val symbol: String) : LiveFocusSpec
}

/**
 * Traccia la destinazione corrente della navigazione (route) + il viewport della
 * griglia mercati, così il [PriceFeedHub] sottoscrive in WS esattamente ciò che
 * l'utente vede: zero stream per schermate non-prezzo, massima reattività su
 * Preferiti/Markets/Coin. Pubblico: [spec] come StateFlow per il hub.
 */
class LiveFocus {
    private val _spec = MutableStateFlow<LiveFocusSpec>(LiveFocusSpec.Idle)
    val spec: StateFlow<LiveFocusSpec> = _spec.asStateFlow()

    /** Viewport dell'ultima visita alla griglia: riusato al ritorno sul tab. */
    @Volatile private var lastMarketsViewport: Map<ProviderId, Set<String>> = emptyMap()

    /** Chiamato a ogni cambio di rotta della navigazione (da MainActivity). */
    fun onDestination(route: String?, symbol: String?, provider: String?) {
        val next = when (route) {
            "favorites" -> LiveFocusSpec.Favorites
            "markets" -> LiveFocusSpec.Markets(lastMarketsViewport)
            "coin/{symbol}?provider={provider}" -> LiveFocusSpec.Coin(
                provider?.let { ProviderId.fromName(it) } ?: ProviderId.BINANCE,
                symbol.orEmpty(),
            )
            else -> LiveFocusSpec.Idle
        }
        _spec.value = next
    }

    /** La griglia mercati segnala le righe visibili (throttled, cap [MAX_FOCUS_SYMBOLS]). */
    fun onMarketsViewport(viewport: Map<ProviderId, Set<String>>) {
        val capped = viewport.mapValues { (_, syms) -> syms.take(MAX_FOCUS_SYMBOLS).toSet() }
        lastMarketsViewport = capped
        val cur = _spec.value
        if (cur is LiveFocusSpec.Markets) {
            _spec.value = cur.copy(viewport = capped)
        }
    }

    companion object {
        /** Cap dei simboli live simultanei per la griglia mercati. */
        const val MAX_FOCUS_SYMBOLS = 24
    }
}
