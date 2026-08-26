package com.adgent.trader.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
import com.adgent.trader.appContainer
import com.adgent.trader.core.common.baseOf
import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.notifications.Notifications
import com.adgent.trader.core.work.AlertScheduler
import com.adgent.trader.data.DataMode
import com.adgent.trader.ui.appViewModel
import com.adgent.trader.ui.components.CoinBadge
import com.adgent.trader.ui.theme.neonCardFrame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AlertsUiState(
    val rules: List<AlertRuleEntity> = emptyList(),
    val realtime: Boolean = true,
)

class AlertsViewModel(container: AppContainer) : ViewModel() {

    private val container = container

    val state: StateFlow<AlertsUiState> = combine(
        container.alertRepo.observeAll(),
        container.settingsRepo.settings.map { it.dataMode },
    ) { rules, mode ->
        AlertsUiState(rules, mode == DataMode.REALTIME)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun setEnabled(rule: AlertRuleEntity, enabled: Boolean) {
        viewModelScope.launch {
            container.alertRepo.setEnabled(rule.id, enabled)
            rearmAlertChain()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            container.alertRepo.delete(id)
            rearmAlertChain()
        }
    }

    /** Riallinea la catena WorkManager: parte/riparte se resta almeno una regola
     *  attiva, si spegne se sono tutte disabilitate/cancellate. */
    private suspend fun rearmAlertChain() {
        val mode = runCatching { container.settingsRepo.settings.first().dataMode }
            .getOrDefault(DataMode.SAVER)
        AlertScheduler.scheduleIfRules(container.appContext, AlertScheduler.initialDelayMs(mode))
    }
}

/**
 * Schermata Avvisi: lista regole con switch on/off, cancellazione e CTA editor.
 * Sottotitolo che spiega la modalità attiva (realtime ~1s vs risparmio ≤15min).
 */
@Composable
fun AlertsScreen(
    onOpenCoin: (String, String) -> Unit,
    onEditRule: (Long?) -> Unit,
    vm: AlertsViewModel = appViewModel { AlertsViewModel(it) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val notifState = com.adgent.trader.ui.notifications.rememberNotifPermissionState()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (!notifState.granted) {
            NotifWarningBanner(
                onEnable = { notifState.ensure() },
                onOpenSettings = { notifState.openSystemSettings() },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Alerts",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    if (state.realtime) "Live while open · background checks every 2-15 minutes"
                    else "Battery saver · checks every 15 minutes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.rules.isEmpty()) {
            EmptyAlerts(onCreate = { onEditRule(null) })
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.rules, key = { it.id }) { rule ->
                    AlertRow(
                        rule = rule,
                        onToggle = { vm.setEnabled(rule, !rule.enabled) },
                        onDelete = { vm.delete(rule.id) },
                        onOpenCoin = { onOpenCoin(rule.symbol, rule.provider) },
                        onEdit = { onEditRule(rule.id) },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}

/**
 * Banner quando il permesso notifiche manca: da Android 13 il sistema scarta
 * ogni avviso in silenzio, quindi senza questo consenso gli alert non arrivano.
 */
@Composable
private fun NotifWarningBanner(
    onEnable: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Icon(
                Icons.Outlined.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Notifications disabled",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Without the permission alerts cannot arrive.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEnable) { Text("Enable") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun AlertRow(
    rule: AlertRuleEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onOpenCoin: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .neonCardFrame(RoundedCornerShape(16.dp))
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            CoinBadge(base = baseOf(rule.symbol), size = 30.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    Notifications.describe(rule),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                com.adgent.trader.core.provider.ProviderId.fromName(rule.provider)?.let { pid ->
                    Text(
                        pid.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                rule.note.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    TextButton(onClick = onOpenCoin, contentPadding = PaddingValues(0.dp)) {
                        Text("Open chart", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(0.dp)) {
                        Text("Edit", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "Delete alert",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyAlerts(onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.NotificationsActive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(44.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("No alerts", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "We notify you when a price crosses the threshold you set.\nWorks even with the app closed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        ExtendedFloatingActionButton(
            onClick = onCreate,
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("Create your first alert") },
        )
    }
}
