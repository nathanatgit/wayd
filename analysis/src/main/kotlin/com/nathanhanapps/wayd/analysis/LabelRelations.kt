package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.ApkFact
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.model.AdviceReason
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.core.model.Attention
import com.nathanhanapps.wayd.core.model.BlockAdvice
import com.nathanhanapps.wayd.core.model.ConnectionRole
import com.nathanhanapps.wayd.core.model.PermissionMeaning
import com.nathanhanapps.wayd.core.model.RelationStrength
import com.nathanhanapps.wayd.core.model.RoleSource
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.SuggestionKind

/** A possible relation between a destination host and an APK fact (or an AppOps op with no declared fact). Never causation. */
data class Relation(val fact: ApkFact?, val meaning: PermissionMeaning?, val op: String?, val strength: RelationStrength)

/** A pointer to something the user could do. [permission]/[op] name what to restrict in AppOps; [component] what to redirect or disable in Thanox. */
data class Suggestion(val kind: SuggestionKind, val meaning: PermissionMeaning? = null, val permission: String? = null, val op: String? = null, val component: String? = null)

/** Everything the rules know about one host of one app: traffic, role, APK relations, attention and suggestions. */
data class HostAssessment(
    val summary: HostSummary,
    val records: List<Flow>,
    val inApk: Boolean,
    val permissions: List<Relation>,
    val components: List<Relation>,
    val sdks: List<Relation>,
    val finding: Severity?,
    val score: Int,
    val attention: Attention,
    val suggestions: List<Suggestion>,
) {
    val blocked: Boolean get() = AdviceReason.ALREADY_BLOCKED in summary.reasons
    val lastAt: Long get() = records.maxOf { it.startAt }
}

/** APK facts tokenised once per snapshot, so assessing hundreds of hosts is set lookups instead of re-reading thousands of facts. */
class FactIndex(facts: List<ApkFact>) {
    val permissions: List<Pair<ApkFact, PermissionMeaning>> = facts.filter { it.kind == ApkFactKind.PERMISSION }.map { it to LabelRules.permission(it.key) }
    val declared: Map<PermissionMeaning, ApkFact> = permissions.groupBy({ it.second }, { it.first }).mapValues { it.value.first() }
    val components: List<Pair<ApkFact, Set<String>>> = facts.filter { it.kind in COMPONENTS }.map { it to LabelRules.tokens(it.key) }
    val sdks: List<Pair<ApkFact, Set<String>>> = facts.filter { it.kind == ApkFactKind.SDK }.map { it to LabelRules.tokens(it.key + " " + it.value.orEmpty()) }
    val endpoints: Set<String> = facts.filter { it.kind == ApkFactKind.ENDPOINT }.mapNotNull { LabelRules.host(it.key) }.toSet()

    companion object { val COMPONENTS = setOf(ApkFactKind.ACTIVITY, ApkFactKind.SERVICE, ApkFactKind.RECEIVER, ApkFactKind.PROVIDER) }
}

/**
 * Deterministic cross-evidence for labels (D-063): connection role × declared permissions × components/SDKs × AppOps use at the
 * same time × rule findings → one attention level and concrete suggestions per host. Rules decide; the AI only explains these.
 */
object LabelRelations {
    val SENSITIVE = setOf(
        PermissionMeaning.LOCATION, PermissionMeaning.CONTACTS, PermissionMeaning.ACCOUNTS, PermissionMeaning.PHONE, PermissionMeaning.CALL_LOG,
        PermissionMeaning.SMS, PermissionMeaning.CALENDAR, PermissionMeaning.CAMERA, PermissionMeaning.MICROPHONE, PermissionMeaning.MEDIA,
        PermissionMeaning.MEDIA_LOCATION, PermissionMeaning.SENSORS, PermissionMeaning.ACTIVITY_RECOGNITION, PermissionMeaning.CLIPBOARD,
        PermissionMeaning.APP_LIST, PermissionMeaning.USAGE_STATS, PermissionMeaning.AD_ID, PermissionMeaning.NOTIFICATION_ACCESS, PermissionMeaning.ACCESSIBILITY,
    )
    private val TRACKING = setOf(ConnectionRole.ADS, ConnectionRole.ANALYTICS, ConnectionRole.DIAGNOSTICS)
    private val PLATFORM = setOf(ConnectionRole.SYSTEM, ConnectionRole.CARRIER)
    private val FLOW_REF = Regex("\"f:(\\d+)\"")

