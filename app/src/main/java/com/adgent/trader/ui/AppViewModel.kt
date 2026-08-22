package com.adgent.trader.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.adgent.trader.appContainer

/** Crea un ViewModel iniettando l'AppContainer manuale. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (com.adgent.trader.AppContainer) -> VM,
): VM {
    val container = LocalContext.current.appContainer
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(container) }
        }
    )
}
