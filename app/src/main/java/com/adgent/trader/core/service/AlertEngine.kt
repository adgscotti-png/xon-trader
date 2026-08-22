package com.adgent.trader.core.service

import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.model.PriceTick

/**
 * Valutazione delle regole avviso sui tick live.
 * Logica pura: il servizio la applica a ogni frame WS; il cooldown anti-spam
 * è di 60s per regola; le regole non-ripetibili scattano una volta sola.
 */
object AlertEngine {

    private const val COOLDOWN_MS = 60_000L

    /**
     * Restituisce le regole soddisfatte da questo tick, con l'azione da eseguire:
     * FIRE (notifica + disattiva se una-tantum) oppure SKIP (cooldown/non soddisfatta).
     */
    fun evaluate(rule: AlertRuleEntity, tick: PriceTick, nowMs: Long = System.currentTimeMillis()): Verdict {
        if (!rule.enabled) return Verdict.SKIP
        if (!matches(rule, tick)) return Verdict.SKIP
        // Cooldown: evita notifiche ripetute per lo stesso evento.
        rule.lastTriggeredAt?.let { last ->
            if (nowMs - last < COOLDOWN_MS) return Verdict.SKIP
        }
        return if (rule.repeatable) Verdict.FIRE_REPEATABLE else Verdict.FIRE_ONCE
    }

    /** La condizione della regola è soddisfatta dal tick? */
    fun matches(rule: AlertRuleEntity, tick: PriceTick): Boolean = when (rule.type) {
        "PRICE_ABOVE" -> tick.price >= rule.threshold
        "PRICE_BELOW" -> tick.price <= rule.threshold
        "PERCENT_UP" -> tick.changePercent24h >= rule.threshold
        "PERCENT_DOWN" -> tick.changePercent24h <= -rule.threshold
        else -> false
    }

    enum class Verdict { SKIP, FIRE_ONCE, FIRE_REPEATABLE }
}
