package com.nathanhanapps.wayd.ai

/** A local language model. One inference at a time; implementations must be cancellable. */
interface AiEngine {
    /** True when a model file is installed and the engine can run. */
    suspend fun isReady(): Boolean

    /** Identifier stored with each analysis, e.g. `gemma-4-E2B-it`. */
    suspend fun modelId(): String

    /** Runs one chat turn and returns the raw text. Throws on failure. */
    suspend fun generate(system: String, user: String): String
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
}

/**
 * Runs an analysis: prompt, model, validation, one repair retry, storage. Nothing the model says reaches the UI without passing
 * [SchemaValidator]; severity stays with the rule engine.
 */
class AiAnalyzer(
    private val engine: AiEngine,
    private val store: AnalysisStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun analyse(req: AiRequest, useCache: Boolean = true): AiOutcome {
        if (!engine.isReady()) return AiOutcome.NoModel
        val prompt = PromptBuilder.build(req)
        val key = refsKey(req)
        val model = runCatching { engine.modelId() }.getOrDefault("unknown")

        if (useCache) {
            store.cached(key, PromptBuilder.VERSION)?.let { json ->
                (SchemaValidator.validate(json, prompt.allowedEvidence) as? Validation.Valid)?.let {
                    return AiOutcome.Success(it.result, it.stripped, model, 0, fromCache = true)
                }
            }
        }

        val started = clock()
        var reply = try {
            engine.generate(prompt.system, prompt.user)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return AiOutcome.Failed(t.message ?: t.javaClass.simpleName)
        }
        var verdict = SchemaValidator.validate(reply, prompt.allowedEvidence)
        if (verdict is Validation.Invalid) {
            val repair = PromptBuilder.repair(prompt, reply, verdict.errors)
            reply = try {
                engine.generate(repair.system, repair.user)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                return AiOutcome.Failed(t.message ?: t.javaClass.simpleName)
            }
            verdict = SchemaValidator.validate(reply, prompt.allowedEvidence)
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
