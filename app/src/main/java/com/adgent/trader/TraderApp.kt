package com.adgent.trader

import android.app.Application
import android.content.Context
import com.adgent.trader.core.database.TraderDatabase
import com.adgent.trader.core.provider.AutoProviderRouter
import com.adgent.trader.core.provider.MarketCatalog
import com.adgent.trader.core.provider.PriceFeedHub
import com.adgent.trader.core.provider.ProviderRegistry
import com.adgent.trader.core.provider.SymbolMapper
import com.adgent.trader.core.service.LiveFeedLifecycleController
import com.adgent.trader.data.AlertRepository
import com.adgent.trader.data.ChartRepository
import com.adgent.trader.data.DataMode
import com.adgent.trader.data.MarketDataRepository
import com.adgent.trader.data.SettingsRepository
import com.adgent.trader.data.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * DI manuale: container unico con singleton lazy. Semplice, veloce da compilare,
 * nessun processor di annotazioni oltre a Room/KSP.
 */
class AppContainer(app: Application) {
    /** Context applicativo per avviare servizi (feed realtime) dal ViewModel. */
    val appContext: Context = app

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // keepalive per i WebSocket; ignorato dalle REST
        .build()

    private val mapper = SymbolMapper()
    private val db: TraderDatabase = TraderDatabase.build(app)

    val providerRegistry = ProviderRegistry(okHttp, mapper, appScope)
    val marketCatalog = MarketCatalog(db.symbolsDao(), mapper)

    val watchlistRepo = WatchlistRepository(db.watchlistDao()).apply {
        // Ogni modifica ai preferiti ridisegna subito i widget home screen.
        onChanged = { com.adgent.trader.core.work.WidgetUpdateWorker.enqueueNow(app) }
    }
    val alertRepo = AlertRepository(db.alertDao())
    val settingsRepo = SettingsRepository(app)

    val autoProviderRouter = AutoProviderRouter(mapper, providerRegistry)
    val priceFeedHub = PriceFeedHub(
        providerRegistry, watchlistRepo, settingsRepo, alertRepo, autoProviderRouter, mapper,
        db.tickerCacheDao(), appScope,
    )
    val marketDataRepo = MarketDataRepository(
        marketCatalog, providerRegistry, priceFeedHub, db.tickerCacheDao(), mapper,
    )
    val chartRepo = ChartRepository(providerRegistry, db.klinesDao())

    /** Chiude gli stream live della watchlist in background in modalità Risparmio. */
    val liveFeedLifecycleController = LiveFeedLifecycleController(settingsRepo, priceFeedHub, appScope)
}

class TraderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Live hub della watchlist: parte subito, si autoregola su watchlist/
        // impostazioni/salute provider e in SAVER chiude gli stream in background.
        container.priceFeedHub.start()
        container.liveFeedLifecycleController.attach()
        // Canali notifica + feed realtime se la modalità dati lo prevede.
        com.adgent.trader.core.notifications.Notifications.ensureChannels(this)
        // Widget home screen: refresh periodico 15 min + subito ad ogni apertura app.
        com.adgent.trader.core.work.WidgetUpdateWorker.schedule(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val mode = runCatching {
                container.settingsRepo.settings.first().dataMode
            }.getOrDefault(DataMode.SAVER)
            if (mode == DataMode.REALTIME) {
                com.adgent.trader.core.service.PriceFeedController.start(this@TraderApp)
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TraderApp).container
