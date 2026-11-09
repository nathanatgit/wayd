package com.nathanhanapps.wayd.ai

/**
 * Replaces raw identifier values with their type tokens before text reaches the model (docs/06): the model needs to know that
 * something "is an Android ID", not what the value is. Best effort and deliberately aggressive.
 */
object Masker {
    private class Rule(val regex: Regex, val replacement: String)

    private val rules = listOf(
        Rule(Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"""), "[JWT]"),
        // key/value style secrets: Authorization: Bearer xxx, "token":"xxx", access_token=xxx
        Rule(
            Regex("""(?i)\b((?:bearer|authorization|access_?token|refresh_?token|token|secret|password|passwd|pwd|api_?key|sign|session(?:id)?)["']?\s*[:=]\s*["']?(?:bearer\s+)?)[A-Za-z0-9._\-+/=%]{6,}"""),
            "$1[SECRET]",
        ),
        Rule(Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""), "[EMAIL]"),
        Rule(Regex("""(?i)\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\b"""), "[MAC]"),
        Rule(Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"""), "[UUID]"),
        Rule(Regex("""(?i)((?:android_?id|ssaid)["']?\s*[:=]\s*["']?)[0-9a-f]{16}\b"""), "$1[ANDROID_ID]"),
        Rule(Regex("""(?<![0-9])1[3-9][0-9]{9}(?![0-9])"""), "[PHONE]"),
        Rule(Regex("""(?<![0-9])[0-9]{15}(?![0-9])"""), "[IMEI]"),
        Rule(Regex("""(?<![0-9.])-?[0-9]{1,3}\.[0-9]{4,}\s*[,;]\s*-?[0-9]{1,3}\.[0-9]{4,}"""), "[LOCATION]"),
        Rule(Regex("""(?i)(?<![0-9a-z])[0-9a-f]{32,}(?![0-9a-z])"""), "[HEX_TOKEN]"),
        Rule(Regex("""(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{40,}={0,2}"""), "[TOKEN]"),
    )

    fun mask(text: String): String {
        var out = text
        for (r in rules) out = r.regex.replace(out, r.replacement)
        return out
    }
}
