package com.adgent.trader.core.provider

/** Salute di un provider: OK / DEGRADED (error rate alto) / DOWN (circuito aperto). */
enum class ProviderState { OK, DEGRADED, DOWN }

data class ProviderHealth(
    val id: ProviderId,
    val state: ProviderState,
    val lastError: String? = null,
)

/** Coppia nel catalogo di un provider, con il simbolo nativo dell'exchange. */
data class ProviderSymbol(
    val provider: ProviderId,
    val pair: CanonicalPair,
    val symbol: String,
)

/** Limite client-side: max richieste in una finestra temporale. */
data class RateLimit(val maxRequests: Int, val windowMs: Long)
