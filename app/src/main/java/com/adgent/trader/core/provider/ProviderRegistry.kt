package com.adgent.trader.core.provider

import com.adgent.trader.core.network.BitfinexApi
import com.adgent.trader.core.network.BybitApi
import com.adgent.trader.core.network.BinanceApi
import com.adgent.trader.core.network.CoinbaseApi
import com.adgent.trader.core.network.KrakenApi
import com.adgent.trader.core.network.KucoinApi
import com.adgent.trader.core.network.OkxApi
import kotlinx.coroutines.CoroutineScope
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
    scope: CoroutineScope,
) {
    // coerceInputValues: le API keyless restituiscono spesso `null` al posto
    // di una stringa numerica (es. KuCoin `last:null`) → coerce sul default.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

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
        put(
            ProviderId.COINBASE,
            CoinbaseProvider(
                api = retrofit("https://api.exchange.coinbase.com/", okHttp).create(CoinbaseApi::class.java),
                client = okHttp,
                mapper = mapper,
            )
        )
        put(
            ProviderId.OKX,
            OkxProvider(
                api = retrofit("https://www.okx.com/", okHttp).create(OkxApi::class.java),
                client = okHttp,
                mapper = mapper,
            )
        )
        put(
            ProviderId.BITFINEX,
            BitfinexProvider(
                api = retrofit("https://api-pub.bitfinex.com/", okHttp).create(BitfinexApi::class.java),
                client = okHttp,
                mapper = mapper,
            )
        )
        put(
            ProviderId.KUCOIN,
            KucoinProvider(
                api = retrofit("https://api.kucoin.com/", okHttp).create(KucoinApi::class.java),
                scope = scope,
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
