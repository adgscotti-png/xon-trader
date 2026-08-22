package com.adgent.trader.core.service

import android.content.Context
import android.content.Intent

/** API statica per avviare/fermare il feed realtime (usata da UI, boot, widget). */
object PriceFeedController {
    fun onBootCompleted(context: Context) {
        // F3: riavvia il servizio se la modalità realtime è attiva.
    }

    fun start(context: Context) {
        context.startForegroundService(Intent(context, PriceFeedService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, PriceFeedService::class.java))
    }
}
