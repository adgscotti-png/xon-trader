package com.adgent.trader.ui.alerts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Segnaposto F3 — sostituita dalla gestione avvisi completa. */
@Composable
fun AlertsScreen(onOpenCoin: (String) -> Unit, onEditRule: (Long?) -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("Avvisi — in arrivo nella fase F3")
    }
}

@Composable
fun AlertEditScreen(ruleId: Long?, presetSymbol: String?, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text("Editor avvisi — in arrivo nella fase F3")
    }
}
