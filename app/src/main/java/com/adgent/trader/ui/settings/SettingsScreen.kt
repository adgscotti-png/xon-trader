package com.adgent.trader.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.updateAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
import com.adgent.trader.core.service.PriceFeedController
import com.adgent.trader.data.AppStyle
import com.adgent.trader.data.DataMode
import com.adgent.trader.data.Settings
import com.adgent.trader.data.ThemeMode
import com.adgent.trader.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<Settings?> = container.settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val repo = container.settingsRepo

    /** Chips del default provider: AUTO + solo i provider registrati (le ondate li aggiungono). */
    val providerOptions: List<com.adgent.trader.data.ProviderSelection> =
        com.adgent.trader.data.ProviderSelection.entries.filter { sel ->
            sel.providerId == null || sel.providerId in container.providerRegistry.enabledIds()
        }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { repo.setTheme(mode) }

    /** Cambia lo stile visivo e ridisegna subito i widget home (Classic/Neon). */
    fun setAppStyle(context: android.content.Context, style: AppStyle) = viewModelScope.launch {
        repo.setAppStyle(style)
        com.adgent.trader.ui.widgets.TickerWidget().updateAll(context)
        com.adgent.trader.ui.widgets.WatchlistWidget().updateAll(context)
    }

    /** Sorgente dati di default per le nuove coppie (AUTO = migliore disponibile). */
    fun setDefaultProvider(sel: com.adgent.trader.data.ProviderSelection) =
        viewModelScope.launch { repo.setDefaultProvider(sel) }

    /** Cambia modalità dati e applica subito il servizio realtime on/off. */
    fun setDataMode(context: android.content.Context, mode: DataMode) = viewModelScope.launch {
        repo.setDataMode(mode)
        PriceFeedController.applyMode(context, mode)
    }

    fun setAppLock(enabled: Boolean) = viewModelScope.launch { repo.setAppLock(enabled) }

    /** Esporta watchlist + avvisi su file JSON scelto con SAF. */
    suspend fun exportBackup(context: android.content.Context, uri: android.net.Uri): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val alerts = container.alertRepo.all().map {
                    com.adgent.trader.data.BackupAlert(
                        symbol = it.symbol,
                        type = it.type,
                        threshold = it.threshold,
                        repeatable = it.repeatable,
                        note = it.note,
                        enabled = it.enabled,
                        createdAt = it.createdAt,
                        provider = it.provider,
                    )
                }
                val data = com.adgent.trader.data.BackupData(
                    exportedAt = System.currentTimeMillis(),
                    watchlist = container.watchlistRepo.all().map {
                        com.adgent.trader.data.BackupWatchItem(symbol = it.symbol, provider = it.provider)
                    },
                    alerts = alerts,
                )
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(com.adgent.trader.data.BackupCodec.encode(data).toByteArray(Charsets.UTF_8))
                } ?: error("output non disponibile")
            }.isSuccess
        }

    /** Ripristina da file JSON: sostituisce watchlist e avvisi. Ritorna (preferiti, avvisi). */
    suspend fun importBackup(
        context: android.content.Context,
        uri: android.net.Uri,
    ): Pair<Int, Int>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("input non disponibile")
            val data = com.adgent.trader.data.BackupCodec.decode(text) ?: error("formato non valido")
            container.alertRepo.replaceAll(
                data.alerts.map { a ->
                    com.adgent.trader.core.database.AlertRuleEntity(
                        id = 0L,
                        symbol = a.symbol,
                        type = a.type,
                        threshold = a.threshold,
                        repeatable = a.repeatable,
                        note = a.note,
                        enabled = a.enabled,
                        createdAt = a.createdAt,
                        lastTriggeredAt = null,
                        provider = a.provider,
                    )
                },
            )
            container.watchlistRepo.replaceAll(
                data.watchlist.map { w ->
                    com.adgent.trader.core.database.WatchlistEntity(
                        provider = w.provider,
                        symbol = w.symbol,
                        position = 0,
                        addedAt = System.currentTimeMillis(),
                    )
                },
            )
            data.watchlist.size to data.alerts.size
        }.getOrNull()
    }
}

