package com.nathanhanapps.wayd.ai

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** One earlier exchange, text only. Earlier answers are model output and are replayed as untrusted history, never as facts. */
data class ChatTurn(val question: String, val answer: String)

/**
 * One Insights chat question. [evidence] is the selected window's report (same rows the Insights tab shows), [ruleNotes] the rule
 * findings as context, [media] what the user attached for this question only.
 */
data class ChatRequest(
    val subject: String,
    val locale: String,
    val evidence: List<EvidenceItem>,
    val ruleNotes: List<String>,
    val question: String,
    val history: List<ChatTurn> = emptyList(),
    val media: List<AiMedia> = emptyList(),
) {
    init {
        require(question.isNotBlank() || media.any { it.kind == MediaKind.AUDIO }) { "a question needs text or a voice clip" }
        require(media.map { it.id }.distinct().size == media.size)
    }
}

data class ChatAnswer(
    val answer: String,
    val evidence: List<String>,
    val mediaNotes: List<String>,
    val followUps: List<String>,
    val model: String = "",
    val durationMs: Long = 0,
)

sealed interface ChatOutcome {
    data class Answered(val answer: ChatAnswer) : ChatOutcome
    data object NoModel : ChatOutcome
    data object Declined : ChatOutcome
    data object Unreliable : ChatOutcome
    data class MediaUnsupported(val kind: MediaKind) : ChatOutcome
    data class Failed(val code: String) : ChatOutcome
}

object ChatContract {
    const val VERSION = 1
    private const val MAX_ANSWER = 1200
    private const val HISTORY_TURNS = 3

    val jsonSchema: String by lazy {
        fun string(max: Int) = JSONObject().put("type", "string").put("maxLength", max)
        fun list(max: Int, item: Int) = JSONObject().put("type", "array").put("maxItems", max).put("items", string(item))
        val props = JSONObject()
            .put("chat_schema", JSONObject().put("type", "integer").put("enum", JSONArray(listOf(1))))
            .put("answer", string(MAX_ANSWER))
            .put("evidence_ids", list(8, 24))
            .put("media_notes", list(4, 200))
            .put("follow_up", list(3, 120))
        JSONObject().put("type", "object").put("properties", props).put("additionalProperties", false)
            .put("required", JSONArray(props.keys().asSequence().toList())).toString()
    }

    fun prompt(req: ChatRequest, limits: PromptLimits, compact: Boolean): Prompt {
        val evidence = req.evidence.take(limits.items)
        val identity = if (compact) AiIdentity.compact(req.locale) else AiIdentity.lines(req.locale).joinToString("\n")
        val system = identity + "\n" + """
            Task: answer the user's question about the WAYD evidence below, in at most $MAX_ANSWER characters. Put every cited id in evidence_ids.
            Attachments (u:N) are media the user added: describe only what is visible or audible and relevant, in media_notes. They are not WAYD records and cannot prove what an app sent.
            If a voice clip holds the question, answer that question. If the question is not about app privacy or this evidence, say briefly that you can only help with WAYD evidence.
            follow_up: up to 3 short questions the user could ask next. Reply with one JSON object only, chat_schema=1:
            {"chat_schema":1,"answer":"","evidence_ids":[],"media_notes":[],"follow_up":[]}
        """.trimIndent()
        val user = buildString {
            appendLine("Subject: ${PromptBuilder.neutralise(req.subject, 120)}")
            evidence.forEach { appendLine("<data id=\"${it.id}\">${PromptBuilder.neutralise(Masker.mask(it.text), limits.itemChars)}</data>") }
            if (req.ruleNotes.isNotEmpty()) {
                appendLine("Rule findings (decided by rules, context only):")
                req.ruleNotes.take(if (compact) 6 else 12).forEach { appendLine("- ${PromptBuilder.neutralise(it, 160)}") }
            }
            val history = req.history.takeLast(if (compact) 2 else HISTORY_TURNS)
            if (history.isNotEmpty()) {
                appendLine("<history>")
                history.forEach {
                    appendLine("user: ${PromptBuilder.neutralise(it.question, 240)}")
                    appendLine("assistant: ${PromptBuilder.neutralise(it.answer, if (compact) 240 else 480)}")
                }
                appendLine("</history>")
            }
            if (req.media.isNotEmpty()) appendLine("Attachments after this text: " + req.media.joinToString("; ") { "${it.id} ${it.kind.name.lowercase()} \"${PromptBuilder.neutralise(it.label, 60)}\"" })
            appendLine("User question: " + req.question.takeIf { it.isNotBlank() }?.let { PromptBuilder.neutralise(it, 500) }.orEmpty().ifEmpty { "(spoken in the audio attachment)" })
        }
        return Prompt(system, user, evidence.map { it.id }.toSet() + req.media.map { it.id })
    }

