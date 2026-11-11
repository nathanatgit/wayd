package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.db.AiAnalysis

/**
 * What stored AI analyses said each connection is for. These are hints shown next to the rule label, never a label or a colour of
 * their own (AGENTS.md rule 2). Each stored answer is validated again against the evidence it was given, so an id the model
 * invented never attaches a hint to an unrelated connection.
 */
object AiHints {
    private val REF = Regex("([fpm]):(\\d+)")

    /** Flow id to the AI's purpose guess; [rows] newest first, the newest answer about a connection wins. UNKNOWN is left out. */
    fun flowPurposes(rows: List<AiAnalysis>): Map<Long, Purpose> {
        val out = HashMap<Long, Purpose>()
        for (row in rows) {
            if (!row.validated) continue
            val allowed = REF.findAll(row.inputRefsJson).map { it.value }.toSet()
            val result = (SchemaValidator.validate(row.resultJson, allowed) as? Validation.Valid)?.result ?: continue
            for (d in result.destinations) {
                if (d.purpose == Purpose.UNKNOWN) continue
                for (ref in d.evidence) {
                    if (!ref.startsWith("f:")) continue
                    ref.substring(2).toLongOrNull()?.let { out.putIfAbsent(it, d.purpose) }
                }
            }
        }
        return out
    }
}
