package com.adgent.trader.ui.alerts

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.adgent.trader.AppContainer
import com.adgent.trader.appContainer
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.common.baseOf
import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.model.AlertType
import com.adgent.trader.core.work.AlertScheduler
import com.adgent.trader.data.DataMode
import com.adgent.trader.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Candidato nel picker simbolo: simbolo provider-specifico + prezzo + provider. */
data class AlertCandidate(
    val symbol: String,
    val price: Double,
    val provider: String,
)

data class AlertEditUiState(
    val ruleId: Long? = null,
    val symbols: List<AlertCandidate> = emptyList(),
    val query: String = "",
    val symbol: String = "BTCUSDT",
    val provider: String = "BINANCE",
    val type: AlertType = AlertType.PRICE_ABOVE,
    val threshold: String = "",
    val repeatable: Boolean = false,
    val note: String = "",
    val saved: Boolean = false,
    /** Modalità batch: N avvisi a scala di prezzo fissa sopra il prezzo base. */
    val batch: Boolean = false,
    val batchCount: Int = 5,
    val step: String = "",
) {
    val thresholdValue: Double?
        get() = threshold.replace(',', '.').toDoubleOrNull()

    val stepValue: Double?
        get() = step.replace(',', '.').toDoubleOrNull()

    val canSave: Boolean
        get() {
            val t = thresholdValue ?: return false
            if (t <= 0.0) return false
            if (!batch) return true
            val s = stepValue ?: return false
            return s > 0.0 && batchCount in 2..MAX_BATCH
        }

    companion object {
        const val MAX_BATCH = 20
    }
}

