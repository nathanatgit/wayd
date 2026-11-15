package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.db.AiAnalysis
import com.nathanhanapps.wayd.core.db.AiAnalysisDao
import com.nathanhanapps.wayd.core.model.BlockAdvice
import com.nathanhanapps.wayd.core.model.ConnectionRole
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** MESSAGE: one decrypted request or response of the target host (masked like every other item). */
enum class LabelEvidenceKind { CONNECTION, PERMISSION, FEATURE, ENDPOINT, SDK, OPERATION, FINDING, COVERAGE, MESSAGE }
data class LabelEvidence(val id: String, val kind: LabelEvidenceKind, val text: String)

/**
 * One user-started question about [target]. [subject] is what the answer describes and how it is found again: the APK fact itself,
 * or `h:<host>` for a connection, so every record of the same host shares one saved interpretation.
 */
data class LabelRequest(
    val pkg: String,
    val target: String,
    val locale: String,
    val evidence: List<LabelEvidence>,
    val domainBlockAvailable: Boolean,
    val subject: String = target,
) {
    init {
        require(evidence.size in 1..24 && evidence.map { it.id }.distinct().size == evidence.size)
        require(evidence.any { it.id == target })
        require(target.matches(TARGET))
        require(subject.matches(TARGET) || subject.matches(HOST_SUBJECT))
        require(evidence.all { it.id.matches(Regex("[afpcxm]:[0-9]+")) })
    }
    val targetPrefix: String get() = prefixFor(pkg, subject, locale)
    val key: String get() = targetPrefix + digest(locale + domainBlockAvailable + evidence.joinToString { "${it.id}:${it.kind}:${it.text}" })
    companion object {
        private val TARGET = Regex("[af]:[0-9]+")
        private val HOST_SUBJECT = Regex("h:[a-z0-9.-]{1,253}")
        /** Package and subject are digested so the stored key never holds a plaintext package or host. */
        fun prefix(pkg: String) = "${LabelContract.ROW_PREFIX}${digest(pkg)}|"
        fun prefixFor(pkg: String, subject: String, locale: String) = prefix(pkg) + digest(subject).take(32) + "|" + locale + "|"
        internal fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Every relationship is an AI hypothesis, never a new captured fact or policy target. */
data class LabelAnswer(
    val role: ConnectionRole,
    val explanation: String,
    val permissionRefs: List<String>,
    val featureRefs: List<String>,
    val featureNames: Map<String, String>,
    val evidence: List<String>,
    val advice: BlockAdvice,
    val reason: String,
    /** The record or fact the model was actually asked about; for a host subject it may be another record of that host. */
    val target: String = "",
    val model: String? = null,
    val createdAt: Long? = null,
)

object LabelContract {
    const val VERSION = 102
    /** Row prefix shared by every saved label, for one bounded query across packages. */
    const val ROW_PREFIX = "labels-v2|"
    const val MAX_LINKS = 4
    const val MAX_EXTRA = 8

    val jsonSchema: String by lazy {
        fun string(max: Int) = JSONObject().put("type", "string").put("maxLength", max)
        fun list(max: Int) = JSONObject().put("type", "array").put("maxItems", max).put("items", string(24))
        val props = JSONObject()
            .put("label_schema", JSONObject().put("type", "integer").put("enum", JSONArray(listOf(1))))
            .put("target_id", string(24))
            .put("role", JSONObject().put("type", "string").put("enum", JSONArray(ConnectionRole.entries.map { it.name })))
            .put("explanation", string(80)).put("permission_refs", list(MAX_LINKS)).put("feature_refs", list(MAX_LINKS))
            .put("feature_labels", JSONObject().put("type", "array").put("maxItems", MAX_LINKS).put("items", string(60))).put("evidence_ids", list(MAX_EXTRA))
            .put("advice", JSONObject().put("type", "string").put("enum", JSONArray(BlockAdvice.entries.map { it.name })))
            .put("reason", string(240))
        JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false)
            .put("required", JSONArray(props.keys().asSequence().toList())).toString()
    }

    /**
     * Per-request schema: role and advice are fixed where the target leaves no choice, refs can only name supplied ids of the right
     * kind, and the free text comes before each decision (the engine writes properties in schema order, so the model reasons before
     * it commits). This turns the rules [parse] enforces into constraints a small model cannot break: in tools/promptlab Gemma E2B
     * invented ids (`android.permission.アクセス_`, `a:312`,`a:313`) and gave roles to permissions, failing 23% of labels; 0% with this.
     */
    fun schemaFor(req: LabelRequest): String {
        fun string(max: Int) = JSONObject().put("type", "string").put("maxLength", max)
        fun ids(values: List<String>, max: Int) = JSONObject().put("type", "array").put("maxItems", if (values.isEmpty()) 0 else max)
            .put("items", if (values.isEmpty()) string(1) else JSONObject().put("type", "string").put("enum", JSONArray(values)))
        fun enumOf(values: List<String>) = JSONObject().put("type", "string").put("enum", JSONArray(values))
        val kind = req.evidence.first { it.id == req.target }.kind
        val network = kind == LabelEvidenceKind.CONNECTION || kind == LabelEvidenceKind.ENDPOINT
        fun of(k: LabelEvidenceKind) = if (network) req.evidence.filter { it.kind == k }.map { it.id } else emptyList()
        val features = of(LabelEvidenceKind.FEATURE)
        // Insertion order is the generation order (Android's JSONObject keeps it).
        val props = JSONObject()
            .put("label_schema", JSONObject().put("type", "integer").put("enum", JSONArray(listOf(1))))
            .put("target_id", enumOf(listOf(req.target)))
            .put("explanation", string(80))
            .put("role", enumOf(if (network) ConnectionRole.entries.map { it.name } else listOf(ConnectionRole.UNKNOWN.name)))
            .put("permission_refs", ids(of(LabelEvidenceKind.PERMISSION), MAX_LINKS))
            .put("feature_refs", ids(features, MAX_LINKS))
            .put("feature_labels", JSONObject().put("type", "array").put("maxItems", if (features.isEmpty()) 0 else MAX_LINKS).put("items", string(60)))
            .put("evidence_ids", ids(req.evidence.map { it.id }.filter { it != req.target }, MAX_EXTRA))
            .put("reason", string(240))
            .put("advice", enumOf(if (adviceAllowed(req, kind)) BlockAdvice.entries.map { it.name } else listOf(BlockAdvice.INSUFFICIENT.name)))
        return JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false)
            .put("required", JSONArray(props.keys().asSequence().toList())).toString()
    }

    private fun adviceAllowed(req: LabelRequest, kind: LabelEvidenceKind) = kind == LabelEvidenceKind.CONNECTION && req.domainBlockAvailable

    /**
     * Same rules in fewer tokens for on-device models. The constraints that depend on the target (role, links, advice) are decided
     * here rather than stated as conditionals a 2B model has to apply, and the template uses placeholders, not default values: E2B
     * copied the old template's "UNKNOWN"/"INSUFFICIENT" into every label, even for hosts it described as a CDN.
     */
    private fun compactSystem(req: LabelRequest): String {
        val kind = req.evidence.first { it.id == req.target }.kind
        val network = kind == LabelEvidenceKind.CONNECTION || kind == LabelEvidenceKind.ENDPOINT
        return buildList {
            add(AiIdentity.compact(req.locale))
            add("Task: explain the item ${req.target} (kind=$kind) for the user. Never invent facts or links.")
            add("role = purpose, not severity: FUNCTIONAL app API, LOGIN auth, LOCATION geo, PUSH push, ANALYTICS stats/tracking, DIAGNOSTICS crash/APM, " +
                "ADS ads, MEDIA streams, CONTENT CDN/static, UPDATE updates, CARRIER, SYSTEM OS/DNS, UNKNOWN.")
            if (network) {
                add("Judge role from the hostname and company first.")
                add("permission_refs: up to $MAX_LINKS PERMISSION ids that plausibly relate; feature_refs: up to $MAX_LINKS FEATURE ids; " +
                    "feature_labels: one plain feature name per feature_ref (not a class name). Leave them empty when unsure.")
                add("APK presence is not proof of use. overlapsTarget=true is co-occurrence, not cause. Same company is not SDK proof.")
                if (req.evidence.any { it.kind == LabelEvidenceKind.MESSAGE }) add("MESSAGE items are decrypted content of this host: the strongest evidence of what it is for and what is sent. [ID]-style values are masked identifiers.")
            } else {
                add("This is not a connection: role UNKNOWN, permission_refs/feature_refs/feature_labels empty. Explain the capability or feature in plain words.")
            }
            add(if (adviceAllowed(req, kind)) "advice: TRY_TEMPORARY for ADS/ANALYTICS/DIAGNOSTICS (reversible trial); MAY_AFFECT_FUNCTION when blocking may break a feature; INSUFFICIENT if unclear."
                else "advice must be INSUFFICIENT (no domain block is possible for this item).")
            add("evidence_ids: up to $MAX_EXTRA OTHER supplied ids that support you (not ${req.target}).")
            add("explanation: a short label, max 40 characters. reason: what the evidence shows and what blocking could affect, max 200 characters.")
            add("Write explanation, feature_labels and reason in ${AiIdentity.language(req.locale)}; no AI: prefix.")
            add("JSON only: {\"label_schema\":1,\"target_id\":\"${req.target}\",\"explanation\":\"…\",\"role\":\"…\",\"permission_refs\":[],\"feature_refs\":[],\"feature_labels\":[],\"evidence_ids\":[],\"reason\":\"…\",\"advice\":\"…\"}")
        }.joinToString("\n")
    }

    fun prompt(req: LabelRequest, compact: Boolean = false): Prompt {
        val system = if (compact) compactSystem(req) else AiIdentity.lines(req.locale).joinToString("\n") + "\n" + """
            Task: explain ONE WAYD item: a connection, APK endpoint, declared permission, or declared component/feature. Do not invent facts or links.
            Return one JSON object matching the supplied schema. target_id must be ${req.target} and label_schema must be 1.
            Roles describe purpose, never severity: ${ConnectionRole.entries.joinToString()}. CONTENT means content delivery (CDN, images, static files).
            For permissions explain the capability in simple words; for components explain the possible user-facing feature. Use UNKNOWN role for these.
            For connections/endpoints suggest a role from the hostname, company and context; a connection answer describes every record of that hostname. explanation is a short human-readable marker, at most 80 characters.
            permission_refs may name only PERMISSION evidence; feature_refs only FEATURE evidence; at most $MAX_LINKS each, no duplicates. Links mean POSSIBLE association, never proof of causation.
            feature_labels must have one short human-readable feature name per feature_refs entry, in the same order, not a Java class name. Both arrays are empty when there are no linked features.
            The target and linked refs are cited automatically. evidence_ids lists at most $MAX_EXTRA ADDITIONAL supplied IDs that support the explanation; it may be empty.
            APK presence does not prove execution, grants or transmission. A hostname in the latest APK does not prove historical version identity.
            OPERATION records are time buckets: persistent, background, cached and foreground-service are distinct contexts. Do not guess foreground from a false placeholder.
            MESSAGE items are decrypted requests/responses of this host (identifiers masked as [ID]-style tokens): the strongest evidence of purpose and of what is sent; cite them when used.
            overlapsTarget=true only means the bucket and the connection times overlap; it is co-occurrence, not cause.
            Zero recorded bytes do not prove an upload. HTTP_HOST does not prove readable user content. Same company does not establish SDK attribution.
            Omit uncertain links instead of listing every permission/component in the app.
            Blocking advice considers purpose, possible permission/feature associations, privacy benefit AND likely function breakage.
            TRY_TEMPORARY means consider a reversible trial, MAY_AFFECT_FUNCTION means possible feature breakage, INSUFFICIENT means evidence is inadequate.
            Only CONNECTION targets with domainBlockAvailable=true may receive advice other than INSUFFICIENT. Never choose a blocking target, set severity, grant consent or change rules.
            reason explains evidence and possible impact in at most 240 characters. No instructions to block an IP or a parent domain.
            Write explanation, feature_labels and reason in ${if (req.locale.startsWith("zh")) "Simplified Chinese" else "English"}. Do not include AI: prefixes; the UI adds them.
        """.trimIndent() + "\nReturn exactly this JSON schema (also applies without constrained decoding):\n" + jsonSchema
        val user = buildString {
            appendLine("domainBlockAvailable=${req.domainBlockAvailable}; target=${req.target}")
            req.evidence.forEach { e ->
                appendLine("<data id=\"${e.id}\">kind=${e.kind}; ${PromptBuilder.neutralise(Masker.mask(e.text), 700)}</data>")
            }
            // Said again at the very end: with the language named only mid-system, Claude Haiku answered 3 of 5 zh-CN labels in English.
            append("Explain ${req.target}. Write explanation, feature_labels and reason in ${AiIdentity.language(req.locale)}")
            append(if (req.locale.startsWith("zh")) "（简体中文）。" else ".")
        }
        return Prompt(system, user, req.evidence.map { it.id }.toSet())
    }

    /** Fail closed on wrong targets, fabricated or duplicate refs, wrong relationship kinds, and advice without a real hostname. */
    fun parse(raw: String, req: LabelRequest): LabelAnswer? = runCatching {
        require(raw.length <= 64_000)
        val o = JSONObject(SchemaValidator.extractJson(raw) ?: error("json"))
        require(o.getInt("label_schema") == 1 && o.getString("target_id") == req.target)
        val byId = req.evidence.associateBy { it.id }
        fun ids(name: String): List<String> {
            val a = o.getJSONArray(name)
            return (0 until a.length()).map { a.getString(it).also { id -> require(id in byId) } }
        }
        fun links(name: String): List<String> = ids(name).also { require(it.size <= MAX_LINKS && it.distinct().size == it.size) }
        fun text(name: String, max: Int): String = o.getString(name).trim().also {
            require(it.length <= max && it.none { ch -> ch.isISOControl() || ch == '<' || ch == '>' })
        }
        val permissionRefs = links("permission_refs").also { ids -> require(ids.all { byId.getValue(it).kind == LabelEvidenceKind.PERMISSION }) }
        val featureRefs = links("feature_refs").also { ids -> require(ids.all { byId.getValue(it).kind == LabelEvidenceKind.FEATURE }) }
        val names = o.getJSONArray("feature_labels")
        require(names.length() == featureRefs.size)
        val featureNames = featureRefs.mapIndexed { index, ref ->
            val name = names.getString(index).trim()
            require(name.length in 1..60 && name.none { it.isISOControl() || it == '<' || it == '>' })
            ref to name
        }.toMap()
        // Extra supports only; the target and links are cited here. Repeating them (older replies did) is tolerated.
        val extra = ids("evidence_ids").filter { it != req.target && it !in permissionRefs && it !in featureRefs }.distinct()
        require(extra.size <= MAX_EXTRA)
        val evidence = listOf(req.target) + permissionRefs + featureRefs + extra
        val role = ConnectionRole.valueOf(o.getString("role"))
        val advice = BlockAdvice.valueOf(o.getString("advice"))
        val kind = byId.getValue(req.target).kind
        val network = kind in setOf(LabelEvidenceKind.CONNECTION, LabelEvidenceKind.ENDPOINT)
        require(network || role == ConnectionRole.UNKNOWN)
        require(network || (permissionRefs.isEmpty() && featureRefs.isEmpty()))
        require(advice == BlockAdvice.INSUFFICIENT || (kind == LabelEvidenceKind.CONNECTION && req.domainBlockAvailable))
        val explanation = text("explanation", 80).also { require(it.isNotBlank()) }
        val reason = text("reason", 240).also { require(it.isNotBlank()) }
        LabelAnswer(role, explanation, permissionRefs, featureRefs, featureNames, evidence, advice, reason, req.target)
    }.getOrNull()

    /**
     * Restore a saved interpretation of its original bounded input. APK refs must still exist with the same kind in [knownApk]
     * (a rescan replaces fact rows, so stale links make the answer disappear); history refs stay as saved. Advice is lowered to
     * INSUFFICIENT when the current records no longer support a domain block.
     */
    fun restore(row: AiAnalysis, pkg: String, subject: String, locale: String, domainBlockAvailable: Boolean, knownApk: Map<String, LabelEvidenceKind>? = null): LabelAnswer? = runCatching {
        require(row.validated && row.promptVersion == VERSION && row.resultJson.length <= 64_000)
        require(row.inputRefsJson.startsWith(LabelRequest.prefixFor(pkg, subject, locale)))
        val saved = JSONObject(row.resultJson)
        val input = saved.getJSONArray("_input")
        require(input.length() in 1..24)
        val evidence = (0 until input.length()).mapNotNull {
            val e = input.getJSONObject(it)
            val id = e.getString("id")
            val kind = LabelEvidenceKind.valueOf(e.getString("kind"))
            val text = e.getString("text").also { require(it.length <= 700) }
            if (knownApk == null || !id.startsWith("a:") || knownApk[id] == kind) LabelEvidence(id, kind, text) else null
        }
        val stored = LabelRequest(pkg, saved.getString("target_id"), locale, evidence, saved.optBoolean("_dba", false), subject)
        val answer = parse(row.resultJson, stored) ?: return@runCatching null
        answer.copy(advice = if (domainBlockAvailable) answer.advice else BlockAdvice.INSUFFICIENT, model = row.model, createdAt = row.createdAt)
    }.getOrNull()
}

