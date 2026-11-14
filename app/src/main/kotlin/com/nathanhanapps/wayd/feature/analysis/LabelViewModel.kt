package com.nathanhanapps.wayd.feature.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.ai.LabelAnalyzer
import com.nathanhanapps.wayd.ai.LabelAnswer
import com.nathanhanapps.wayd.ai.LabelAnswerIndex
import com.nathanhanapps.wayd.ai.LabelBudget
import com.nathanhanapps.wayd.ai.LabelContract
import com.nathanhanapps.wayd.ai.LabelEvidence
import com.nathanhanapps.wayd.ai.LabelEvidenceKind
import com.nathanhanapps.wayd.ai.LabelOutcome
import com.nathanhanapps.wayd.ai.LabelRequest
import com.nathanhanapps.wayd.ai.HistoryAnalyzer
import com.nathanhanapps.wayd.ai.HistoryContract
import com.nathanhanapps.wayd.ai.HistoryHost
import com.nathanhanapps.wayd.ai.HistoryNote
import com.nathanhanapps.wayd.ai.HistoryOutcome
import com.nathanhanapps.wayd.analysis.HostAssessment
import com.nathanhanapps.wayd.analysis.HostSummary
import com.nathanhanapps.wayd.analysis.LabelRelations
import com.nathanhanapps.wayd.analysis.LabelRules
import com.nathanhanapps.wayd.analysis.RoleMatch
import com.nathanhanapps.wayd.core.db.AiAnalysisDao
import com.nathanhanapps.wayd.core.db.ApkFact
import com.nathanhanapps.wayd.core.db.ApkScanDao
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.FindingDao
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.db.PermEventDao
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.feature.ai.AiEvidenceService
import com.nathanhanapps.wayd.feature.ai.ExternalAiGate
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Fact kind → the evidence kind the label contract validates against. Shared by every screen that restores labels. */
fun labelKind(f: ApkFact): LabelEvidenceKind = when (f.kind) {
    ApkFactKind.PERMISSION -> LabelEvidenceKind.PERMISSION
    ApkFactKind.ACTIVITY, ApkFactKind.SERVICE, ApkFactKind.RECEIVER, ApkFactKind.PROVIDER -> LabelEvidenceKind.FEATURE
    ApkFactKind.ENDPOINT -> LabelEvidenceKind.ENDPOINT
    ApkFactKind.SDK -> LabelEvidenceKind.SDK
    else -> LabelEvidenceKind.COVERAGE
}

fun knownApk(facts: List<ApkFact>): Map<String, LabelEvidenceKind> = facts.associate { "a:${it.id}" to labelKind(it) }

/**
 * Screen-scoped snapshot. A plain class (identity equality): Compose `remember(context, …)` must not compare thousands of rows.
 * Every index is built once, off the main thread, so list rows only do hash lookups.
 */
