package com.adgent.trader.core.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Segnaposto F3 — servizio realtime completo in fase F3. */
class PriceFeedService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
