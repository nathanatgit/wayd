package com.nathanhanapps.wayd.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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

    override suspend fun isReady() = models.activeModel() != null

    override suspend fun modelId() = models.activeModel()?.first?.id ?: "none"

    override suspend fun generate(system: String, user: String): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val eng = ensureEngine()
            val conversation = eng.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    // Low temperature: we want a faithful, structured answer, not a creative one.
                    samplerConfig = SamplerConfig(40, 0.9, 0.2, 0),
                ),
            )
            try {
                val reply = conversation.sendMessage(user)
                reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            } finally {
                runCatching { conversation.close() }
            }
        }
    }

    /** Frees the model's memory. The next analysis loads it again. */
    suspend fun release() = lock.withLock {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    private fun ensureEngine(): Engine {
        val (_, path) = models.activeModel() ?: error("no model installed")
        engine?.let { if (loadedPath == path) return it }
        runCatching { engine?.close() }
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        val created = try {
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
    }
}
