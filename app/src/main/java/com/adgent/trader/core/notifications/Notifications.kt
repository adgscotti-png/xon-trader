package com.adgent.trader.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adgent.trader.MainActivity
import com.adgent.trader.R
import com.adgent.trader.core.common.Format
import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.model.PriceTick

/** Canali notifica e builder per avvisi prezzo, azioni rapide incluse. */
object Notifications {

    const val CHANNEL_PRICES = "prices"
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_TEST = "test"

    /** ID azioni delle notifiche avviso. */
    const val ACTION_DISABLE = "com.adgent.trader.action.DISABLE_ALERT"
    const val EXTRA_RULE_ID = "extra_rule_id"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PRICES,
                    "Avvisi prezzo",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Notifiche quando un simbolo supera la soglia impostata" },
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "Feed realtime",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "Notifica persistente del servizio dati in tempo reale" },
                NotificationChannel(
                    CHANNEL_TEST,
                    "Test",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Notifiche di prova per verificare le impostazioni" },
            )
        )
    }

    fun canPost(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    /**
     * Notifica di scatto avviso: titolo = simbolo, testo = condizione raggiunta,
     * azioni [Disattiva] e tap → deep link al dettaglio coin.
     */
    fun notifyAlert(
        context: Context,
        rule: AlertRuleEntity,
        tick: PriceTick,
    ) {
        if (!canPost(context)) return
        val base = rule.symbol.removeSuffix("USDT")
        val condition = describe(rule)
        val reached = when (rule.type) {
            "PRICE_ABOVE" -> tick.price >= rule.threshold
            "PRICE_BELOW" -> tick.price <= rule.threshold
            "PERCENT_UP", "PERCENT_DOWN" -> true // valutata sulla % 24h
            else -> true
        }
        if (!reached) return

        val openIntent = PendingIntent.getActivity(
            context,
            ("coin_${rule.symbol}").hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("adgent://coin/${rule.symbol}")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disableIntent = PendingIntent.getBroadcast(
            context,
            ("disable_${rule.id}").hashCode(),
            Intent(context, AlertActionReceiver::class.java).apply {
                action = ACTION_DISABLE
                putExtra(EXTRA_RULE_ID, rule.id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PRICES)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("$base ${Format.price(tick.price)}")
            .setContentText(condition)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$condition · ora ${Format.price(tick.price)}")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(0xFF4C3DFF.toInt())
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(0, "Disattiva", disableIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(ALERT_NOTIF_BASE_ID + rule.id.toInt(), notification)
        }
    }

    /**
     * Notifica di prova per verificare dall'app che le notifiche arrivino.
     * Ritorna false se il permesso manca (Android 13+ le scarta in silenzio).
     */
    fun notifyTest(context: Context): Boolean {
        if (!canPost(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_TEST)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("Notifica di prova")
            .setContentText("Perfetto: gli avvisi prezzo arriveranno in questa forma.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Perfetto: gli avvisi prezzo arriveranno in questa forma. " +
                        "Se non la vedi, controlla le notifiche di sistema per ADGENT Trader."),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(0xFF4C3DFF.toInt())
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(TEST_NOTIF_ID, notification)
        }.isSuccess
    }

    /** Notifica persistente discreta del servizio realtime. */
    fun serviceNotification(context: Context, text: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle("ADGENT Trader")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /** Descrizione leggibile della regola (riusata da lista UI). */
    fun describe(rule: AlertRuleEntity): String {
        val base = rule.symbol.removeSuffix("USDT")
        return when (rule.type) {
            "PRICE_ABOVE" -> "$base sopra ${Format.price(rule.threshold)}"
            "PRICE_BELOW" -> "$base sotto ${Format.price(rule.threshold)}"
            "PERCENT_UP" -> "$base su ≥${Format.percent(rule.threshold)}"
            "PERCENT_DOWN" -> "$base giù ≤−${Format.percent(rule.threshold)}"
            else -> rule.type
        } + if (rule.repeatable) " · ripetibile" else ""
    }

    /** Base degli ID notifica avviso: +ruleId. Pubblico per l'azione Disattiva. */
    const val ALERT_NOTIF_BASE_ID = 10_000

    /** ID della notifica di prova (impostazioni). */
    const val TEST_NOTIF_ID = 9_999
}
