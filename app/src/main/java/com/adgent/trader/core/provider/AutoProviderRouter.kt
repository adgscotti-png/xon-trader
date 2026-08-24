package com.adgent.trader.core.provider

/**
 * Decide la sorgente dati effettiva per una coppia in modalità Auto.
 * Ordine: override per-coin → default configurato → miglior provider disponibile
 * (per priorità). Un provider DOWN viene scavalcato: il banner spiega il fallback.
 */
class AutoProviderRouter(
    private val mapper: SymbolMapper,
    private val registry: ProviderRegistry,
) {
    fun resolve(
        pair: CanonicalPair,
        perCoin: Map<String, String>,
        defaultProvider: ProviderId?,
        health: Map<ProviderId, ProviderHealth>,
    ): ProviderId {
        perCoin[pair.key]?.let { name ->
            ProviderId.fromName(name)?.let { override ->
                if (isUsable(override, health)) return override
            }
        }
        if (defaultProvider != null && isUsable(defaultProvider, health)) return defaultProvider
        val best = ProviderId.entries
            .sortedBy { it.defaultPriority }
            .firstOrNull { isUsable(it, health) && mapper.toProviderSymbol(it, pair) != null }
        return best ?: ProviderId.BINANCE
    }

    fun isUsable(p: ProviderId, health: Map<ProviderId, ProviderHealth>): Boolean =
        // Un provider assente da health NON è registrato: mai instradare verso di lui.
        health[p]?.let { it.state != ProviderState.DOWN } ?: false

    /** Motivo del fallback per il banner, o null se non c'è fallback. */
    fun fallbackReason(requested: ProviderId?, effective: ProviderId): String? =
        if (requested != null && requested != effective)
            "${requested.label} temporarily unavailable — using ${effective.label}"
        else null
}