/** Editor avviso: simbolo, tipo soglia, ripetizione e nota. Salva su Room. */
class AlertEditViewModel(
    private val container: AppContainer,
    private val ruleId: Long?,
    presetSymbol: String?,
    presetProvider: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AlertEditUiState(
            ruleId = ruleId,
            symbol = presetSymbol ?: "BTCUSDT",
            provider = presetProvider ?: "BINANCE",
        )
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Lista simboli con prezzo per il picker (cache mercati, tutti i provider).
            val cached = container.marketDataRepo.observeCached(provider = null, limit = 300).first()
                .map { AlertCandidate(it.symbol, it.price, it.provider) }
            _state.update {
                it.copy(symbols = cached.sortedWith(compareBy({ c -> c.symbol }, { c -> c.provider })))
            }

            if (ruleId != null) {
                container.alertRepo.byId(ruleId)?.let { r ->
                    _state.update {
                        it.copy(
                            symbol = r.symbol,
                            provider = r.provider,
                            type = AlertType.entries.firstOrNull { t -> t.name == r.type }
                                ?: AlertType.PRICE_ABOVE,
                            threshold = r.threshold.toBigDecimal().stripTrailingZeros().toPlainString(),
                            repeatable = r.repeatable,
                            note = r.note,
                        )
                    }
                }
            } else if (presetSymbol == null) {
                // Preimposta il simbolo con volume più alto (ordine cache già per volume).
                _state.update { s ->
                    s.copy(
                        symbol = cached.firstOrNull()?.symbol ?: "BTCUSDT",
                        provider = cached.firstOrNull()?.provider ?: "BINANCE",
                    )
                }
            }
        }
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }
    fun onSymbolPick(candidate: AlertCandidate) =
        _state.update { it.copy(symbol = candidate.symbol, provider = candidate.provider, query = "") }
    fun onType(t: AlertType) = _state.update { it.copy(type = t) }
    fun onThreshold(v: String) =
        _state.update { it.copy(threshold = v.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(15)) }
    fun onRepeatable(b: Boolean) = _state.update { it.copy(repeatable = b) }
    fun onNote(n: String) = _state.update { it.copy(note = n.take(120)) }

    /** Attiva la modalità batch: la scala è sempre di tipo Above (prezzi crescenti). */
    fun onBatch(b: Boolean) = _state.update {
        it.copy(batch = b, type = if (b) AlertType.PRICE_ABOVE else it.type)
    }

    fun onStep(v: String) =
        _state.update { it.copy(step = v.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(12)) }

    fun onBatchCount(delta: Int) = _state.update {
        it.copy(batchCount = (it.batchCount + delta).coerceIn(2, AlertEditUiState.MAX_BATCH))
    }

    /** Salva la regola (o la scala batch); se realtime attivo garantisce il servizio. */
    fun save(context: Context) {
        val s = state.value
        val threshold = s.thresholdValue ?: return
        viewModelScope.launch {
            if (s.batch) {
                val step = s.stepValue ?: return@launch
                val count = s.batchCount.coerceIn(2, AlertEditUiState.MAX_BATCH)
                val now = System.currentTimeMillis()
                // Scala di prezzi crescenti dal livello base: base, base+step, …
                (0 until count).forEach { i ->
                    container.alertRepo.save(
                        AlertRuleEntity(
                            id = 0L,
                            symbol = s.symbol,
                            type = AlertType.PRICE_ABOVE.name,
                            threshold = threshold + i * step,
                            repeatable = s.repeatable,
                            note = s.note.trim(),
                            enabled = true,
                            createdAt = now + i,
                            lastTriggeredAt = null,
                            provider = s.provider,
                        )
                    )
                }
            } else {
                val existing = s.ruleId?.let { runCatching { container.alertRepo.byId(it) }.getOrNull() }
                val rule = AlertRuleEntity(
                    id = s.ruleId ?: 0L,
                    symbol = s.symbol,
                    type = s.type.name,
                    threshold = threshold,
                    repeatable = s.repeatable,
                    note = s.note.trim(),
                    enabled = true,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    lastTriggeredAt = existing?.lastTriggeredAt,
                    provider = s.provider,
                )
                container.alertRepo.save(rule)
            }
            // Avvia (o ri-arma) la catena WorkManager di verifica avvisi: parte
            // subito se realtime, alla prossima cadenza altrimenti.
            val mode = runCatching { container.settingsRepo.settings.first().dataMode }
                .getOrDefault(DataMode.SAVER)
            AlertScheduler.scheduleIfRules(context, AlertScheduler.initialDelayMs(mode))
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete(context: Context) {
        val id = state.value.ruleId ?: return
        viewModelScope.launch {
            container.alertRepo.delete(id)
            // Se era l'ultima regola la catena si spegne (zero wakeup), altrimenti ri-arma.
            val mode = runCatching { container.settingsRepo.settings.first().dataMode }
                .getOrDefault(DataMode.SAVER)
            AlertScheduler.scheduleIfRules(context, AlertScheduler.initialDelayMs(mode))
            _state.update { it.copy(saved = true) }
        }
    }
}

/**
 * Editor avviso: picker simbolo con ricerca live, tipo soglia, ripetibile, nota.
 * Ogni sezione spiega cosa succede quando l'avviso scatta.
 */
@Composable
fun AlertEditScreen(
    ruleId: Long?,
    presetSymbol: String?,
    presetProvider: String?,
    onClose: () -> Unit,
    vm: AlertEditViewModel = appViewModel(key = "alert-edit-${ruleId ?: 0}-$presetSymbol-$presetProvider") {
        AlertEditViewModel(it, ruleId, presetSymbol, presetProvider)
    },
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val notifState = com.adgent.trader.ui.notifications.rememberNotifPermissionState()
    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close editor")
            }
            Text(
                if (ruleId == null) "New alert" else "Edit alert",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        // ---------- Simbolo ----------
        SectionTitle("Instrument", "The crypto pair to watch")
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            placeholder = { Text("Search symbol (e.g. BTC)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        val results = if (state.query.isBlank()) emptyList()
        else state.symbols.filter { it.symbol.contains(state.query, ignoreCase = true) }.take(6)
        results.forEach { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.onSymbolPick(c) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(baseOf(c.symbol), fontWeight = FontWeight.SemiBold)
                    com.adgent.trader.core.provider.ProviderId.fromName(c.provider)?.let { pid ->
                        Text(
                            "${c.symbol} · ${pid.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "$" + Format.price(c.price),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                "Selected instrument: ${state.symbol} · " +
                    (com.adgent.trader.core.provider.ProviderId.fromName(state.provider)?.label
                        ?: state.provider),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // ---------- Tipo ----------
        if (!state.batch) {
            SectionTitle("When to alert me", "The condition that triggers the notification")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                listOf(AlertType.PRICE_ABOVE, AlertType.PRICE_BELOW).forEach { t ->
                    FilterChip(
                        selected = state.type == t,
                        onClick = { vm.onType(t) },
                        label = { Text(t.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---------- Soglia ----------
        SectionTitle(
            if (state.batch) "Starting price (lowest level)"
            else "Threshold price",
            if (state.batch) "One alert for every price step above this value"
            else when (state.type) {
                AlertType.PRICE_ABOVE -> "You get the notification if the price rises above this value"
                AlertType.PRICE_BELOW -> "You get the notification if the price falls below this value"
                else -> ""
            },
        )
        OutlinedTextField(
            value = state.threshold,
            onValueChange = vm::onThreshold,
            placeholder = { Text("e.g. ${exampleThreshold(state.symbol)}") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        // ---------- Batch: N avvisi a scala di prezzo fissa ----------
        if (ruleId == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Create multiple alerts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Turns one starting price into a ladder: N alerts at fixed price steps above it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.batch, onCheckedChange = vm::onBatch)
            }
            if (state.batch) {
                // Numero di avvisi (2-20).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Number of alerts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "From 2 to 20. Each one is a separate alert you can edit or delete.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { vm.onBatchCount(-1) }, enabled = state.batchCount > 2) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    Text("${state.batchCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { vm.onBatchCount(+1) }, enabled = state.batchCount < AlertEditUiState.MAX_BATCH) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
                // Distanza fissa tra un avviso e l'altro.
                OutlinedTextField(
                    value = state.step,
                    onValueChange = vm::onStep,
                    placeholder = { Text("e.g. 1000") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("Price step between alerts") },
                    supportingText = {
                        Text("The gap in price between two consecutive alerts, e.g. 1000 = one every \$1,000.")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                // Anteprima della scala generata.
                state.thresholdValue?.let { base ->
                    state.stepValue?.let { st ->
                        val n = state.batchCount.coerceIn(2, AlertEditUiState.MAX_BATCH)
                        val last = base + (n - 1) * st
                        Text(
                            "You'll get $n alerts: " +
                                (0 until n).joinToString(" · ") { i -> Format.price(base + i * st) },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 3,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // ---------- Ripetibile + nota ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Repeatable alert", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Off: a single notification, then it turns itself off.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.repeatable, onCheckedChange = vm::onRepeatable)
        }
        OutlinedTextField(
            value = state.note,
            onValueChange = vm::onNote,
            placeholder = { Text("Optional note, e.g. \"take profit target\"") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        // ---------- Azioni ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    vm.save(context)
                    // Il permesso notifiche è ciò che rende l'avviso utile: lo chiediamo
                    // nel momento in cui l'utente crea davvero il primo avviso.
                    notifState.ensure()
                },
                enabled = state.canSave,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.batch) "Create ${state.batchCount} alerts" else "Save alert")
            }
            if (ruleId != null) {
                TextButton(onClick = { vm.delete(context) }, modifier = Modifier.weight(1f)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun exampleThreshold(symbol: String): String = when (symbol) {
    "BTCUSDT" -> "100000"
    "ETHUSDT" -> "3500"
    "SOLUSDT" -> "200"
    else -> "50"
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
