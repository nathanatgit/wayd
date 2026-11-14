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
    private var loadedMedia = false
    @Volatile private var mediaFailed = false

    override suspend fun isReady() = models.activeModel() != null

    override suspend fun modelId() = models.activeModel()?.id ?: "none"

    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val _phase = kotlinx.coroutines.flow.MutableStateFlow(EnginePhase.IDLE)
    override val phase: kotlinx.coroutines.flow.StateFlow<EnginePhase> = _phase

    /** Gemma edge builds carry vision and audio encoders; a model without them fails at load and is reported as unsupported. */
    override suspend fun mediaSupport(): Set<MediaKind> = if (models.activeModel() == null || mediaFailed) emptySet() else setOf(MediaKind.IMAGE, MediaKind.AUDIO)

    override suspend fun generate(system: String, user: String, jsonSchema: String?): String = generate(system, user, jsonSchema, emptyList())

    override suspend fun generate(system: String, user: String, jsonSchema: String?, media: List<AiMedia>): String = withContext(Dispatchers.Default) {
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
                    run(path, system, user, jsonSchema, firstRun, media)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: AiMediaUnsupported) {
                    throw e
                } catch (t: Throwable) {
                    // The GPU backend can fail at the first message, not at load time (for example no usable OpenCL). Retry on the CPU once.
                    if (cpuOnly) throw t
                    cpuOnly = true
                    runCatching { engine?.close() }
                    engine = null
                    loadedPath = null
                    run(path, system, user, jsonSchema, firstRun, media)
                }
                prefs.edit().putBoolean(compiledKey, true).apply()
                text
            } finally {
                _phase.value = EnginePhase.IDLE
            }
        }
    }

    private fun run(path: String, system: String, user: String, jsonSchema: String?, firstRun: Boolean, media: List<AiMedia>): String {
        val eng = ensureEngine(path, media.isNotEmpty())
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
                // Text first, then attachments in order, matching the "Attachments after this text" line in the prompt.
                val contents = Contents.of(listOf<Content>(Content.Text(user)) + media.map {
                    when (it.kind) {
                        MediaKind.IMAGE -> Content.ImageBytes(it.bytes)
                        MediaKind.AUDIO -> Content.AudioBytes(it.bytes)
                    }
                })
                val reply = conv.sendMessage(
                    contents,
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
        loadedMedia = false
        mediaFailed = false
    }

    /**
     * Text-only loads stay as before. The first request with media reloads once with vision/audio encoders (audio on the CPU) and
     * keeps that engine, since it also serves text; switching back and forth would reload the model each time.
     */
    private fun ensureEngine(path: String, needMedia: Boolean): Engine {
        engine?.let { if (loadedPath == path && (loadedMedia || !needMedia)) return it }
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        fun config(main: Backend) = if (needMedia) EngineConfig(path, main, if (main is Backend.GPU) Backend.GPU() else Backend.CPU(), Backend.CPU(), MAX_TOKENS, MAX_IMAGES, cache)
        else EngineConfig(path, main, null, null, MAX_TOKENS, null, cache)
        val created = try {
            if (cpuOnly) error("cpu only")
            Engine(config(Backend.GPU())).also { it.initialize() }
        } catch (gpu: Throwable) {
            // No usable GPU delegate on this device or ROM: run on the CPU.
            try {
                Engine(config(Backend.CPU())).also { it.initialize() }
            } catch (t: Throwable) {
                if (!needMedia) throw t
                mediaFailed = true // this model file has no usable vision/audio section
                throw AiMediaUnsupported(MediaKind.IMAGE)
            }
        }
        engine = created
        loadedPath = path
        loadedMedia = needMedia
        return created
    }

    private companion object {
        /** Practical prompt+answer budget on a phone (docs/06): do not rely on the model's 128K window. */
        const val MAX_TOKENS = 8192

        /** Enough for a complete analysis; stops a looping model from running for minutes. */
        const val MAX_OUTPUT_TOKENS = 1400

        /** Upper bound for one chat turn (MediaLimits.LARGE); smaller depths send fewer. */
        const val MAX_IMAGES = 6
    }
}