/**
 * Impostazioni: tema, modalità dati (realtime vs risparmio con guida anti-kill
 * per marca), info e disclaimer.
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = appViewModel { SettingsViewModel(it) },
    onOpenWidgetStatus: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = settings ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // SAF: esporta/ripristina il backup dei dati (watchlist + avvisi).
    // Il launch è protetto: su alcune ROM il picker documenti può mancare o
    // fallire (ActivityNotFoundException/SecurityException) e senza catch
    // l'app crasha proprio sul tap del bottone.
    fun safeLaunch(launch: () -> Unit) {
        try {
            launch()
        } catch (e: Exception) {
            runCatching {
                android.widget.Toast.makeText(
                    context,
                    "File picker unavailable: ${e.javaClass.simpleName}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            scope.launch {
                val ok = vm.exportBackup(context, it)
                android.widget.Toast.makeText(
                    context,
                    if (ok) "Backup exported" else "Export failed",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            scope.launch {
                val res = vm.importBackup(context, it)
                android.widget.Toast.makeText(
                    context,
                    if (res != null) "Restored ${res.first} favorites and ${res.second} alerts"
                    else "Invalid backup file",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        // ---------- Tema ----------
        SettingsSection("Appearance") {
            Text(
                "App theme. \"System\" follows the phone setting.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to "System",
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = current.themeMode == mode,
                        onClick = { vm.setTheme(mode) },
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Card style. Classic is the flat look; Neon adds a blue glowing outline to prices, cards and home widgets.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AppStyle.CLASSIC to "Classic",
                    AppStyle.NEON to "Neon",
                ).forEach { (style, label) ->
                    FilterChip(
                        selected = current.appStyle == style,
                        onClick = { vm.setAppStyle(context, style) },
                        label = { Text(label) },
                    )
                }
            }
        }

        // ---------- Notifiche ----------
        val notifState = com.adgent.trader.ui.notifications.rememberNotifPermissionState()
        SettingsSection("Notifications") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (notifState.granted) "Notification permission granted"
                        else "Notification permission disabled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (notifState.granted)
                            "Price alerts can arrive even with the app closed."
                        else
                            "⚠ Without the permission alerts will NOT arrive, even in Realtime mode.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!notifState.granted) {
                    TextButton(onClick = notifState::ensure) { Text("Enable") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val sent = notifState.sendTest(context)
                    android.widget.Toast.makeText(
                        context,
                        when {
                            sent -> "Notification sent: check the panel"
                            else -> "Notifications blocked: tap \"Enable\" or open system settings"
                        },
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }) {
                    Text("Send test notification")
                }
                OutlinedButton(onClick = { notifState.openSystemSettings() }) {
                    Text("System settings")
                }
            }
        }

        // ---------- Modalità dati ----------
        SettingsSection("Price alerts") {
            Text(
                "How quickly price alerts arrive vs. how much battery the app uses. " +
                    "Recommended: Battery saver for daily use.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (current.dataMode == DataMode.REALTIME)
                            "Realtime · instant alerts, more battery"
                        else
                            "Battery saver · recommended",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (current.dataMode == DataMode.REALTIME)
                            "Notification in ~1 second even with the app closed. Higher battery " +
                                "use: keeps a persistent connection and a small notification."
                        else
                            "Checks every 15 minutes: alerts arrive within 15 minutes, " +
                                "near-zero battery impact, no persistent notification.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = current.dataMode == DataMode.REALTIME,
                    onCheckedChange = { realtime ->
                        vm.setDataMode(context, if (realtime) DataMode.REALTIME else DataMode.SAVER)
                    },
                )
            }

            val manufacturer = android.os.Build.MANUFACTURER.lowercase()
            val aggressive = manufacturer in listOf("xiaomi", "huawei", "oppo", "vivo", "realme", "oneplus", "samsung")
            if (aggressive && current.dataMode == DataMode.REALTIME) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Tip for ${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Some phones kill background apps. To guarantee alerts, " +
                                "exclude XON Trader from battery optimization in phone settings.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$manufacturer")),
                                )
                            }
                        }) {
                            Text("Guide for your phone")
                        }
                    }
                }
            }
        }

        // ---------- Sorgente dati mercati ----------
        SettingsSection("Market data source") {
            Text(
                "Where prices come from by default. Auto picks the best available " +
                    "exchange for each coin (and switches if one goes down). You can " +
                    "override any single coin from its detail page.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            // LazyRow scrollabile: con una Row le chip oltre lo schermo
            // (OKX/Bitfinex/KuCoin) resterebbero irraggiungibili.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(vm.providerOptions) { sel ->
                    FilterChip(
                        selected = current.defaultProvider == sel,
                        onClick = { vm.setDefaultProvider(sel) },
                        label = { Text(sel.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                        ),
                        border = null,
                    )
                }
            }
        }

        // ---------- Widget home screen ----------
        SettingsSection("Home-screen widgets") {
            Text(
                "Widgets each keep their own settings (coin, text size, number " +
                    "format). If a widget shows something unexpected, open the " +
                    "status panel: it lists every widget with its saved config " +
                    "and tells you which coin each one is set to show.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenWidgetStatus) {
                Text("Widget status…")
            }
        }

        // ---------- Sicurezza ----------
        SettingsSection("Security") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App lock", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Asks for fingerprint, face or phone PIN on launch. " +
                            "Protects charts and alerts if someone else holds your phone.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = current.appLock, onCheckedChange = vm::setAppLock)
            }
        }

        // ---------- Backup e ripristino ----------
        SettingsSection("Backup & restore") {
            Text(
                "Export favorites and alerts to a JSON file to keep or move to " +
                    "another phone. Restore replaces current data with the file " +
                    "contents.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
                        .format(java.util.Date())
                    safeLaunch { exportLauncher.launch("xon-trader-backup-$stamp.json") }
                }) {
                    Text("Export to file…")
                }
                OutlinedButton(onClick = {
                    // MIME concreti (niente wildcard): alcuni picker OEM vanno in
                    // errore risolvendo "text/*" in EXTRA_MIME_TYPES.
                    safeLaunch {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                }) {
                    Text("Restore from file…")
                }
            }
        }

        // ---------- Info ----------
        SettingsSection("About") {
            Text(
                "XON Trader · market data from 7 public exchanges (Binance, Bybit, " +
                    "Kraken, Coinbase, OKX, Bitfinex, KuCoin), no signup required. " +
                    "Informational app: this is not financial advice. " +
                    "Free software, GPL-3.0 license.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