/**
 * Saved answers grouped once by subject prefix (rows arrive newest first). Lookups are hash hits and each subject is restored at most
 * once per index, so list rows never re-parse JSON on recomposition.
 */
class LabelAnswerIndex(rows: List<AiAnalysis>, private val locale: String, private val knownApk: Map<String, LabelEvidenceKind>?) {
    private val bySubject: Map<String, List<AiAnalysis>> = rows.groupBy { it.inputRefsJson.substringBeforeLast('|') + "|" }
    private val cache = HashMap<String, LabelAnswer?>()

    fun has(pkg: String, subject: String): Boolean = LabelRequest.prefixFor(pkg, subject, locale) in bySubject

    @Synchronized
    fun get(pkg: String, subject: String, domainBlockAvailable: Boolean): LabelAnswer? {
        val prefix = LabelRequest.prefixFor(pkg, subject, locale)
        val candidates = bySubject[prefix] ?: return null
        val key = "$prefix$domainBlockAvailable"
        if (key in cache) return cache[key]
        return candidates.firstNotNullOfOrNull { LabelContract.restore(it, pkg, subject, locale, domainBlockAvailable, knownApk) }.also { cache[key] = it }
    }
}

/**
 * Context size per model. The phone engine has an 8K-token window for prompt + answer (LiteRtEngine.MAX_TOKENS), and small edge
 * models lose instruction-following and speed well before that, so local runs get fewer, shorter items and a compact prompt.
 * Rough sizes (≈3.5 chars/token for Latin text, ≈1 token per CJK char): E2B ≈ 1.2K prompt tokens, E4B ≈ 1.8K, external ≈ 4.5K.
 * Items arrive in priority order; the target is always kept.
 */
