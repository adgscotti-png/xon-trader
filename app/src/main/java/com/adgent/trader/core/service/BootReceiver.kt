package com.adgent.trader.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adgent.trader.core.database.TraderDatabase
import com.adgent.trader.core.work.AlertScheduler
import kotlinx.coroutines.runBlocking

/** Al riavvio del telefono ripristina la catena di verifica avvisi se esistono
 *  regole attive (altrimenti zero wakeup). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                val db = TraderDatabase.build(context)
                val hasRules = runBlocking {
                    runCatching { db.alertDao().enabledRules().isNotEmpty() }.getOrDefault(false)
                }
                db.close()
                if (hasRules) AlertScheduler.schedule(context.applicationContext, 0L)
                else AlertScheduler.cancel(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
