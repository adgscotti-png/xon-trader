package com.adgent.trader.core.provider

/**
 * Circuit breaker per provider: errori consecutivi → stato DOWN temporaneo
 * (chiamate fail-fast, probe half-open dopo [openTimeoutMs]). Errori blandi
 * (pochi 429) → stato DEGRADED, così il router può preferire altri provider
 * senza interrompere del tutto il flusso.
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openTimeoutMs: Long = 30_000,
) {
    @Volatile private var consecutiveFailures = 0
    @Volatile private var openSince: Long = 0L

    fun onSuccess() {
        consecutiveFailures = 0
        openSince = 0L
    }

    fun onFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold && openSince == 0L) {
            openSince = System.currentTimeMillis()
        }
    }

    fun isOpen(): Boolean {
        if (openSince == 0L) return false
        if (System.currentTimeMillis() - openSince > openTimeoutMs) {
            // half-open: la prossima chiamata viene lasciata passare come probe.
            openSince = 0L
            consecutiveFailures = 0
            return false
        }
        return true
    }

    fun state(): ProviderState = when {
        isOpen() -> ProviderState.DOWN
        consecutiveFailures >= 2 -> ProviderState.DEGRADED
        else -> ProviderState.OK
    }
}