data class LabelBudget(val maxItems: Int, val itemChars: Int, val totalChars: Int, val compact: Boolean) {
    fun fit(req: LabelRequest): LabelRequest {
        var used = 0
        val kept = ArrayList<LabelEvidence>()
        for (e in req.evidence) {
            val item = e.copy(text = e.text.take(itemChars))
            val cost = item.text.length + 40 // id/kind wrapper
            if (e.id == req.target || (kept.size < maxItems && used + cost <= totalChars)) {
                kept += item
                used += cost
            }
        }
        // The target was always added; if it came late, drop the lowest-priority item to respect the count.
        while (kept.size > maxItems) kept.removeAt(kept.indexOfLast { it.id != req.target })
        return req.copy(evidence = kept)
    }

    companion object {
        val SMALL = LabelBudget(maxItems = 10, itemChars = 220, totalChars = 2_200, compact = true)
        val MEDIUM = LabelBudget(maxItems = 14, itemChars = 300, totalChars = 3_800, compact = true)
        val LARGE = LabelBudget(maxItems = 24, itemChars = 700, totalChars = 12_000, compact = false)

        /** The user's context size and effort for this provider (Settings → AI); AUTO picks by model. */
        fun resolve(modelId: String, depth: AiDepth = AiDepth()): LabelBudget = when (depth.size(modelId)) {
            ContextSize.SMALL -> SMALL
            ContextSize.MEDIUM -> MEDIUM
            ContextSize.LARGE, ContextSize.AUTO -> LARGE
        }.copy(compact = depth.compact(modelId))
    }
}

