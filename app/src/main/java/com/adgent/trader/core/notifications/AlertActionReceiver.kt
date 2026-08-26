package com.adgent.trader.core.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Azione rapida "Disattiva" dalla notifica avviso. */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_DISABLE) return
        val ruleId = intent.getLongExtra(Notifications.EXTRA_RULE_ID, -1L)
        if (ruleId <= 0) return

        val pending = goAsync()
        // Il receiver vive pochi secondi: usa un thread dedicato, non il processo app.
        Thread {
            try {
                val db = com.adgent.trader.core.database.TraderDatabase.build(context)
                kotlinx.coroutines.runBlocking {
                    db.alertDao().setEnabled(ruleId, enabled = false)
                    // Rimane qualche altra regola attiva? Allora la catena di
                    // verifica prosegue; altrimenti si spegne (zero wakeup).
                    if (db.alertDao().enabledRules().isNotEmpty()) {
                        com.adgent.trader.core.work.AlertScheduler.schedule(
                            context.applicationContext,
                            0L,
                        )
                    } else {
                        com.adgent.trader.core.work.AlertScheduler.cancel(context.applicationContext)
                    }
                }
                db.close()
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(Notifications.ALERT_NOTIF_BASE_ID + ruleId.toInt())
            } finally {
                pending.finish()
            }
        }.start()
    }
}
