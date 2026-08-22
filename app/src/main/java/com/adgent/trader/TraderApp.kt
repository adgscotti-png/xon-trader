package com.adgent.trader

import android.app.Application
import android.content.Context
import com.adgent.trader.core.database.TraderDatabase
import com.adgent.trader.core.network.BinanceApi
import com.adgent.trader.core.network.BinanceWebSocket
import com.adgent.trader.data.AlertRepository
import com.adgent.trader.data.DataMode
import com.adgent.trader.data.ChartRepository
import com.adgent.trader.data.SettingsRepository
import com.adgent.trader.data.TickerRepository
import com.adgent.trader.data.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * DI manuale: container unico con singleton lazy. Semplice, veloce da compilare,
 * nessun processor di annotazioni oltre a Room/KSP.
 */
class AppContainer(app: Application) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://data-api.binance.vision/")
        .client(okHttp)
        .addConverterFactory(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .asConverterFactory("application/json".toMediaType())
        )
        .build()

    val api: BinanceApi = retrofit.create(BinanceApi::class.java)
    val ws: BinanceWebSocket = BinanceWebSocket()
    private val db: TraderDatabase = TraderDatabase.build(app)

    val tickerRepo = TickerRepository(api, ws, db.symbolsDao(), db.tickerCacheDao(), db.klinesDao(), appScope)
    val chartRepo = ChartRepository(api, db.klinesDao())
    val watchlistRepo = WatchlistRepository(db.watchlistDao()).apply {
        // Ogni modifica ai preferiti ridisegna subito i widget home screen.
        onChanged = { com.adgent.trader.core.work.WidgetUpdateWorker.enqueueNow(app) }
    }
    val alertRepo = AlertRepository(db.alertDao())
    val settingsRepo = SettingsRepository(app)
}

class TraderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Canali notifica + feed realtime se la modalità dati lo prevede.
        com.adgent.trader.core.notifications.Notifications.ensureChannels(this)
        // Widget home screen: refresh periodico 15 min + subito ad ogni apertura app.
        com.adgent.trader.core.work.WidgetUpdateWorker.schedule(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val mode = runCatching {
                container.settingsRepo.settings.first().dataMode
            }.getOrDefault(DataMode.REALTIME)
            if (mode == DataMode.REALTIME) {
                com.adgent.trader.core.service.PriceFeedController.start(this@TraderApp)
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TraderApp).container
