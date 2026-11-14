package com.nathanhanapps.wayd.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How much evidence one request may carry. AUTO follows the model: E2B small, E4B medium, external large. */
enum class ContextSize { AUTO, SMALL, MEDIUM, LARGE }

/**
 * How hard one request may try. LOW: compact prompt, no repair retry. STANDARD: compact prompt on small contexts, one repair.
 * HIGH: full prompt and one repair; external servers that support it may also reason (`reasoning_effort`).
 */
enum class AiEffort { LOW, STANDARD, HIGH }

data class AiDepth(val context: ContextSize = ContextSize.AUTO, val effort: AiEffort = AiEffort.STANDARD) {
    private fun external(modelId: String) = modelId.startsWith("ext:")

    /**
     * On-device Gemma is scaled separately from external servers (D-063): it never gets the Large budget (an 8K window shared with
     * the answer), and effort does not apply to it. AUTO: E2B and unknown local models Small, E4B Medium, external Large.
     */
    fun size(modelId: String): ContextSize = when {
        external(modelId) -> if (context == ContextSize.AUTO) ContextSize.LARGE else context
        context == ContextSize.SMALL -> ContextSize.SMALL
        context == ContextSize.MEDIUM || context == ContextSize.LARGE -> ContextSize.MEDIUM
        modelId.contains("e4b", ignoreCase = true) -> ContextSize.MEDIUM
        else -> ContextSize.SMALL
    }

    /** Local models always get one repair retry; only an external Low effort skips it. */
    fun repair(modelId: String): Boolean = !external(modelId) || effort != AiEffort.LOW

    /** Local models always use the compact prompts written for them. External: compact for Low, or Standard on a small context. */
    fun compact(modelId: String): Boolean = !external(modelId) || effort == AiEffort.LOW || (effort == AiEffort.STANDARD && size(modelId) != ContextSize.LARGE)

    /** OpenAI-compatible `reasoning_effort`. "none" keeps reasoning models from spending the whole window thinking (the old default). */
    val reasoning: String get() = if (effort == AiEffort.HIGH) "medium" else "none"
}

/** Per-provider depth, read by both the app-level analysis and per-item labels. */
fun interface AiDepthSource {
    fun depthFor(modelId: String): AiDepth
}

/** Main analysis limits per size: items in the prompt and characters per item. */
data class PromptLimits(val items: Int, val itemChars: Int) {
    companion object {
        val DEFAULT = PromptLimits(24, 900)
        fun of(size: ContextSize): PromptLimits = when (size) {
            ContextSize.SMALL -> PromptLimits(10, 320)
            ContextSize.MEDIUM -> PromptLimits(16, 520)
            ContextSize.LARGE, ContextSize.AUTO -> DEFAULT
        }
    }
}

/** Plain preferences: these are tuning choices, not secrets. On-device and external are separate because their models differ. */
@Singleton
class AiDepthSettings @Inject constructor(@ApplicationContext context: Context) : AiDepthSource {
    private val prefs = context.getSharedPreferences("ai_depth", Context.MODE_PRIVATE)
    private val _local = MutableStateFlow(read("local"))
    private val _external = MutableStateFlow(read("external"))
    val local: StateFlow<AiDepth> = _local.asStateFlow()
    val external: StateFlow<AiDepth> = _external.asStateFlow()

    override fun depthFor(modelId: String): AiDepth = if (modelId.startsWith("ext:")) _external.value else _local.value

    fun set(external: Boolean, depth: AiDepth) {
        val key = if (external) "external" else "local"
        prefs.edit().putString("${key}_context", depth.context.name).putString("${key}_effort", depth.effort.name).apply()
        (if (external) _external else _local).value = depth
    }

    private fun read(key: String) = AiDepth(
        runCatching { ContextSize.valueOf(prefs.getString("${key}_context", null)!!) }.getOrDefault(ContextSize.AUTO),
        runCatching { AiEffort.valueOf(prefs.getString("${key}_effort", null)!!) }.getOrDefault(AiEffort.STANDARD),
    )
}
