package com.adgent.trader.core.service

import com.adgent.trader.core.database.AlertRuleEntity
import com.adgent.trader.core.model.PriceTick
import com.adgent.trader.core.provider.ProviderId

/** Valicamento di una regola avviso su un tick: il servizio lo notifica, il hub persiste lastTriggeredAt. */
data class AlertTrigger(
    val rule: AlertRuleEntity,
    val tick: PriceTick,
    val nowMs: Long,
)

/**
 * Valutazione avvisi O(1) per tick: bucket per "provider:symbol" invece dello
 * scan globale di tutte le regole a ogni batch. Il cooldown anti-spam (60s,
 * [AlertEngine.COOLDOWN_MS]) è tenuto IN MEMORIA (ruleId -> lastFired) per non
 * leggere il DB a ogni tick; al rebuild le regole arrivano con lastTriggeredAt
 * dal DB, quindi il cooldown sopravvive anche a un riavvio del processo.
 * Nessuna chiamata suspend/DB dentro questi metodi (il persist di
 * lastTriggeredAt lo fa il chiamante, fuori dal lock).
 */
class AlertBoundaryIndex {

    private val lock = Any()
    private val rulesBySymbol = HashMap<String, List<AlertRuleEntity>>()
    private val lastFired = HashMap<Long, Long>()

    /** Ricostruisce i bucket dalle regole abilitate correnti; rimuove gli id spariti. */
    fun rebuild(enabled: List<AlertRuleEntity>) = synchronized(lock) {
        rulesBySymbol.clear()
        for (rule in enabled) {
            if (!rule.enabled) continue
            rulesBySymbol[rule.key()] = rulesBySymbol[rule.key()].orEmpty() + rule
        }
        lastFired.keys.retainAll(enabled.map { it.id })
    }

    /**
     * Valuta SOLO le regole del simbolo del tick e aggiorna il cooldown in
     * memoria. Restituisce i trigger da notificare, senza effetti collaterali.
     */
    fun evaluate(provider: ProviderId, tick: PriceTick, nowMs: Long): List<AlertTrigger> =
        synchronized(lock) {
            rulesBySymbol["${provider.name}:${tick.symbol}"]?.mapNotNull { rule ->
                val effective = if (lastFired.containsKey(rule.id))
                    rule.copy(lastTriggeredAt = lastFired[rule.id])
                else rule
                when (AlertEngine.evaluate(effective, tick, nowMs)) {
                    AlertEngine.Verdict.SKIP -> null
                    else -> {
                        lastFired[rule.id] = nowMs
                        AlertTrigger(rule, tick, nowMs)
                    }
                }
            }.orEmpty()
        }

    private fun AlertRuleEntity.key() = "$provider:$symbol"
}
