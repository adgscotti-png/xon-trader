package com.adgent.trader.core.provider

import com.adgent.trader.core.network.BybitApi
import com.adgent.trader.core.network.BinanceApi
import com.adgent.trader.core.network.KrakenApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Costruisce gli adapter con un unico OkHttp (connection pooling) e Retrofit
 * per-provider. `health` è il flusso aggregato per il router/banner.
 */
class ProviderRegistry(
    okHttp: OkHttpClient,
    mapper: SymbolMapper,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val adapters: Map<ProviderId, MarketDataProvider> = buildMap {
        put(
            ProviderId.BINANCE,
            BinanceProvider(
                api = retrofit("https://data-api.binance.vision/", okHttp).create(BinanceApi::class.java),
                client = okHttp,
                mapper = mapper,
            )
        )
        put(
            ProviderId.BYBIT,
            BybitProvider(
                api = retrofit("https://api.bybit.com/", okHttp).create(BybitApi::class.java),
                client = okHttp,
                mapper = mapper,
            )
        )
        put(
            ProviderId.KRAKEN,
            KrakenProvider(
                api = retrofit("https://api.kraken.com/", okHttp).create(KrakenApi::class.java),
                client = okHttp,
                mapper = mapper,
            )
        )
    }

    val health: Flow<Map<ProviderId, ProviderHealth>> =
        combine(adapters.values.map { it.health }) { arr ->
            @Suppress("UNCHECKED_CAST")
            (arr as Array<ProviderHealth>).associateBy { it.id }
        }

    fun get(id: ProviderId): MarketDataProvider = adapters.getValue(id)
    fun getOrNull(id: ProviderId): MarketDataProvider? = adapters[id]
    fun all(): List<MarketDataProvider> = adapters.values.toList()
    fun enabledIds(): Set<ProviderId> = adapters.keys

    private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
}
