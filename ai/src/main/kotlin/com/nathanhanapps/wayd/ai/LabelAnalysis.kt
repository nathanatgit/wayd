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

enum class LabelEvidenceKind { CONNECTION, PERMISSION, FEATURE, ENDPOINT, SDK, OPERATION, FINDING, COVERAGE }
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
        require(evidence.all { it.id.matches(Regex("[afpcx]:[0-9]+")) })
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

    /** Same rules in fewer tokens for on-device models; constrained decoding enforces the schema, so a one-line template suffices. */
    private fun compactSystem(req: LabelRequest): String = AiIdentity.compact(req.locale) + "\n" + """
        Task: explain ONE item (connection, APK endpoint, permission or component). Never invent facts or links. target_id=${req.target}, label_schema=1.
        role (purpose, not severity): ${ConnectionRole.entries.joinToString(" ")}. Permissions/components: role UNKNOWN, no links.
        explanation: short marker, max 80 chars. Links (max $MAX_LINKS each, no duplicates) are POSSIBLE associations, not proof:
        permission_refs only PERMISSION ids; feature_refs only FEATURE ids; feature_labels = one plain feature name per feature_ref.
        evidence_ids = extra supporting ids only (target and links are cited automatically), max $MAX_EXTRA. Omit uncertain links.
        APK presence is not execution, grant or transmission. overlapsTarget=true is co-occurrence, not cause. Same company is not SDK proof.
        advice: TRY_TEMPORARY (reversible trial), MAY_AFFECT_FUNCTION (may break a feature), INSUFFICIENT. Only CONNECTION with domainBlockAvailable=true may be other than INSUFFICIENT.
        Never choose a block target or severity. reason: evidence and possible impact, max 240 chars.
        Write explanation, feature_labels, reason in ${if (req.locale.startsWith("zh")) "Simplified Chinese" else "English"}; no AI: prefix.
        Reply with JSON only: {"label_schema":1,"target_id":"${req.target}","role":"UNKNOWN","explanation":"","permission_refs":[],"feature_refs":[],"feature_labels":[],"evidence_ids":[],"advice":"INSUFFICIENT","reason":""}
    """.trimIndent()

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
            var reply = engine.generate(prompt.system, prompt.user, LabelContract.jsonSchema)
            if (LabelContract.parse(reply, req) == null && depth.repair(model)) {
                if (engine.modelId() != model || engine.externalDestination() != approvedDestination) return LabelOutcome.DECLINED
                reply = engine.generate(prompt.system, prompt.user + "\nThe answer failed validation. Return a valid object; use no uncertain links and INSUFFICIENT advice if needed.", LabelContract.jsonSchema)
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
