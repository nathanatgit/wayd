package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.db.AiAnalysis
import com.nathanhanapps.wayd.core.db.AiAnalysisDao
import com.nathanhanapps.wayd.core.model.BlockAdvice
import com.nathanhanapps.wayd.core.model.ConnectionRole
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * One destination of one app, already reduced by the rules to a single compact line (role hint, traffic, related permissions and
 * components, rule finding). [blockable] says whether a domain block is possible at all, so the model cannot suggest one otherwise.
 */
data class HistoryHost(val host: String, val line: String, val blockable: Boolean)

data class HistoryNote(val role: ConnectionRole, val advice: BlockAdvice, val note: String, val model: String = "", val at: Long = 0)

sealed interface HistoryOutcome {
    data class Done(val checked: Int, val failedChunks: Int) : HistoryOutcome
    data object NoModel : HistoryOutcome
    data object Declined : HistoryOutcome
    data class Failed(val code: String) : HistoryOutcome
}

/**
 * Whole-history check: many hosts per request instead of one request per connection. Hosts are sent in chunks sized for the model
 * (8 lines for Gemma E2B, 14 for E4B, 40 for external), each line under ~160 characters, so a chunk is roughly 0.6K–2K prompt
 * tokens and the JSON answer stays short. A failed chunk is skipped, not fatal.
 */
object HistoryContract {
    const val VERSION = 201
    const val ROW_PREFIX = "history-v1|"
    const val MAX_HOSTS = 60
    private const val LINE_CHARS = 160

    fun chunkSize(size: ContextSize): Int = when (size) {
        ContextSize.SMALL -> 8
        ContextSize.MEDIUM -> 14
        ContextSize.LARGE, ContextSize.AUTO -> 40
    }

    fun prefix(pkg: String, locale: String) = ROW_PREFIX + LabelRequest.digest(pkg) + "|" + locale + "|"

    /**
     * Exactly [items] items with ids h1..hN, so constrained decoding cannot stop after the first item or invent an id (Gemma E2B did
     * both). The note comes before role and advice: the engine writes properties in schema order, and a small model that states its
     * reason first picks the matching role/advice far more often (tools/promptlab: E2B advice 36% → 94% on the synthetic hosts).
     */
    fun schema(items: Int): String {
        val item = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string").put("enum", JSONArray((1..items).map { "h$it" })))
                .put("note", JSONObject().put("type", "string").put("maxLength", 80))
                .put("role", JSONObject().put("type", "string").put("enum", JSONArray(ConnectionRole.entries.map { it.name })))
                .put("advice", JSONObject().put("type", "string").put("enum", JSONArray(BlockAdvice.entries.map { it.name }))))
            .put("required", JSONArray(listOf("id", "note", "role", "advice")))
        val props = JSONObject()
            .put("history_schema", JSONObject().put("type", "integer").put("enum", JSONArray(listOf(1))))
            .put("items", JSONObject().put("type", "array").put("minItems", items).put("maxItems", items).put("items", item))
        return JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false)
            .put("required", JSONArray(listOf("history_schema", "items"))).toString()
    }

    /**
     * Role and advice are spelled out per value: a bare enum list left small models guessing (E2B gave UNKNOWN/INSUFFICIENT, the values
     * of the old template, to hosts it described correctly). The example is filled in the answer language, not with empty defaults.
     */
    fun prompt(pkg: String, locale: String, chunk: List<HistoryHost>, compact: Boolean): Prompt {
        val zh = locale.startsWith("zh")
        val identity = if (compact) AiIdentity.compact(locale) else AiIdentity.lines(locale).joinToString("\n")
        val example = if (zh) "广告SDK，屏幕关闭时仍上传" else "ad SDK, uploads with screen off"
        val system = identity + "\n" + """
            Task: for ONE app, label every <data> line h1..hN: note (why), then role and advice. Exactly one item per id, in order.
            role = what the host is for, judged from the hostname first, then the fields:
            FUNCTIONAL app's own API; LOGIN accounts/auth; LOCATION maps/geo; PUSH push/long connection; ANALYTICS stats/logs/tracking;
            DIAGNOSTICS crash/APM/perf; ADS ad networks; MEDIA video/audio streams; CONTENT CDN/images/static; UPDATE app/config updates;
            CARRIER telecom; SYSTEM OS/DNS/time/connectivity; UNKNOWN only if the hostname gives no clue.
            hint= is a rule guess and may be wrong; correct it when the hostname clearly says otherwise.
            advice: ADS, ANALYTICS, DIAGNOSTICS -> TRY_TEMPORARY (a reversible block trial rarely breaks the app).
            FUNCTIONAL, LOGIN, PUSH, CONTENT, MEDIA, UPDATE, LOCATION, SYSTEM, CARRIER -> MAY_AFFECT_FUNCTION.
            noblock lines, or UNKNOWN with no clue -> INSUFFICIENT. Never set severity or choose another target.
            note: the reason in at most 40 characters, from the line (hostname word, screen-off share, upload size, perm). No severity words.
            Fields: rec=records up/down=bytes off=screen-off share dec=decrypted requests (count, then method and path: strong evidence of purpose) apk=host is in the APK perm=possibly related permissions (*=same time, not proof) comp=component rule=finding from rules.
            JSON only, e.g. {"history_schema":1,"items":[{"id":"h1","note":"$example","role":"ADS","advice":"TRY_TEMPORARY"}]}
        """.trimIndent()
        val user = buildString {
            appendLine("app=${PromptBuilder.neutralise(pkg, 80)}")
            chunk.forEachIndexed { i, h ->
                appendLine("<data id=\"h${i + 1}\">${if (h.blockable) "" else "noblock "}${PromptBuilder.neutralise(Masker.mask(h.line), LINE_CHARS)}</data>")
            }
            // Said again at the very end, where small models pay the most attention.
            append("Label all ${chunk.size} lines h1..h${chunk.size}. Notes in ${AiIdentity.language(locale)}").append(if (zh) " (简体中文)." else ".")
        }
        return Prompt(system, user, chunk.indices.map { "h${it + 1}" }.toSet())
    }

    /** Items for unknown ids or with invalid vocabulary are dropped; advice on a non-blockable host is lowered, not trusted. */
    fun parse(raw: String, chunk: List<HistoryHost>): Map<String, HistoryNote>? = runCatching {
        require(raw.length <= 64_000)
        val o = JSONObject(SchemaValidator.extractJson(raw) ?: error("json"))
        require(o.getInt("history_schema") == 1)
        val items = o.getJSONArray("items")
        val out = LinkedHashMap<String, HistoryNote>()
        for (i in 0 until minOf(items.length(), chunk.size * 2)) {
            val item = items.optJSONObject(i) ?: continue
            val index = item.optString("id").removePrefix("h").toIntOrNull()?.minus(1) ?: continue
            val host = chunk.getOrNull(index) ?: continue
            val role = runCatching { ConnectionRole.valueOf(item.getString("role")) }.getOrNull() ?: continue
            val advice = runCatching { BlockAdvice.valueOf(item.getString("advice")) }.getOrNull() ?: continue
            val note = item.optString("note").trim().filter { !it.isISOControl() }.replace('<', '‹').replace('>', '›').take(80)
            out.putIfAbsent(host.host, HistoryNote(role, if (host.blockable) advice else BlockAdvice.INSUFFICIENT, note))
        }
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Newest note per host across saved runs (rows arrive newest first). */
    fun restore(rows: List<AiAnalysis>, pkg: String, locale: String): Map<String, HistoryNote> {
        val prefix = prefix(pkg, locale)
        val out = HashMap<String, HistoryNote>()
        rows.filter { it.validated && it.promptVersion == VERSION && it.inputRefsJson.startsWith(prefix) && it.resultJson.length <= 256_000 }.forEach { row ->
            runCatching {
                val hosts = JSONObject(row.resultJson).getJSONObject("hosts")
                hosts.keys().forEach { host ->
                    val h = hosts.getJSONObject(host)
                    out.putIfAbsent(host, HistoryNote(ConnectionRole.valueOf(h.getString("role")), BlockAdvice.valueOf(h.getString("advice")), h.optString("note").take(80), row.model, row.createdAt))
                }
            }
        }
        return out
    }
}