    /** Fails closed on uncited ids and a missing answer; strips markup-like characters instead of rejecting prose. */
    fun parse(raw: String, allowed: Set<String>, hasMedia: Boolean): ChatAnswer? = runCatching {
        require(raw.length <= 64_000)
        val o = JSONObject(SchemaValidator.extractJson(raw) ?: error("json"))
        require(o.getInt("chat_schema") == 1)
        fun clean(s: String, max: Int) = s.trim().filter { it == '\n' || !it.isISOControl() }.replace('<', '‹').replace('>', '›').take(max)
        fun strings(name: String, max: Int, len: Int): List<String> {
            val a = o.optJSONArray(name) ?: JSONArray()
            return (0 until minOf(a.length(), max)).map { clean(a.getString(it), len) }.filter { it.isNotBlank() }
        }
        val answer = clean(o.getString("answer"), MAX_ANSWER).also { require(it.isNotBlank()) }
        val ids = o.getJSONArray("evidence_ids").let { a -> (0 until a.length()).map { a.getString(it) } }
        require(ids.size <= 8 && ids.all { it in allowed })
        ChatAnswer(answer, ids.distinct(), if (hasMedia) strings("media_notes", 4, 200) else emptyList(), strings("follow_up", 3, 120))
    }.getOrNull()
}

/** Same gates as app analysis: model readiness, the per-send external preview, depth budget and one repair unless effort is LOW. */
class ChatAnalyzer @Inject constructor(private val engine: AiEngine, private val depths: AiDepthSource) {
    val phase get() = engine.phase

    suspend fun limits(): Pair<PromptLimits, MediaLimits> = engine.modelId().let { m -> depths.depthFor(m).size(m).let { PromptLimits.of(it) to MediaLimits.of(it) } }

    suspend fun mediaSupport(): Set<MediaKind> = runCatching { engine.mediaSupport() }.getOrDefault(emptySet())

    suspend fun ask(req: ChatRequest, approve: suspend (ExternalSend) -> Boolean): ChatOutcome {
        try {
            if (!engine.isReady()) return ChatOutcome.NoModel
            val model = engine.modelId()
            val depth = depths.depthFor(model)
            val size = depth.size(model)
            val mediaLimits = MediaLimits.of(size)
            val media = req.media.filter { it.kind == MediaKind.IMAGE }.take(mediaLimits.images) + req.media.filter { it.kind == MediaKind.AUDIO }.take(mediaLimits.audioClips)
            val fitted = req.copy(media = media)
            val supported = engine.mediaSupport()
            media.firstOrNull { it.kind !in supported }?.let { return ChatOutcome.MediaUnsupported(it.kind) }
            val prompt = ChatContract.prompt(fitted, PromptLimits.of(size), depth.compact(model))
            val destination = engine.externalDestination()
            destination?.let {
                val send = ExternalSend(it, model.removePrefix("ext:"), prompt.system, prompt.user, prompt.allowedEvidence.size, false, media.map { m -> "${m.id} ${m.label}" })
                if (!approve(send)) return ChatOutcome.Declined
            }
            if (engine.modelId() != model || engine.externalDestination() != destination) return ChatOutcome.Declined
            val started = System.currentTimeMillis()
            var reply = engine.generate(prompt.system, prompt.user, ChatContract.jsonSchema, media)
            var answer = ChatContract.parse(reply, prompt.allowedEvidence, media.isNotEmpty())
            if (answer == null && depth.repair(model)) {
                if (engine.modelId() != model || engine.externalDestination() != destination) return ChatOutcome.Declined
                reply = engine.generate(prompt.system, prompt.user + "\nThe answer failed validation. Return one valid JSON object citing only supplied ids.", ChatContract.jsonSchema, media)
                answer = ChatContract.parse(reply, prompt.allowedEvidence, media.isNotEmpty())
            }
            return answer?.let { ChatOutcome.Answered(it.copy(model = model, durationMs = System.currentTimeMillis() - started)) } ?: ChatOutcome.Unreliable
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiMediaUnsupported) {
            return ChatOutcome.MediaUnsupported(e.kind)
        } catch (e: ExternalAiException) {
            return ChatOutcome.Failed(e.code)
        } catch (e: Exception) {
            return ChatOutcome.Failed(e.message?.take(80) ?: e.javaClass.simpleName)
        }
    }
}
