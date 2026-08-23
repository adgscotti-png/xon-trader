package com.adgent.trader.ui.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * SOLO DEBUG (source set debug, esclusa da release).
 * Riproduce il flusso utente dei widget per verificare l'isolamento
 * per-istanza della configurazione:
 *   1. Crea un widget ticker ETH
 *   2. Crea un widget watchlist
 *   3. Crea un widget ticker BTC
 * e mostra tutti e tre sullo schermo. Se dopo il passo 3 il primo
 * mostra ancora ETH, la configurazione è isolata per istanza.
 */
class WidgetReproActivity : ComponentActivity() {

    private lateinit var host: AppWidgetHost
    private val awm by lazy { AppWidgetManager.getInstance(this) }
    private val tickerCmp by lazy { ComponentName(this, TickerWidgetReceiver::class.java) }
    private val watchCmp by lazy { ComponentName(this, WatchlistWidgetReceiver::class.java) }

    private var log by mutableStateOf("")
    private var idA by mutableIntStateOf(-1)
    private var idB by mutableIntStateOf(-1)
    private var idC by mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 0xAD6E)
        host.startListening()
        setContent { ReproUi() }
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }

    private fun addTickerWidget(symbol: String, textSize: Int, expectedKind: WidgetKind): Int {
        val id = host.allocateAppWidgetId()
        val ok = awm.bindAppWidgetIdIfAllowed(id, tickerCmp)
        val info = awm.getAppWidgetInfo(id)
        runCatching {
            WidgetConfigStore.save(applicationContext, WidgetKind.TICKER, id, WidgetConfig(symbol = symbol, textSizeSp = textSize))
            val back = WidgetConfigStore.load(applicationContext, WidgetKind.TICKER, id)
            log += "saved->load ${back.symbol} sp=${back.textSizeSp}\n"
        }.onFailure { e -> log += "SAVE FAIL: ${e}\n" }
        log += "ticker id=$id bind=$ok kind=${info?.provider?.className?.substringAfterLast('.')}\n"
        lifecycleScope.launch { TickerWidget().updateAll(applicationContext) }
        return id
    }

    private fun addWatchlistWidget(rows: Int): Int {
        val id = host.allocateAppWidgetId()
        val ok = awm.bindAppWidgetIdIfAllowed(id, watchCmp)
        val info = awm.getAppWidgetInfo(id)
        runCatching {
            WidgetConfigStore.save(applicationContext, WidgetKind.WATCHLIST, id, WidgetConfig(rows = rows, textSizeSp = 14))
            val back = WidgetConfigStore.load(applicationContext, WidgetKind.WATCHLIST, id)
            log += "saved->load rows=${back.rows}\n"
        }.onFailure { e -> log += "SAVE FAIL: ${e}\n" }
        log += "watchlist id=$id bind=$ok kind=${info?.provider?.className?.substringAfterLast('.')}\n"
        lifecycleScope.launch { WatchlistWidget().updateAll(applicationContext) }
        return id
    }

    private fun addHostView(id: Int, container: LinearLayout) {
        val info = awm.getAppWidgetInfo(id) ?: return
        val view = host.createView(this, id, info)
        container.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (300 * resources.displayMetrics.density).toInt()))
    }

    @Composable
    private fun ReproUi() {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Widget isolation repro (DEBUG)", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Passo 1: ticker ETH · Passo 2: watchlist · Passo 3: ticker BTC.\n" +
                        "Se dopo il passo 3 il primo mostra ancora ETH → isolamento OK.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (idA < 0) idA = addTickerWidget("ETHUSDT", 18, WidgetKind.TICKER) }) { Text("1. ETH") }
                    Button(onClick = { if (idB < 0) idB = addWatchlistWidget(4) }) { Text("2. Watchlist") }
                    Button(onClick = { if (idC < 0) idC = addTickerWidget("BTCUSDT", 40, WidgetKind.TICKER) }) { Text("3. BTC") }
                }
                Text(log, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFD54F))

                listOf(idA to "1. TICKER id=$idA (ETH)", idB to "2. WATCHLIST id=$idB", idC to "3. TICKER id=$idC (BTC)")
                    .forEach { (id, label) ->
                        if (id > 0) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            AndroidView(
                                factory = { ctx ->
                                    LinearLayout(ctx).apply {
                                        orientation = LinearLayout.VERTICAL
                                        setPadding(0, 0, 0, 4)
                                        addHostView(id, this)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
            }
        }
    }
}
