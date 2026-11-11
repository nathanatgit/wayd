package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.TrackerCategory

/** One label per recorded connection, highest first. Decided only by rules and the tracker list, never by AI (AGENTS.md rule 2). */
enum class ConnectionLabel { DANGER, WARN, SENSITIVE, AD, NORMAL }

object ConnectionLabels {
    private val FLOW_REF = Regex("f:(\\d+)")

    /**
     * Labels [flows] from the rule [findings] that cite them as evidence and from each host's tracker category:
     * a HIGH finding makes a connection DANGER, MEDIUM makes it WARN, a lower finding that names personal data types makes it
     * SENSITIVE, an ads or analytics host makes it AD, anything else is NORMAL. The highest label wins.
     */
    fun of(flows: Collection<Flow>, findings: Collection<Finding>): Map<Long, ConnectionLabel> {
        val fromFindings = HashMap<Long, ConnectionLabel>()
        for (f in findings) {
            val label = when (f.severity) {
                Severity.HIGH -> ConnectionLabel.DANGER
                Severity.MEDIUM -> ConnectionLabel.WARN
                Severity.LOW, Severity.INFO -> if (f.dataTypes.isNotBlank()) ConnectionLabel.SENSITIVE else continue
            }
            for (m in FLOW_REF.findAll(f.evidenceJson)) {
                val id = m.groupValues[1].toLongOrNull() ?: continue
                fromFindings.merge(id, label, ::higher)
            }
        }
        return flows.associate { flow ->
            val byCategory = if (flow.category == TrackerCategory.ADS || flow.category == TrackerCategory.ANALYTICS) ConnectionLabel.AD else ConnectionLabel.NORMAL
            flow.id to (fromFindings[flow.id]?.let { higher(it, byCategory) } ?: byCategory)
        }
    }

    private fun higher(a: ConnectionLabel, b: ConnectionLabel) = if (a.ordinal <= b.ordinal) a else b
}
