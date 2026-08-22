package com.adgent.trader.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al riavvio del telefono ripristina il feed realtime se era attivo (F3). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PriceFeedController.onBootCompleted(context)
        }
    }
}
