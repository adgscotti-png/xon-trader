package com.adgent.trader

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.adgent.trader.data.AppStyle
import com.adgent.trader.data.Settings
import com.adgent.trader.data.ThemeMode
import com.adgent.trader.ui.alerts.AlertsScreen
import com.adgent.trader.ui.coin.CoinDetailScreen
import com.adgent.trader.ui.favorites.FavoritesScreen
import com.adgent.trader.ui.settings.SettingsScreen
import com.adgent.trader.ui.markets.MarketsScreen
import com.adgent.trader.ui.theme.AdgentTraderTheme

// FragmentActivity: richiesta da androidx.biometric.BiometricPrompt per il blocco app.
class MainActivity : FragmentActivity() {

    /** Ultimo intent ricevuto: condiviso con la composizione così i deep link
     *  di onNewIntent (widget/notifiche) ricollegano la navigazione. */
    private var pendingIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIntent = intent
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
        )
        setContent {
            AdgentApp(handleIntent = pendingIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
    }
}

private data class TopLevelItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun AdgentApp(handleIntent: Intent?) {
    val container = androidx.compose.ui.platform.LocalContext.current.appContainer
    val settings by container.settingsRepo.settings
        .collectAsStateWithLifecycle(initialValue = null)

    val darkTheme = when (settings?.themeMode ?: ThemeMode.SYSTEM) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    AdgentTraderTheme(
        darkTheme = darkTheme,
        appStyle = settings?.appStyle ?: AppStyle.CLASSIC,
    ) {
        com.adgent.trader.ui.lock.AppLockGate(enabled = settings?.appLock == true) {
            RootNav(settings = settings, handleIntent = handleIntent)
        }
    }
}

@Composable
private fun RootNav(settings: Settings?, handleIntent: Intent?) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val tabs = listOf(
        TopLevelItem("markets", "Markets", Icons.Rounded.CandlestickChart),
        TopLevelItem("favorites", "Favorites", Icons.Rounded.Star),
        TopLevelItem("alerts", "Alerts", Icons.Outlined.Notifications),
        TopLevelItem("settings", "Settings", Icons.Outlined.Settings),
    )
    val isTopLevel = tabs.any { it.route == currentRoute }

    // Deep link da widget/notifiche (adgent://coin/…): il NavHost consuma già il
    // deep link di COLD start; qui gestiamo anche gli intent successivi
    // (onNewIntent con app in background → prima apriva l'ultima schermata).
    // Guardia: se siamo già sul dettaglio della STESSA coin+provider non ripetiamo.
    LaunchedEffect(handleIntent) {
        val i = handleIntent ?: return@LaunchedEffect
        if (i.action != Intent.ACTION_VIEW) return@LaunchedEffect
        val data = i.data ?: return@LaunchedEffect
        val targetSymbol = data.pathSegments?.lastOrNull() ?: return@LaunchedEffect
        val targetProvider = data.getQueryParameter("provider") ?: "BINANCE"
        val entry = navController.currentBackStackEntry
        if (navController.currentDestination?.route?.startsWith("coin/") == true) {
            val curSym = entry?.arguments?.getString("symbol")
            val curProv = entry?.arguments?.getString("provider") ?: "BINANCE"
            if (curSym == targetSymbol && curProv == targetProvider) return@LaunchedEffect
        }
        navController.handleDeepLink(i)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (isTopLevel) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = {
                                Text(
                                    tab.label,
                                    fontWeight = if (currentRoute == tab.route) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "markets",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable("markets") {
                MarketsScreen(
                    onOpenCoin = { symbol, providerName ->
                        navController.navigate("coin/$symbol?provider=$providerName")
                    },
                )
            }
            composable("favorites") {
                FavoritesScreen(
                    onOpenCoin = { symbol, providerName ->
                        navController.navigate("coin/$symbol?provider=$providerName")
                    },
                )
            }
            composable("alerts") {
                AlertsScreen(
                    onOpenCoin = { symbol, providerName ->
                        navController.navigate("coin/$symbol?provider=$providerName")
                    },
                    onEditRule = { id ->
                        navController.navigate(if (id != null) "alertEdit?ruleId=$id" else "alertEdit")
                    },
                )
            }
            composable("settings") {
                SettingsScreen(onOpenWidgetStatus = { navController.navigate("widgetDiagnostics") })
            }
            composable("widgetDiagnostics") {
                com.adgent.trader.ui.widgets.WidgetDiagnosticsScreen(
                    onClose = { navController.popBackStack() },
                )
            }
            composable(
                route = "coin/{symbol}?provider={provider}",
                arguments = listOf(
                    navArgument("symbol") { type = NavType.StringType },
                    navArgument("provider") { type = NavType.StringType; defaultValue = "BINANCE" },
                ),
                // Il pattern provider-aware viene valutato PRIMA, così i tap da
                // widget/notifiche portano il provider; i deep link legacy
                // "adgent://coin/{symbol}" continuano a funzionare (default BINANCE).
                deepLinks = listOf(
                    navDeepLink { uriPattern = "adgent://coin/{symbol}?provider={provider}" },
                    navDeepLink { uriPattern = "adgent://coin/{symbol}" },
                ),
            ) { entry ->
                val symbol = entry.arguments?.getString("symbol") ?: "BTCUSDT"
                val provider = entry.arguments?.getString("provider") ?: "BINANCE"
                CoinDetailScreen(
                    symbol = symbol,
                    providerName = provider,
                    onClose = { navController.popBackStack() },
                    onCreateAlert = {
                        navController.navigate("alertEdit?symbol=$symbol&provider=$provider")
                    },
                )
            }
            // Query opzionali: senza navArgument dichiarati Navigation lascia null gli
            // argomenti assenti nella navigate() (ruleId per l'edit, symbol+provider da
            // coin detail). Mai `defaultValue = null` su NavType.StringType: a graph-build
            // lancia IllegalArgumentException (crash loop al primo frame).
            composable("alertEdit?ruleId={ruleId}&symbol={symbol}&provider={provider}") { entry ->
                val ruleId = entry.arguments?.getString("ruleId")?.toLongOrNull()
                val symbol = entry.arguments?.getString("symbol")
                val provider = entry.arguments?.getString("provider")
                com.adgent.trader.ui.alerts.AlertEditScreen(
                    ruleId = ruleId,
                    presetSymbol = symbol,
                    presetProvider = provider,
                    onClose = { navController.popBackStack() },
                )
            }
        }
    }
}
