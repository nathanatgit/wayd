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

    fun schema(items: Int): String {
        val item = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("properties", JSONObject()
                .put("id", JSONObject().put("type", "string").put("maxLength", 6))
                .put("role", JSONObject().put("type", "string").put("enum", JSONArray(ConnectionRole.entries.map { it.name })))
                .put("advice", JSONObject().put("type", "string").put("enum", JSONArray(BlockAdvice.entries.map { it.name })))
                .put("note", JSONObject().put("type", "string").put("maxLength", 80)))
            .put("required", JSONArray(listOf("id", "role", "advice", "note")))
        val props = JSONObject()
            .put("history_schema", JSONObject().put("type", "integer").put("enum", JSONArray(listOf(1))))
            .put("items", JSONObject().put("type", "array").put("maxItems", items).put("items", item))
        return JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false)
            .put("required", JSONArray(listOf("history_schema", "items"))).toString()
    }

    fun prompt(pkg: String, locale: String, chunk: List<HistoryHost>, compact: Boolean): Prompt {
        val identity = if (compact) AiIdentity.compact(locale) else AiIdentity.lines(locale).joinToString("\n")
        val system = identity + "\n" + """
            Task: for ONE app, give each destination line a role, blocking advice and a short note (why, max 60 characters). One item per id, same ids.
            role: ${ConnectionRole.entries.joinToString(" ")}. advice: TRY_TEMPORARY (reversible block trial), MAY_AFFECT_FUNCTION (may break a feature), INSUFFICIENT.
            Lines marked noblock must get INSUFFICIENT. Never set severity or choose another target.
            Line fields: hint=rule role (may be wrong); rec=records; up/down=bytes; off=screen-off share; apk=hostname is in the APK;
            perm=possibly related permissions (* used at the same time; not proof); comp=possibly related component; rule=finding decided by rules.
            JSON only: {"history_schema":1,"items":[{"id":"h1","role":"UNKNOWN","advice":"INSUFFICIENT","note":""}]}
        """.trimIndent()
        val user = buildString {
            appendLine("app=${PromptBuilder.neutralise(pkg, 80)}")
            chunk.forEachIndexed { i, h ->
                appendLine("<data id=\"h${i + 1}\">${if (h.blockable) "" else "noblock "}${PromptBuilder.neutralise(Masker.mask(h.line), LINE_CHARS)}</data>")
            }
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
