package com.nathanhanapps.wayd.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs Gemma through LiteRT-LM. The GPU backend is tried first and the CPU is the fallback. One inference at a time; the
 * loaded engine is kept between analyses and released by [release] (for example when memory is tight or the model changes).
 */
@Singleton
class LiteRtEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
) : AiEngine {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private var cpuOnly = false
    private var noConstraint = false

    override suspend fun isReady() = models.activeModel() != null

    override suspend fun modelId() = models.activeModel()?.id ?: "none"

    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val _phase = kotlinx.coroutines.flow.MutableStateFlow(EnginePhase.IDLE)
    override val phase: kotlinx.coroutines.flow.StateFlow<EnginePhase> = _phase

    override suspend fun generate(system: String, user: String, jsonSchema: String?): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val spec = models.activeModel() ?: error("no model installed")
            val modelId = spec.id
            // Checked (and refused on an unresolved mismatch) before any backend is tried, so a bad file is not retried on the CPU.
            val path = models.loadPath(spec)
            val compiledKey = "compiled_$modelId"
            val firstRun = !prefs.getBoolean(compiledKey, false)
            _phase.value = if (firstRun) EnginePhase.PREPARING_FIRST_RUN else if (engine == null) EnginePhase.LOADING else EnginePhase.GENERATING
            try {
                val text = try {
                    run(path, system, user, jsonSchema, firstRun)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // The GPU backend can fail at the first message, not at load time (for example no usable OpenCL). Retry on the CPU once.
                    if (cpuOnly) throw t
                    cpuOnly = true
                    runCatching { engine?.close() }
                    engine = null
                    loadedPath = null
                    run(path, system, user, jsonSchema, firstRun)
                }
                prefs.edit().putBoolean(compiledKey, true).apply()
                text
            } finally {
                _phase.value = EnginePhase.IDLE
            }
        }
    }

    private fun run(path: String, system: String, user: String, jsonSchema: String?, firstRun: Boolean): String {
        val eng = ensureEngine(path)
        if (!firstRun) _phase.value = EnginePhase.GENERATING
        fun conversation(constrained: Boolean) = eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                // Low temperature: we want a faithful, structured answer, not a creative one.
                samplerConfig = SamplerConfig(40, 0.9, 0.2, 0),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                enableResponseFormat = constrained,
            ),
        )

        fun ask(constrained: Boolean): String {
            val conv = conversation(constrained)
            try {
                val reply = conv.sendMessage(
                    user,
                    emptyMap<String, Any>(),
                    RepetitionPenaltyConfig(1.15f, null, null, 256),
                    null, null, null, null,
                    if (constrained) ResponseFormat.json(jsonSchema!!) else null,
                )
                return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            } finally {
                runCatching { conv.close() }
            }
        }
        // Constrained decoding makes the JSON valid by construction; if this device or model rejects it, fall back to free text.
        return if (jsonSchema != null && !noConstraint) {
            try {
                ask(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t.message?.contains("OpenCL", ignoreCase = true) == true) throw t
                noConstraint = true
                ask(false)
            }
        } else {
            ask(false)
        }
    }

    /** Frees the model's memory. The next analysis loads it again. */
    suspend fun release() = lock.withLock {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    private fun ensureEngine(path: String): Engine {
        engine?.let { if (loadedPath == path) return it }
        runCatching { engine?.close() }
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        val created = try {
            if (cpuOnly) error("cpu only")
            Engine(EngineConfig(path, Backend.GPU(), null, null, MAX_TOKENS, null, cache)).also { it.initialize() }
        } catch (gpu: Throwable) {
            // No usable GPU delegate on this device or ROM: run on the CPU.
            Engine(EngineConfig(path, Backend.CPU(), null, null, MAX_TOKENS, null, cache)).also { it.initialize() }
        }
        engine = created
        loadedPath = path
        return created
    }

    private companion object {
        /** Practical prompt+answer budget on a phone (docs/06): do not rely on the model's 128K window. */
        const val MAX_TOKENS = 8192

        /** Enough for a complete analysis; stops a looping model from running for minutes. */
        const val MAX_OUTPUT_TOKENS = 1400
    }
}