enum class LabelOutcome { SAVED, NO_MODEL, INVALID, FAILED, DECLINED }

/** Reuses the encrypted analysis table and the normal external-provider gate. No writes to facts, findings or policy. */
class LabelAnalyzer @Inject constructor(private val engine: AiEngine, private val dao: AiAnalysisDao, private val depths: AiDepthSource) {
    val phase get() = engine.phase

    /** Budget of the currently selected model and depth, for the UI's disclosure of how many items are sent. */
    suspend fun budget(): LabelBudget = engine.modelId().let { LabelBudget.resolve(it, depths.depthFor(it)) }

    suspend fun analyse(full: LabelRequest, approve: suspend (ExternalSend) -> Boolean): LabelOutcome {
        try {
            if (!engine.isReady()) return LabelOutcome.NO_MODEL
            val model = engine.modelId()
            val depth = depths.depthFor(model)
            val budget = LabelBudget.resolve(model, depth)
            // Fit before keying, so the saved input is exactly what this model saw.
            val req = budget.fit(full)
            val prompt = LabelContract.prompt(req, budget.compact)
            val approvedDestination = engine.externalDestination()
            approvedDestination?.let { destination ->
                if (!approve(ExternalSend(destination, model.removePrefix("ext:"), prompt.system, prompt.user, req.evidence.size, false))) return LabelOutcome.DECLINED
            }
            if (engine.modelId() != model || engine.externalDestination() != approvedDestination) return LabelOutcome.DECLINED
            val started = System.currentTimeMillis()
            val schema = LabelContract.schemaFor(req)
            var reply = engine.generate(prompt.system, prompt.user, schema)
            if (LabelContract.parse(reply, req) == null && depth.repair(model)) {
                if (engine.modelId() != model || engine.externalDestination() != approvedDestination) return LabelOutcome.DECLINED
                reply = engine.generate(prompt.system, prompt.user + "\nThe answer failed validation. Return a valid object; use no uncertain links and INSUFFICIENT advice if needed.", schema)
            }
            if (LabelContract.parse(reply, req) == null) return LabelOutcome.INVALID
            // Preserve the bounded input IDs/kinds and the block availability the answer was validated against.
            val saved = JSONObject(SchemaValidator.extractJson(reply)!!).put("_dba", req.domainBlockAvailable).put("_input", JSONArray(req.evidence.map {
                JSONObject().put("id", it.id).put("kind", it.kind.name).put("text", PromptBuilder.neutralise(Masker.mask(it.text), 700))
            }))
            dao.insert(AiAnalysis(inputRefsJson = req.key, model = model, promptVersion = LabelContract.VERSION,
                resultJson = saved.toString(), validated = true, createdAt = System.currentTimeMillis(), durationMs = System.currentTimeMillis() - started))
            return LabelOutcome.SAVED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return LabelOutcome.FAILED
        }
    }
}
