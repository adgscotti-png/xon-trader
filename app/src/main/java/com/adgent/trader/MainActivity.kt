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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CandlestickChart
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.adgent.trader.data.Settings
import com.adgent.trader.data.ThemeMode
import com.adgent.trader.ui.alerts.AlertsScreen
import com.adgent.trader.ui.coin.CoinDetailScreen
import com.adgent.trader.ui.settings.SettingsScreen
import com.adgent.trader.ui.markets.MarketsScreen
import com.adgent.trader.ui.theme.AdgentTraderTheme

// FragmentActivity: richiesta da androidx.biometric.BiometricPrompt per il blocco app.
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
        )
        setContent {
            AdgentApp(handleIntent = intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
    AdgentTraderTheme(darkTheme = darkTheme) {
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
        TopLevelItem("alerts", "Alerts", Icons.Outlined.Notifications),
        TopLevelItem("settings", "Settings", Icons.Outlined.Settings),
    )
    val isTopLevel = tabs.any { it.route == currentRoute }

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
                MarketsScreen(onOpenCoin = { navController.navigate("coin/$it") })
            }
            composable("alerts") {
                AlertsScreen(
                    onOpenCoin = { navController.navigate("coin/$it") },
                    onEditRule = { id -> navController.navigate("alertEdit?ruleId=$id") },
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
                route = "coin/{symbol}",
                deepLinks = listOf(navDeepLink { uriPattern = "adgent://coin/{symbol}" }),
            ) { entry ->
                val symbol = entry.arguments?.getString("symbol") ?: "BTCUSDT"
                CoinDetailScreen(
                    symbol = symbol,
                    onClose = { navController.popBackStack() },
                    onCreateAlert = { navController.navigate("alertEdit?symbol=$symbol") },
                )
            }
            composable("alertEdit?ruleId={ruleId}&symbol={symbol}") { entry ->
                val ruleId = entry.arguments?.getString("ruleId")?.toLongOrNull()
                val symbol = entry.arguments?.getString("symbol")
                com.adgent.trader.ui.alerts.AlertEditScreen(
                    ruleId = ruleId,
                    presetSymbol = symbol,
                    onClose = { navController.popBackStack() },
                )
            }
        }
    }
}
