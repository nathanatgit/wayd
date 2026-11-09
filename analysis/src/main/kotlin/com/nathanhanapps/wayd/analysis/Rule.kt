package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.Tier
import com.nathanhanapps.wayd.enrich.TrackerDb

/** Package used for flows whose owner could not be resolved; rules never attribute findings to it. */
const val UNKNOWN_PKG = "?"

/** Read-only view of the analysis window handed to every rule. */
class RuleContext(
    /** Flows that started inside the window. */
    val flows: List<Flow>,
    val windowStart: Long,
    val windowEnd: Long,
    /** Hosts contacted by each package *before* the window (for "new domain" detection). */
    val knownHostsBefore: Map<String, Set<String>>,
    val tracker: TrackerDb,
    /** Stored on the findings; injected so tests are deterministic. */
    val now: Long,
    /** Permission-use records (AppOps history buckets) that started inside the window. */
    val permEvents: List<PermEvent> = emptyList(),
) {
    val attributed: List<Flow> by lazy { flows.filter { it.pkg != UNKNOWN_PKG } }
}

/** Deterministic rule. `id` is stable forever; bump `version` when the logic changes. */
interface Rule {
    val id: String
    val version: Int

    /** How trustworthy the evidence tier is; feeds the risk score (docs/05: PLAINTEXT 1.0, METADATA 0.6). */
    val tier: Tier

    fun evaluate(ctx: RuleContext): List<Finding>
}

/** Helpers shared by rules. */
internal fun evidenceJson(flowIds: Collection<Long>, permIds: Collection<Long> = emptyList()): String =
    (permIds.map { "p:$it" } + flowIds.map { "f:$it" }).joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

internal fun finding(
    ctx: RuleContext,
    rule: Rule,
    pkg: String,
    severity: Severity,
    title: String,
    evidence: Collection<Long>,
    dataTypes: String = "",
    perm: Collection<Long> = emptyList(),
) = Finding(
    sessionId = null,
    pkg = pkg,
    ruleId = rule.id,
    severity = severity,
    title = title,
    dataTypes = dataTypes,
    evidenceJson = evidenceJson(evidence, perm),
    createdAt = ctx.windowStart, // anchors the finding to its day so a re-run can replace it
    userState = com.nathanhanapps.wayd.core.model.UserState.NEW,
)

/** Human-readable byte size used in finding titles (language-neutral, no locale formatting). */
internal fun bytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}
