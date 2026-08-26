package com.adgent.trader.core.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adgent.trader.appContainer
import com.adgent.trader.data.DataMode
import java.util.concurrent.TimeUnit

/**
 * Catena di verifica avvisi in background (WorkManager one-shot con REPLACE):
 * ogni run programma il successivo con [nextDelayMs] dalla distanza alla soglia.
 * Zero regole attive → la catena si spegne ([cancel]); niente servizio in
 * foreground, niente notifica persistente. WorkManager viene raggruppato dal
 * sistema (Doze-batching) e non tiene il processo in vita: batteria minima,
 * latenza degli alert 2-15 minuti secondo la vicinanza alla soglia.
 */
object AlertScheduler {

    const val WORK_NAME = "adgent-alert-check"

    /** Floor del primo delay: non serve svegliarsi prima. */
    const val MIN_INITIAL_DELAY_MS = 10_000L

    /** Schedula (o ri-schedula, REPLACE) la prossima verifica tra [delayMs]. */
    fun schedule(context: Context, delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AlertCheckWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(MIN_INITIAL_DELAY_MS), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    /** Schedula solo se esistono regole attive; altrimenti ferma la catena.
     *  Ritorna true se la verifica è stata programmata. */
    suspend fun scheduleIfRules(context: Context, delayMs: Long): Boolean {
        val hasRules = runCatching { context.appContainer.alertRepo.enabledRules().isNotEmpty() }
            .getOrDefault(false)
        if (hasRules) schedule(context, delayMs) else cancel(context)
        return hasRules
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Primo delay dopo un passaggio di stato (app aperta/chiusa o cambio modalità). */
    fun initialDelayMs(mode: DataMode): Long =
        if (mode == DataMode.REALTIME) 2 * 60_000L else 15 * 60_000L

    /**
     * Delay del prossimo ciclo dalla distanza percentuale minima a una soglia
     * ([minGap] in frazione 0..1): più sei vicino, più spesso controlli.
     * SAVER: fissa 15 min, indipendente dalla distanza.
     */
    fun nextDelayMs(minGap: Double, mode: DataMode): Long = when (mode) {
        DataMode.SAVER -> 15 * 60_000L
        DataMode.REALTIME -> when {
            minGap <= 0.01 -> 2 * 60_000L
            minGap <= 0.03 -> 5 * 60_000L
            minGap <= 0.10 -> 10 * 60_000L
            else -> 15 * 60_000L
        }
    }
}
