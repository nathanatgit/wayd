package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.analysis.rules.BackgroundUpload
import com.nathanhanapps.wayd.analysis.rules.Beacon
import com.nathanhanapps.wayd.analysis.rules.CleartextHttp
import com.nathanhanapps.wayd.analysis.rules.DirectIp
import com.nathanhanapps.wayd.analysis.rules.NewDomain
import com.nathanhanapps.wayd.analysis.rules.OwnDoh
import com.nathanhanapps.wayd.analysis.rules.TrackerContact
import com.nathanhanapps.wayd.analysis.rules.UploadRatio
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.Tier

/** Runs all registered rules. A rule that throws is skipped, never allowed to hide the others' findings. */
class RuleEngine(val rules: List<Rule> = defaultRules()) {
    fun run(ctx: RuleContext): List<Finding> =
        rules.flatMap { r -> runCatching { r.evaluate(ctx) }.getOrDefault(emptyList()) }
            .sortedWith(compareByDescending<Finding> { it.severity.ordinal }.thenBy { it.pkg }.thenBy { it.ruleId })

    fun certainty(ruleId: String): Double =
        rules.firstOrNull { it.id == ruleId }?.tier?.let { RiskScorer.certainty(it) } ?: RiskScorer.certainty(Tier.METADATA)

    companion object {
        fun defaultRules(): List<Rule> = listOf(
            BackgroundUpload(), UploadRatio(), Beacon(), NewDomain(), DirectIp(), OwnDoh(), TrackerContact(), CleartextHttp(),
        )
    }
}

/** docs/05: score = sum(severity weight x certainty weight), capped at 100, always with its reasons. */
object RiskScorer {
    fun weight(s: Severity) = when (s) {
        Severity.HIGH -> 30
        Severity.MEDIUM -> 12
        Severity.LOW -> 4
        Severity.INFO -> 0
    }

    fun certainty(t: Tier) = when (t) {
        Tier.PLAINTEXT, Tier.DECRYPTED -> 1.0
        Tier.METADATA -> 0.6
    }

    data class Reason(val ruleId: String, val title: String, val points: Double)
    data class Score(val total: Int, val reasons: List<Reason>)

    fun score(findings: List<Finding>, engine: RuleEngine): Score {
        val reasons = findings.map { Reason(it.ruleId, it.title, weight(it.severity) * engine.certainty(it.ruleId)) }
            .filter { it.points > 0 }.sortedByDescending { it.points }
        return Score(reasons.sumOf { it.points }.toInt().coerceAtMost(100), reasons)
    }
}
