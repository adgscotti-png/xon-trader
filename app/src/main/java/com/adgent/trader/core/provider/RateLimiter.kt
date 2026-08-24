package com.adgent.trader.core.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token bucket per throttling client-side delle REST pubbliche senza chiave:
 * rispetta i rate limit degli exchange (429) senza richiedere autenticazione.
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long,
) {
    private val mutex = Mutex()
    private var tokens = maxRequests.toDouble()
    private var lastRefill = System.currentTimeMillis()

    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    ((1.0 - tokens) * windowMs / maxRequests).toLong().coerceAtLeast(1L)
                }
            }
            if (waitMs == 0L) return
            delay(waitMs)
        }
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefill
        if (elapsed <= 0) return
        tokens = (tokens + elapsed.toDouble() / windowMs * maxRequests)
            .coerceAtMost(maxRequests.toDouble())
        lastRefill = now
    }
}
