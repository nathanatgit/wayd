package com.nathanhanapps.wayd.ai

/** What the engine is busy with; the UI uses it to explain long waits (the first run compiles the model for this phone). */
enum class EnginePhase { IDLE, PREPARING_FIRST_RUN, LOADING, GENERATING }

/** A local language model. One inference at a time; implementations must be cancellable. */
interface AiEngine {
    val phase: kotlinx.coroutines.flow.StateFlow<EnginePhase> get() = IdlePhase.flow

    /** True when a model file is installed and the engine can run. */
    suspend fun isReady(): Boolean

    /** Identifier stored with each analysis, e.g. `gemma-4-E2B-it`. */
    suspend fun modelId(): String

    /** Non-null when answering sends the prompt off this phone: the host it goes to. The analyzer asks the user first. */
    suspend fun externalDestination(): String? = null

    /** Runs one chat turn and returns the raw text. [jsonSchema], when given, constrains the output to that JSON Schema. Throws on failure. */
    suspend fun generate(system: String, user: String, jsonSchema: String? = null): String
}

private object IdlePhase {
    val flow: kotlinx.coroutines.flow.StateFlow<EnginePhase> = kotlinx.coroutines.flow.MutableStateFlow(EnginePhase.IDLE)
}

/** Persists finished analyses. The database implementation lives in the app. */
interface AnalysisStore {
    suspend fun cached(refsKey: String, promptVersion: Int): String?

    suspend fun save(refsKey: String, model: String, promptVersion: Int, resultJson: String, validated: Boolean, durationMs: Long)
}

sealed interface AiOutcome {
    data class Success(val result: AiResult, val stripped: Int, val model: String, val durationMs: Long, val fromCache: Boolean) : AiOutcome

    /** The model could not produce an answer that passed validation twice; callers show the raw rule findings instead. */
    data class Unreliable(val reasons: List<String>) : AiOutcome

    data object NoModel : AiOutcome

    data class Failed(val message: String) : AiOutcome

    /** The user declined to send the data to the external provider; nothing left the phone. */
    data object Declined : AiOutcome
}

/**
 * Exactly what would leave the phone for an external provider: the full masked prompt, shown to the user before anything is sent.
 * One analysis makes one request, plus a second one only if the first answer fails validation.
 */
data class ExternalSend(val destination: String, val model: String, val system: String, val user: String, val items: Int, val hasDecrypted: Boolean)

/**
 * Runs an analysis: prompt, model, validation, one repair retry, storage. Nothing the model says reaches the UI without passing
 * [SchemaValidator]; severity stays with the rule engine.
 */
class AiAnalyzer(
    private val engine: AiEngine,
    private val store: AnalysisStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Debug builds only: receives validator complaints and the start of an invalid reply so prompts can be tuned. Never set in release. */
    private val debugLog: ((String) -> Unit)? = null,
) {
    /**
     * [approve] is asked once, right before data would leave the phone for an external provider (never for the on-device model,
     * and never when a stored answer is reused). Returning false ends the analysis with [AiOutcome.Declined].
     */
    suspend fun analyse(req: AiRequest, useCache: Boolean = true, approve: suspend (ExternalSend) -> Boolean = { false }): AiOutcome {
        if (!engine.isReady()) return AiOutcome.NoModel
        val prompt = PromptBuilder.build(req)
        val model = runCatching { engine.modelId() }.getOrDefault("unknown")
        // Answers are kept per model: a stored Gemma answer is not presented as the external model's.
        val key = refsKey(req) + "|m=" + model

        if (useCache) {
            store.cached(key, PromptBuilder.VERSION)?.let { json ->
                (SchemaValidator.validate(json, prompt.allowedEvidence) as? Validation.Valid)?.let {
                    return AiOutcome.Success(it.result, it.stripped, model, 0, fromCache = true)
                }
            }
        }

        engine.externalDestination()?.let { destination ->
            val send = ExternalSend(destination, model.removePrefix("ext:"), prompt.system, prompt.user, prompt.allowedEvidence.size, prompt.allowedEvidence.any { it.startsWith("m:") })
            if (!approve(send)) return AiOutcome.Declined
        }

        val started = clock()
        var reply = try {
            engine.generate(prompt.system, prompt.user, PromptBuilder.JSON_SCHEMA)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return AiOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }
        var verdict = SchemaValidator.validate(reply, prompt.allowedEvidence)
        if (verdict is Validation.Invalid) {
            debugLog?.invoke("attempt 1 invalid: ${verdict.errors} | reply(${reply.length}): ${reply.take(2500)}")
            val repair = PromptBuilder.repair(prompt, reply, verdict.errors)
            reply = try {
                engine.generate(repair.system, repair.user, PromptBuilder.JSON_SCHEMA)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                return AiOutcome.Failed(t.message ?: t.javaClass.simpleName)
            }
            verdict = SchemaValidator.validate(reply, prompt.allowedEvidence)
            (verdict as? Validation.Invalid)?.let { debugLog?.invoke("attempt 2 invalid: ${it.errors} | reply(${reply.length}): ${reply.take(2500)}") }
        }
        val took = clock() - started
        return when (verdict) {
            is Validation.Valid -> {
                SchemaValidator.extractJson(reply)?.let { store.save(key, model, PromptBuilder.VERSION, it, true, took) }
                AiOutcome.Success(verdict.result, verdict.stripped, model, took, fromCache = false)
            }
            is Validation.Invalid -> {
                store.save(key, model, PromptBuilder.VERSION, reply.take(2000), false, took)
                AiOutcome.Unreliable(verdict.errors)
            }
        }
    }

    /** Identifies "the same question about the same evidence" so unchanged selections reuse the stored answer. */
    private fun refsKey(req: AiRequest): String =
        (req.evidence.map { it.id }.sorted() + "|" + req.locale + "|" + (req.question ?: "")).joinToString(",")
}
