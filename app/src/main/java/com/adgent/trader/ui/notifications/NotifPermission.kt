package com.adgent.trader.ui.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adgent.trader.core.notifications.Notifications

/**
 * Stato del permesso notifiche (obbligatorio da Android 13: senza, il sistema
 * scarta ogni notifica in silenzio). Si aggiorna a ogni resume dell'app così i
 * banner riflettono subito un cambio fatto nelle impostazioni di sistema.
 *
 * [ensure] richiede il permesso con il dialog di sistema; [openSystemSettings]
 * porta alla schermata notifiche dell'app (unica via quando il dialog non
 * riappare più dopo un rifiuto definitivo).
 */
class NotifPermissionState internal constructor(
    private val grantedState: () -> Boolean,
    private val requestRuntime: () -> Unit,
    val openSystemSettings: () -> Unit,
) {
    val granted: Boolean get() = grantedState()

    /** Chiede il permesso se manca; dialog di sistema o impostazioni come fallback. */
    fun ensure() {
        if (granted) return
        if (Build.VERSION.SDK_INT >= 33) requestRuntime() else openSystemSettings()
    }

    /** Invia una notifica di prova sul canale Test. Ritorna false se bloccate. */
    fun sendTest(context: Context): Boolean =
        if (granted) Notifications.notifyTest(context) else false
}

@Composable
fun rememberNotifPermissionState(): NotifPermissionState {
    val context = LocalContext.current
    val appCtx = remember { context.applicationContext }
    val granted = remember { mutableStateOf(Notifications.canPost(appCtx)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted.value = Notifications.canPost(appCtx) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted.value = Notifications.canPost(appCtx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    return remember {
        NotifPermissionState(
            grantedState = { granted.value },
            requestRuntime = {
                if (Build.VERSION.SDK_INT >= 33) {
                    runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            },
            openSystemSettings = {
                runCatching {
                    appCtx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, appCtx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}