    /** AppOps op name (as `appops` prints it) → capability. Unknown ops return UNKNOWN and are ignored. */
    fun opMeaning(op: String): PermissionMeaning {
        val o = op.uppercase()
        return when {
            "LOCATION" in o || o == "GPS" -> PermissionMeaning.LOCATION
            "CONTACTS" in o -> PermissionMeaning.CONTACTS
            "CALL_LOG" in o -> PermissionMeaning.CALL_LOG
            "SMS" in o || "MMS" in o -> PermissionMeaning.SMS
            "PHONE_STATE" in o || "PHONE_NUMBERS" in o || "DEVICE_IDENTIFIERS" in o -> PermissionMeaning.PHONE
            "CALENDAR" in o -> PermissionMeaning.CALENDAR
            o == "CAMERA" -> PermissionMeaning.CAMERA
            "RECORD_AUDIO" in o -> PermissionMeaning.MICROPHONE
            "CLIPBOARD" in o -> PermissionMeaning.CLIPBOARD
            "BODY_SENSORS" in o -> PermissionMeaning.SENSORS
            "ACTIVITY_RECOGNITION" in o -> PermissionMeaning.ACTIVITY_RECOGNITION
            "USAGE_STATS" in o -> PermissionMeaning.USAGE_STATS
            "GET_ACCOUNTS" in o -> PermissionMeaning.ACCOUNTS
            "QUERY_ALL_PACKAGES" in o -> PermissionMeaning.APP_LIST
            "READ_MEDIA" in o -> PermissionMeaning.MEDIA
            "EXTERNAL_STORAGE" in o -> PermissionMeaning.STORAGE
            "BLUETOOTH" in o -> PermissionMeaning.BLUETOOTH
            "WIFI_SCAN" in o -> PermissionMeaning.WIFI
            else -> PermissionMeaning.UNKNOWN
        }
    }

    /** All hosts of one app, most attention first, then most data. One pass over findings and one fact index for every host. */
    fun assessAll(pkg: String, flows: List<Flow>, facts: List<ApkFact>, ops: List<PermEvent>, findings: List<Finding>): List<HostAssessment> {
        val index = FactIndex(facts)
        val severityByFlow = HashMap<Long, Severity>()
        findings.forEach { f ->
            FLOW_REF.findAll(f.evidenceJson).forEach { m ->
                val id = m.groupValues[1].toLongOrNull() ?: return@forEach
                severityByFlow.merge(id, f.severity) { a, b -> if (a.ordinal >= b.ordinal) a else b }
            }
        }
        val sensitiveOps = ops.map { it to opMeaning(it.op) }.filter { it.second in SENSITIVE }
        return flows.groupBy(LabelRules::subject).map { (_, group) -> assess(pkg, LabelRules.summary(group), group, index, sensitiveOps, severityByFlow) }
            .sortedWith(compareByDescending<HostAssessment> { it.attention.ordinal }.thenByDescending { it.score }.thenByDescending { it.summary.bytesUp + it.summary.bytesDown })
    }

