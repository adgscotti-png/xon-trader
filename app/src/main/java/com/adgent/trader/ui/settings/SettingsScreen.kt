package com.adgent.trader.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { repo.setTheme(mode) }

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
                    )
                }
                val data = com.adgent.trader.data.BackupData(
                    exportedAt = System.currentTimeMillis(),
                    watchlist = container.watchlistRepo.all().map { it.symbol },
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
                    )
                },
            )
            container.watchlistRepo.replaceAll(data.watchlist)
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
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = settings ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // SAF: esporta/ripristina il backup dei dati (watchlist + avvisi).
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            scope.launch {
                val ok = vm.exportBackup(context, it)
                android.widget.Toast.makeText(
                    context,
                    if (ok) "Backup esportato" else "Esportazione non riuscita",
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
                    if (res != null) "Ripristinati ${res.first} preferiti e ${res.second} avvisi"
                    else "File di backup non valido",
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
            "Impostazioni",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        // ---------- Tema ----------
        SettingsSection("Aspetto") {
            Text(
                "Tema dell'app. \"Sistema\" segue l'impostazione del telefono.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ThemeMode.SYSTEM to "Sistema",
                    ThemeMode.LIGHT to "Chiaro",
                    ThemeMode.DARK to "Scuro",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = current.themeMode == mode,
                        onClick = { vm.setTheme(mode) },
                        label = { Text(label) },
                    )
                }
            }
        }

        // ---------- Modalità dati ----------
        SettingsSection("Avvisi in tempo reale") {
            Text(
                "Realtime: notifica in ~1 secondo, con una piccola notifica persistente " +
                    "che tiene attivo il collegamento. Risparmio: nessuna notifica persistente, " +
                    "controllo ogni 15 minuti.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (current.dataMode == DataMode.REALTIME) "Realtime (consigliato)"
                        else "Risparmio batteria",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (current.dataMode == DataMode.REALTIME)
                            "Avvisi quasi istantanei, anche a app chiusa."
                        else
                            "Avvisi garantiti entro 15 minuti, zero consumo in background.",
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
                            "Suggerimento per ${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Alcuni telefoni chiudono le app in background. Per garantire gli avvisi, " +
                                "escludi ADGENT Trader dall'ottimizzazione batteria nelle impostazioni del telefono.",
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
                            Text("Guida per il tuo telefono")
                        }
                    }
                }
            }
        }

        // ---------- Sicurezza ----------
        SettingsSection("Sicurezza") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Blocco app", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "All'apertura richiede impronta, volto o PIN del telefono. " +
                            "Protegge grafici e avvisi se il telefono è in mano ad altri.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = current.appLock, onCheckedChange = vm::setAppLock)
            }
        }

        // ---------- Backup e ripristino ----------
        SettingsSection("Backup e ripristino") {
            Text(
                "Esporta preferiti e avvisi in un file JSON da conservare o spostare " +
                    "su un altro telefono. Il ripristino sostituisce i dati attuali " +
                    "con quelli del file.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
                        .format(java.util.Date())
                    exportLauncher.launch("adgent-trader-backup-$stamp.json")
                }) {
                    Text("Esporta su file…")
                }
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/*"))
                }) {
                    Text("Ripristina da file…")
                }
            }
        }

        // ---------- Info ----------
        SettingsSection("Informazioni") {
            Text(
                "ADGENT Trader 0.1.0-alpha · dati di mercato Binance (endpoint pubblici, " +
                    "nessuna registrazione richiesta). App informativa: non è consulenza " +
                    "finanziaria. Software libero, licenza GPL-3.0.",
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
        shape = RoundedCornerShape(16.dp),
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
