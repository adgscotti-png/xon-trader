package com.adgent.trader.core.provider

import com.adgent.trader.core.model.Kline
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.model.Ticker
import com.adgent.trader.core.model.Timeframe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Port: un exchange di dati di mercato con API pubbliche (senza chiave).
 * Il contratto lavora in SIMBOLI CANONICI (`CanonicalPair.compact`): la
 * stringa nativa dell'exchange esiste solo dentro l'adapter.
 */
interface MarketDataProvider {
    val id: ProviderId
    val displayName: String
    val rateLimit: RateLimit

    /** Salute corrente (circuit breaker). */
    val health: StateFlow<ProviderHealth>
    fun state(): ProviderState

    /** Rileva la disponibilità (ping). */
    suspend fun ping(): Boolean

    /** Catalogo completo delle coppie attive dell'exchange. */
    suspend fun refreshCatalog(): List<ProviderSymbol>

    /** Istantanee 24h per i simboli canonici richiesti (batch throttlato). */
    suspend fun tickers24h(symbols: Collection<String>): List<Ticker>

    /** Istantanee 24h dell'intero mercato (SOLO ranking di tab visibile). */
    suspend fun tickers24hAll(): List<Ticker>

    /** Candele per timeframe (simbolo canonico). */
    suspend fun klines(symbol: String, tf: Timeframe, limit: Int): List<Kline>

    /** Flusso tick live normalizzati (simbolo canonico, provider valorizzato). */
    fun tickFlow(): Flow<List<PriceTick>>

    /** Live stream (usato SOLO dalla watchlist ≤20 coppie). */
    fun connectStream()
    fun disconnectStream()
    fun subscribeStream(symbols: Collection<String>)
    fun unsubscribeStream(symbols: Collection<String>)
}

/** Tronco comune: rate limiter + circuit breaker + health flow condivisi. */
abstract class BaseMarketProvider(
    final override val id: ProviderId,
    final override val displayName: String,
    final override val rateLimit: RateLimit,
    protected val mapper: SymbolMapper,
) : MarketDataProvider {

    protected val limiter = RateLimiter(rateLimit.maxRequests, rateLimit.windowMs)
    protected val breaker = CircuitBreaker()

    private val _health = MutableStateFlow(ProviderHealth(id, ProviderState.OK))
    final override val health: StateFlow<ProviderHealth> = _health.asStateFlow()

    final override fun state(): ProviderState = breaker.state()

    protected fun updateHealth() {
        _health.value = ProviderHealth(id, breaker.state())
    }

    /** Esegue [block] sotto rate limit, registrando successo/fallimento sul breaker. */
    protected suspend fun <T> guarded(block: suspend () -> T): T {
        limiter.acquire()
        return try {
            val result = block()
            breaker.onSuccess()
            updateHealth()
            result
        } catch (t: Throwable) {
            breaker.onFailure()
            updateHealth()
            throw t
        }
    }

}