    private fun assess(
        pkg: String, summary: HostSummary, records: List<Flow>, index: FactIndex,
        sensitiveOps: List<Pair<PermEvent, PermissionMeaning>>, severityByFlow: Map<Long, Severity>,
    ): HostAssessment {
        val role = summary.role.role
        val tracking = role in TRACKING
        val fitting = LabelRules.roleMeanings[role].orEmpty()

        // Permissions: used while this host's records were open (strongest when it also fits the role), else declared and fitting.
        val spans = records.map { it.startAt to (it.endAt ?: it.startAt) }
        val used = sensitiveOps.filter { (e, _) ->
            val end = maxOf(e.bucketEnd, e.ts + (e.durationMs ?: 0))
            spans.any { (s, t) -> e.ts <= t && end >= s }
        }.groupBy({ it.second }, { it.first })
        val permissions = ArrayList<Relation>()
        used.forEach { (meaning, events) ->
            val strength = if (meaning in fitting) RelationStrength.USED_TOGETHER else RelationStrength.SAME_TIME
            permissions += Relation(index.declared[meaning], meaning, events.maxBy { it.ts }.op, strength)
        }
        fitting.filter { it in SENSITIVE && it !in used && it in index.declared }.forEach { permissions += Relation(index.declared.getValue(it), it, null, RelationStrength.DECLARED) }
        permissions.sortBy { it.strength.ordinal }

        // Components and SDKs: role words, host words and the host's company, from the prebuilt token sets.
        val roleWords = LabelRules.roleWords[role].orEmpty()
        val hostWords = LabelRules.hostTokens(summary.host, pkg)
        val company = LabelRules.tracker.lookup(summary.host)?.company?.let(LabelRules::tokens).orEmpty()
        fun score(tokens: Set<String>) = roleWords.count { it in tokens } * 2 + hostWords.count { it in tokens } + if (company.any { it in tokens }) 3 else 0
        val components = index.components.map { it.first to score(it.second) }.filter { it.second >= 2 }.sortedByDescending { it.second }.take(2)
            .map { Relation(it.first, null, null, RelationStrength.NAME_MATCH) }
        val sdks = index.sdks.map { it.first to score(it.second) }.filter { it.second >= 2 }.sortedByDescending { it.second }.take(2)
            .map { Relation(it.first, null, null, RelationStrength.NAME_MATCH) }

        val finding = records.mapNotNull { severityByFlow[it.id] }.maxByOrNull { it.ordinal }
        val blocked = AdviceReason.ALREADY_BLOCKED in summary.reasons

        var score = when (finding) { Severity.HIGH -> 4; Severity.MEDIUM -> 3; Severity.LOW -> 1; else -> 0 }
        if (tracking) score += if (summary.role.source == RoleSource.NAME_PATTERN) 1 else 2
        score += when {
            permissions.any { it.strength == RelationStrength.USED_TOGETHER } -> 2
            permissions.any { it.strength == RelationStrength.SAME_TIME } && (tracking || role == ConnectionRole.UNKNOWN) -> 1
            permissions.any { it.strength == RelationStrength.DECLARED } && tracking -> 1
            else -> 0
        }
        if (AdviceReason.SCREEN_OFF in summary.reasons && role !in PLATFORM) score += 1
        if (AdviceReason.UPLOAD_HEAVY in summary.reasons && (tracking || role == ConnectionRole.UNKNOWN)) score += 1
        var attention = when {
            score >= 5 -> Attention.ACT
            score >= 3 -> Attention.REVIEW
            score >= 1 -> Attention.NOTE
            else -> Attention.NONE
        }
        // Platform services are never pushed above a note by traffic shape alone, and a blocked host needs no further action.
        if (role in PLATFORM && finding == null && attention > Attention.NOTE) attention = Attention.NOTE
        if (blocked) attention = Attention.NONE

        val suggestions = ArrayList<Suggestion>()
        if (!blocked && summary.advice == BlockAdvice.TRY_TEMPORARY) suggestions += Suggestion(SuggestionKind.BLOCK_TRIAL)
        if (tracking || attention >= Attention.REVIEW) permissions.filter { it.strength != RelationStrength.DECLARED || tracking }.take(2).forEach {
            suggestions += Suggestion(SuggestionKind.APPOPS_RESTRICT, it.meaning, it.fact?.key, it.op)
        }
        if (attention >= Attention.NOTE) components.forEach { r ->
            val f = r.fact ?: return@forEach
            when {
                f.kind == ApkFactKind.ACTIVITY && role == ConnectionRole.ADS -> suggestions += Suggestion(SuggestionKind.THANOX_REDIRECT, component = f.key)
                f.kind in setOf(ApkFactKind.SERVICE, ApkFactKind.RECEIVER) && (tracking || role == ConnectionRole.PUSH) -> suggestions += Suggestion(SuggestionKind.THANOX_DISABLE, component = f.key)
            }
        }
        return HostAssessment(summary, records, summary.host != null && summary.host in index.endpoints, permissions, components, sdks, finding, score, attention, suggestions)
    }
}
