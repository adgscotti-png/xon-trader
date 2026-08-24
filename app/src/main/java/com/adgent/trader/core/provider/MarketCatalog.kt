package com.adgent.trader.core.provider

import com.adgent.trader.core.database.SymbolEntity
import com.adgent.trader.core.database.SymbolsDao
import java.util.concurrent.ConcurrentHashMap

/** Catalogo delle coppie per provider (exchangeInfo-like) persistito in Room. */
class MarketCatalog(
    private val symbolsDao: SymbolsDao,
    private val mapper: SymbolMapper,
) {
    private val ensured = ConcurrentHashMap.newKeySet<ProviderId>()

    /** Popola (una volta) il catalogo di un provider; non fallisce se è giù. */
    suspend fun ensureProviderCatalog(provider: MarketDataProvider) {
        if (!ensured.add(provider.id)) return
        runCatching {
            val fresh = provider.refreshCatalog().map { s ->
                SymbolEntity(s.provider.name, s.pair.compact, s.pair.base, s.pair.quote)
            }
            if (fresh.isEmpty()) return@runCatching
            symbolsDao.deleteForProvider(provider.id.name)
            symbolsDao.insertAll(fresh)
        }
    }

    /** Ritenta il catalogo di un provider andato giù al primo tentativo. */
    suspend fun retryProviderCatalog(provider: MarketDataProvider) {
        ensured.remove(provider.id)
        ensureProviderCatalog(provider)
    }

    suspend fun allFor(provider: ProviderId): List<SymbolEntity> =
        symbolsDao.allFor(provider.name)

    suspend fun all(): List<SymbolEntity> = symbolsDao.all()

    fun isEligible(provider: ProviderId, pair: CanonicalPair): Boolean =
        mapper.toProviderSymbol(provider, pair) != null
}