/** Same gates as every other AI feature: readiness, the external preview per send, depth budget, one repair where allowed. */
class HistoryAnalyzer @Inject constructor(private val engine: AiEngine, private val dao: AiAnalysisDao, private val depths: AiDepthSource) {
    val phase get() = engine.phase

    suspend fun check(pkg: String, locale: String, hosts: List<HistoryHost>, approve: suspend (ExternalSend) -> Boolean, progress: (done: Int, total: Int) -> Unit): HistoryOutcome {
        try {
            if (!engine.isReady()) return HistoryOutcome.NoModel
            val model = engine.modelId()
            val depth = depths.depthFor(model)
            val chunks = hosts.take(HistoryContract.MAX_HOSTS).chunked(HistoryContract.chunkSize(depth.size(model)))
            val destination = engine.externalDestination()
            val notes = LinkedHashMap<String, HistoryNote>()
            var failed = 0
            val started = System.currentTimeMillis()
            chunks.forEachIndexed { index, chunk ->
                progress(index, chunks.size)
                val prompt = HistoryContract.prompt(pkg, locale, chunk, depth.compact(model))
                destination?.let { if (!approve(ExternalSend(it, model.removePrefix("ext:"), prompt.system, prompt.user, chunk.size, false))) return if (notes.isEmpty()) HistoryOutcome.Declined else save(pkg, locale, model, notes, failed, started) }
                if (engine.modelId() != model || engine.externalDestination() != destination) return HistoryOutcome.Declined
                val schema = HistoryContract.schema(chunk.size)
                var parsed = HistoryContract.parse(engine.generate(prompt.system, prompt.user, schema), chunk)
                if (parsed == null && depth.repair(model)) {
                    parsed = HistoryContract.parse(engine.generate(prompt.system, prompt.user + "\nThe answer failed validation. Return one valid JSON object with one item per id.", schema), chunk)
                }
                if (parsed == null) failed++ else notes.putAll(parsed)
            }
            progress(chunks.size, chunks.size)
            return save(pkg, locale, model, notes, failed, started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExternalAiException) {
            return HistoryOutcome.Failed(e.code)
        } catch (e: Exception) {
            return HistoryOutcome.Failed(e.message?.take(80) ?: e.javaClass.simpleName)
        }
    }

    private suspend fun save(pkg: String, locale: String, model: String, notes: Map<String, HistoryNote>, failed: Int, started: Long): HistoryOutcome {
        if (notes.isEmpty()) return HistoryOutcome.Failed("invalid")
        val hosts = JSONObject()
        notes.forEach { (host, n) -> hosts.put(host, JSONObject().put("role", n.role.name).put("advice", n.advice.name).put("note", n.note)) }
        val now = System.currentTimeMillis()
        dao.insert(AiAnalysis(inputRefsJson = HistoryContract.prefix(pkg, locale) + now, model = model, promptVersion = HistoryContract.VERSION,
            resultJson = JSONObject().put("hosts", hosts).toString(), validated = true, createdAt = now, durationMs = now - started))
        return HistoryOutcome.Done(notes.size, failed)
    }
}