class LabelContext(
    val pkg: String,
    val locale: String,
    val facts: List<ApkFact>,
    val flows: List<Flow>,
    val permissions: List<PermEvent>,
    val findings: List<Finding>,
    private val answers: LabelAnswerIndex,
    private val history: Map<String, HistoryNote> = emptyMap(),
) {
    val factsById: Map<String, ApkFact> = facts.associateBy { "a:${it.id}" }
    val flowsById: Map<String, Flow> = flows.associateBy { "f:${it.id}" }
    /** Every host of this app with its relations, attention and suggestions; most attention first. Built once per snapshot. */
    val hosts: List<HostAssessment> = LabelRelations.assessAll(pkg, flows, facts, permissions, findings)
    private val assessments: Map<String, HostAssessment> = hosts.associateBy { it.summary.subject }
    private val bySubject: Map<String, List<Flow>> = hosts.associate { it.summary.subject to it.records }
    /** APK fact → hosts related to it, for the APK page ("3 connections may use this permission"). */
    val relatedHosts: Map<Long, List<HostAssessment>> = HashMap<Long, MutableList<HostAssessment>>().also { map ->
        hosts.forEach { h -> (h.permissions + h.components + h.sdks).mapNotNull { it.fact?.id }.distinct().forEach { id -> map.getOrPut(id) { ArrayList() } += h } }
    }
    private val summaries: Map<String, HostSummary> = hosts.associate { it.summary.subject to it.summary }
    private val endpointRoles = HashMap<String, RoleMatch>()

    fun subject(ref: String): String = flowsById[ref]?.let(LabelRules::subject) ?: ref

    /** Algorithm view of the host a record belongs to; null for APK facts. */
    fun summary(ref: String): HostSummary? = flowsById[ref]?.let { summaries[LabelRules.subject(it)] }

    fun records(ref: String): List<Flow> = flowsById[ref]?.let { bySubject[LabelRules.subject(it)] }.orEmpty()

    fun assessment(ref: String): HostAssessment? = flowsById[ref]?.let { assessments[LabelRules.subject(it)] }

    /** Result of the whole-history AI check for this host; advice is lowered when a domain block is not currently possible. */
    fun historyNote(a: HostAssessment): HistoryNote? = a.summary.host?.let(history::get)?.let { if (a.summary.domainBlockAvailable) it else it.copy(advice = com.nathanhanapps.wayd.core.model.BlockAdvice.INSUFFICIENT) }

    /** One compact, token-cheap line per host for the whole-history check: the rules' view, not raw records. */
    fun historyHosts(): List<HistoryHost> = hosts.filter { it.summary.host != null }.map { a ->
        val s = a.summary
        val total = s.bytesUp + s.bytesDown
        HistoryHost(s.host!!, buildString {
            append(s.host).append(" hint=").append(s.role.role)
            append(" rec=").append(s.flows).append(" up=").append(s.bytesUp).append(" down=").append(s.bytesDown)
            if (total > 0) append(" off=").append(s.screenOffBytes * 100 / total).append('%')
            if (a.inApk) append(" apk")
            if (a.permissions.isNotEmpty()) append(" perm=").append(a.permissions.take(3).joinToString(",") { "${it.meaning}" + if (it.strength <= com.nathanhanapps.wayd.core.model.RelationStrength.SAME_TIME) "*" else "" })
            a.components.firstOrNull()?.fact?.let { append(" comp=").append(it.key.substringAfterLast('.').take(40)) }
            a.finding?.let { append(" rule=").append(it) }
            if (a.blocked) append(" blocked")
        }, s.domainBlockAvailable)
    }

    /** Local role for a record or an APK endpoint; null for other facts. */
    fun localRole(ref: String): RoleMatch? = summary(ref)?.role ?: factsById[ref]?.takeIf { it.kind == ApkFactKind.ENDPOINT }?.let {
        synchronized(endpointRoles) { endpointRoles.getOrPut(ref) { LabelRules.match(it.key, null) } }
    }

    fun answer(ref: String): LabelAnswer? {
        val summary = summary(ref)
        return answers.get(pkg, subject(ref), summary?.domainBlockAvailable == true)
    }

    /** Restores saved answers for every item that has one, so the first frame after an emission does no JSON work. */
    internal fun warm(): LabelContext = also {
        bySubject.values.forEach { answer("f:${it.first().id}") }
        factsById.keys.forEach { if (answers.has(pkg, it)) answer(it) }
    }

    /**
     * Evidence in priority order (target, scope, direct co-evidence, ranked APK context, weaker context). The analyzer trims the
     * tail to the selected model's budget, so the most specific items survive on small models.
     */
    fun request(ref: String): LabelRequest? {
        val flow = flowsById[ref]
        val fact = factsById[ref]
        if (flow == null && fact == null) return null
        val summary = summary(ref)
        val records = records(ref)
        val targetHost = flow?.host ?: fact?.takeIf { it.kind == ApkFactKind.ENDPOINT }?.key
        val role = localRole(ref)?.role
        val roles = LabelRules.targetRoles(fact, role)
        val hostTokens = LabelRules.hostTokens(targetHost, pkg)
        val others = facts.filter { "a:${it.id}" != ref && it.key != "cache" }

        val target = flow?.let { connection(it, summary) } ?: apk(fact!!)
        val scope = LabelEvidence("c:0", LabelEvidenceKind.COVERAGE,
            "package=$pkg; selected context, not full coverage: APK facts=${facts.size}, records=${flows.size}, AppOps buckets=${permissions.size}; latest APK may differ from the version that connected; no payloads")
        val exact = if (targetHost == null) emptyList() else others.filter { it.kind == ApkFactKind.ENDPOINT && LabelRules.host(it.key) == LabelRules.host(targetHost) }.take(2)
        val ranked = LabelRules.rankOps(permissions, records, 5)
        val ops = ranked.map { r ->
            val e = r.event
            LabelEvidence("p:${e.id}", LabelEvidenceKind.OPERATION,
                "AppOps ${e.op} x${e.count} ${time(e.ts)}..${time(maxOf(e.bucketEnd, e.ts))} context=${e.source} overlapsTarget=${r.overlapsTarget}; grant and cause not established")
        }
        val rules = if (flow == null) emptyList() else {
            val ids = records.map { "\"f:${it.id}\"" }
            findings.filter { f -> ids.any { it in f.evidenceJson } }.take(2).map {
                LabelEvidence("x:${it.id}", LabelEvidenceKind.FINDING, "rule=${it.ruleId} severity=${it.severity} dataTypes=${it.dataTypes}; decided by rules, not AI")
            }
        }
        val perms = LabelRules.rankPermissions(others, roles, 4)
        val components = LabelRules.rankComponents(others, roles, hostTokens, 3)
        val sdks = LabelRules.rankSdks(others, roles, hostTokens, targetHost, 2)
        val site = LabelRules.siteEndpoints(others, targetHost, 2)
        val coverage = others.filter { it.kind == ApkFactKind.COVERAGE }.take(1)
        val related = records.filter { it.id != flow?.id }.sortedByDescending { it.bytesUp + it.bytesDown }.take(2)

        val ordered = buildList {
            add(target); add(scope)
            addAll(exact.map(::apk))
            // Rule-found relations lead the APK context, so even a trimmed small-model prompt sees them.
            assessment(ref)?.let { a -> addAll((a.permissions + a.components + a.sdks).mapNotNull { it.fact }.map(::apk)) }
            addAll(ops.filterIndexed { i, _ -> ranked[i].overlapsTarget })
            addAll(rules)
            addAll(perms.map(::apk)); addAll(components.map(::apk)); addAll(sdks.map(::apk))
            addAll(site.map { apk(it, "same site as target, not the same host") })
            addAll(related.map { connection(it, null) })
            addAll(ops.filterIndexed { i, _ -> !ranked[i].overlapsTarget }.take(2))
            addAll(coverage.map(::apk))
        }.distinctBy { it.id }.take(24)
        return LabelRequest(pkg, ref, locale, ordered, summary?.domainBlockAvailable == true, subject(ref))
    }

    private fun apk(f: ApkFact, note: String? = null) = LabelEvidence("a:${f.id}", labelKind(f),
        listOfNotNull("latest APK v${f.versionCode}", "${f.kind} ${f.key}", f.value?.takeIf { it.isNotBlank() }, f.evidence?.takeIf { it.isNotBlank() }?.let { "src=$it" }, note).joinToString("; "))

    /** Compact, token-cheap record text: local times instead of epoch milliseconds, and host totals for the target only. */
    private fun connection(f: Flow, host: HostSummary?) = LabelEvidence("f:${f.id}", LabelEvidenceKind.CONNECTION, buildString {
        append("${f.proto} host=${f.host ?: "unavailable"} (${f.hostSource ?: "none"}) up=${f.bytesUp}B down=${f.bytesDown}B screen=${if (f.screenOn) "on" else "off"} at ${time(f.startAt)}")
        f.endAt?.let { append(" dur=${(it - f.startAt) / 1000}s") }
        f.company?.let { append(" company=$it") }
        f.category?.let { append(" list=$it") }
        if (host != null && host.flows > 1) append("; this host: ${host.flows} records up=${host.bytesUp}B down=${host.bytesDown}B screenOff=${host.screenOffBytes}B")
        append("; foreground unknown")
    })

    private fun time(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private companion object { val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm") }
}

/** Screen-scoped, read-only context plus explicit per-item AI generation. Never requests permissions or changes blocks. */
@HiltViewModel
class LabelViewModel @Inject constructor(
    private val apks: ApkScanDao,
    private val flows: FlowDao,
    private val permissions: PermEventDao,
    private val findings: FindingDao,
    private val analyses: AiAnalysisDao,
    private val analyzer: LabelAnalyzer,
    private val historyAnalyzer: HistoryAnalyzer,
    private val gate: ExternalAiGate,
    private val evidence: AiEvidenceService,
) : ViewModel() {
    val context = MutableStateFlow<LabelContext?>(null)
    val busyRef = MutableStateFlow<String?>(null)
    val notice = MutableStateFlow<Int?>(null)
    /** Budget of the selected model and depth setting; shown next to the generate button. */
    val budget = MutableStateFlow<LabelBudget?>(null)
    /** Whole-history AI check: (done chunks, total chunks) while running. */
    val historyProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val historyNotice = MutableStateFlow<Int?>(null)
    private var historyJob: Job? = null
    val phase get() = analyzer.phase
    private var selection: String? = null
    private var watcher: Job? = null
    private var generation: Job? = null

    @OptIn(FlowPreview::class)
    fun select(pkg: String, window: String, extra: Flow? = null) {
        val key = "$pkg|$window|${extra?.id}"
        if (key == selection) return
        selection = key
        watcher?.cancel()
        cancel()
        context.value = null
        if (pkg.isBlank() || pkg == "?" || pkg.startsWith("uid:")) return
        viewModelScope.launch { budget.value = runCatching { analyzer.budget() }.getOrNull() }
        watcher = viewModelScope.launch {
            try {
                val (start, end) = Windows.resolve(window)
                val locale = evidence.uiLocale()
                // Room re-emits on every write to a table (capture writes often): drop identical lists, then coalesce bursts
                // before building indexes once on a background dispatcher.
                combine(
                    apks.observeFacts(pkg).distinctUntilChanged(),
                    flows.observeAppHistory(pkg, start, end).distinctUntilChanged(),
                    combine(
                        analyses.observeLabels(LabelRequest.prefix(pkg), LabelContract.VERSION).distinctUntilChanged(),
                        analyses.observeLabels(HistoryContract.prefix(pkg, locale), HistoryContract.VERSION).distinctUntilChanged(),
                    ) { labels, history -> labels to history },
                    permissions.observeForApp(pkg, start, end).distinctUntilChanged(),
                    findings.observeForApp(pkg, start, end).distinctUntilChanged(),
                ) { facts, history, answers, ops, rules -> Snapshot(facts, history, answers, ops, rules) }
                    .debounce(250)
                    .map { s ->
                        val all = (s.history + listOfNotNull(extra)).distinctBy { it.id }
                        LabelContext(pkg, locale, s.facts, all, s.ops, s.rules, LabelAnswerIndex(s.answers.first, locale, knownApk(s.facts)),
                            HistoryContract.restore(s.answers.second, pkg, locale)).warm()
                    }
                    .flowOn(Dispatchers.Default)
                    .collect { context.value = it }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { notice.value = R.string.labels_failed }
        }
    }

    private class Snapshot(
        val facts: List<ApkFact>, val history: List<Flow>, val answers: Pair<List<com.nathanhanapps.wayd.core.db.AiAnalysis>, List<com.nathanhanapps.wayd.core.db.AiAnalysis>>,
        val ops: List<PermEvent>, val rules: List<Finding>,
    )

    fun generate(ref: String) {
        if (generation?.isActive == true) return
        val ctx = context.value ?: return
        notice.value = null
        busyRef.value = ref
        generation = viewModelScope.launch {
            try {
                val request = kotlinx.coroutines.withContext(Dispatchers.Default) { ctx.request(ref) } ?: return@launch
                budget.value = runCatching { analyzer.budget() }.getOrNull()
                val outcome = analyzer.analyse(request, gate::ask)
                notice.value = when (outcome) {
                    LabelOutcome.SAVED, LabelOutcome.DECLINED -> null
                    LabelOutcome.NO_MODEL -> R.string.ai_no_model
                    LabelOutcome.INVALID -> R.string.ai_unreliable
                    LabelOutcome.FAILED -> R.string.labels_failed
                }
            } finally { busyRef.value = null }
        }
    }

    fun cancel() { generation?.cancel(); busyRef.value = null; notice.value = null }

    /**
     * Checks every host of the selected app and window in a few chunked requests (most attention first), instead of one request
     * per connection. Results are saved and shown on each host row as the AI's opinion; rules and blocks are untouched.
     */
    fun checkHistory() {
        if (historyJob?.isActive == true || generation?.isActive == true) return
        val ctx = context.value ?: return
        historyNotice.value = null
        historyProgress.value = 0 to 0
        historyJob = viewModelScope.launch {
            try {
                val hosts = kotlinx.coroutines.withContext(Dispatchers.Default) { ctx.historyHosts() }
                if (hosts.isEmpty()) { historyNotice.value = R.string.history_ai_none; return@launch }
                val outcome = historyAnalyzer.check(ctx.pkg, ctx.locale, hosts, gate::ask) { done, total -> historyProgress.value = done to total }
                historyNotice.value = when (outcome) {
                    is HistoryOutcome.Done -> if (outcome.failedChunks > 0) R.string.history_ai_partial else null
                    HistoryOutcome.NoModel -> R.string.ai_no_model
                    HistoryOutcome.Declined -> R.string.xg_declined
                    is HistoryOutcome.Failed -> R.string.labels_failed
                }
            } finally { historyProgress.value = null }
        }
    }

    fun cancelHistory() { historyJob?.cancel(); historyProgress.value = null }
}
