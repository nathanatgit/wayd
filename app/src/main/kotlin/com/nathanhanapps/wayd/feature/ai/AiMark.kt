package com.nathanhanapps.wayd.feature.ai

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.ai.AiProvider
import com.nathanhanapps.wayd.ai.ExternalAiSettings
import com.nathanhanapps.wayd.ai.ExternalAiUrl
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Which model family answers, for the mark shown on AI cards. Marks come from LobeHub icons (MIT); a [tint] of null follows the
 * text colour (monochrome brands). Gemma, the on-device model, uses the Gemini mark.
 */
enum class AiBrand(@DrawableRes val icon: Int?, val tint: Long?) {
    GEMINI(R.drawable.ic_ai_gemini, 0xFF3186FF),
    GROK(R.drawable.ic_ai_grok, null),
    OPENAI(R.drawable.ic_ai_openai, null),
    CLAUDE(R.drawable.ic_ai_claude, 0xFFD97757),
    KIMI(R.drawable.ic_ai_kimi, null),
    QWEN(R.drawable.ic_ai_qwen, 0xFF615CED),
    DEEPSEEK(R.drawable.ic_ai_deepseek, 0xFF4D6BFE),
    OTHER(null, null),
}

/** Model name first (a gateway can serve any family), then the server's host. */
fun aiBrandOf(model: String, host: String = ""): AiBrand {
    val m = model.lowercase()
    val byModel = when {
        "gemini" in m || "gemma" in m -> AiBrand.GEMINI
        "grok" in m -> AiBrand.GROK
        "claude" in m -> AiBrand.CLAUDE
        "deepseek" in m -> AiBrand.DEEPSEEK
        "qwen" in m || "qwq" in m -> AiBrand.QWEN
        "kimi" in m || "moonshot" in m -> AiBrand.KIMI
        "gpt" in m || "chatgpt" in m || Regex("(^|/)o[1-9]").containsMatchIn(m) -> AiBrand.OPENAI
        else -> null
    }
    if (byModel != null) return byModel
    val h = host.lowercase()
    return when {
        h.endsWith("googleapis.com") -> AiBrand.GEMINI
        h.endsWith("x.ai") -> AiBrand.GROK
        h.endsWith("anthropic.com") -> AiBrand.CLAUDE
        h.endsWith("deepseek.com") -> AiBrand.DEEPSEEK
        h.endsWith("aliyuncs.com") -> AiBrand.QWEN
        h.endsWith("moonshot.cn") || h.endsWith("moonshot.ai") -> AiBrand.KIMI
        h.endsWith("openai.com") -> AiBrand.OPENAI
        else -> AiBrand.OTHER
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AiMarkEntryPoint {
    fun externalAi(): ExternalAiSettings
}

/** The mark of a given brand; [OTHER] falls back to the star. */
@Composable
fun AiBrandIcon(brand: AiBrand, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val tint = brand.tint?.let { Color(it) } ?: if (brand == AiBrand.OTHER) MaterialTheme.colorScheme.primary else LocalContentColor.current
    if (brand.icon != null) Icon(painterResource(brand.icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
    else Icon(Icons.Filled.Star, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** The mark of whichever provider answers right now: Gemma on the phone, or the external server's model family. */
@Composable
fun AiMark(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val context = LocalContext.current
    val settings = remember { EntryPointAccessors.fromApplication(context.applicationContext, AiMarkEntryPoint::class.java).externalAi() }
    val ext by settings.state.collectAsStateWithLifecycle()
    val brand = if (ext.provider == AiProvider.EXTERNAL && ext.usable) aiBrandOf(ext.model, ExternalAiUrl.host(ext.baseUrl)) else AiBrand.GEMINI
    AiBrandIcon(brand, modifier, size)
}
