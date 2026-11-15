package com.nathanhanapps.wayd.capture

/**
 * Text forms of one rewrite rule for the rule editor (D-073).
 *
 * - **WAYD text**: one `key: value` per line (`host`, `subdomains`, `method`, `match`, `pattern`, `action`, `value`); `#`
 *   starts a comment. Lossless: [fromText] of [toText] gives the same rule.
 * - **Plugin line**: the `[Rewrite]` line format [RewritePluginParser] imports. Lossy: it has no method, and non-regex matches
 *   become a URL regex. [fromPlugin] reads one such line.
 */
object RewriteRuleText {
    data class Parsed(val rule: RewriteRule?, val errorLine: Int?)

    private val MATCHES = mapOf("prefix" to "PREFIX", "exact" to "EXACT", "contains" to "CONTAINS", "regex" to "REGEX")
    private val ACTIONS = mapOf("reject" to "REJECT", "header-remove" to "HEADER_REMOVE", "header-set" to "HEADER_SET", "query-remove" to "QUERY_REMOVE", "redirect" to "REDIRECT")

    fun toText(r: RewriteRule): String = buildString {
        appendLine("host: ${r.host}")
        appendLine("subdomains: ${if (r.subdomains) "yes" else "no"}")
        appendLine("method: ${r.method.ifEmpty { "*" }}")
        appendLine("match: ${MATCHES.entries.first { it.value == r.match }.key}")
        appendLine("pattern: ${r.pattern}")
        appendLine("action: ${ACTIONS.entries.firstOrNull { it.value == r.action }?.key ?: r.action.lowercase()}")
        append("value: ${r.arg}")
    }

    /** Reads WAYD text into a rule (id 0, enabled). [Parsed.errorLine] is the 1-based line that could not be read, or 0 when every line was read but the rule is not valid. */
    fun fromText(text: String): Parsed {
        val fields = HashMap<String, String>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val key = line.substringBefore(':', "").trim().lowercase()
            if (key !in KEYS || ':' !in line) return Parsed(null, i + 1)
            fields[key] = line.substringAfter(':').trim()
        }
        val sub = when (fields["subdomains"]?.lowercase()) { null, "no", "false" -> false; "yes", "true" -> true; else -> return Parsed(null, lineOf(text, "subdomains")) }
        val match = MATCHES[fields["match"]?.lowercase() ?: "prefix"] ?: return Parsed(null, lineOf(text, "match"))
        val action = ACTIONS[fields["action"]?.lowercase() ?: ""] ?: return Parsed(null, lineOf(text, "action"))
        val rule = RewriteRule(0, fields["host"].orEmpty().lowercase(), sub, fields["method"].orEmpty().let { if (it == "*") "" else it.uppercase() },
            match, fields["pattern"].orEmpty(), action, fields["value"].orEmpty(), true, 0)
        return if (rule.valid()) Parsed(rule, null) else Parsed(null, 0)
    }

    private val KEYS = setOf("host", "subdomains", "method", "match", "pattern", "action", "value")
    private fun lineOf(text: String, key: String) = text.lines().indexOfFirst { it.trim().lowercase().startsWith("$key:") } + 1

    private fun escape(s: String) = s.replace(Regex("""[\\.^$*+?()\[\]{}|]""")) { "\\" + it.value }

    /** The rule as a plugin `[Rewrite]` line, or null when the action has no plugin equivalent. */
    fun toPlugin(r: RewriteRule): String? {
        val url = if (r.match == "REGEX") r.pattern else {
            val host = (if (r.subdomains) SUBDOMAIN_PREFIX else "") + escape(r.host)
            when (r.match) {
                "PREFIX" -> "^https?://$host" + escape(r.pattern)
                "EXACT" -> "^https?://$host" + escape(r.pattern) + "$"
                else -> "^https?://$host[^#]*" + escape(r.pattern)
            }
        }
        val type = when (r.action) {
            "REJECT" -> when (r.arg) { "204" -> "reject"; "200-empty" -> "reject-200"; "200-json" -> "reject-dict"; "200-array" -> "reject-array"; else -> return null }
            "HEADER_REMOVE" -> "header-del ${r.arg}"
            "HEADER_SET" -> r.arg.split(": ", limit = 2).let { "header-replace ${it[0]} ${it.getOrElse(1) { "" }}" }
            "QUERY_REMOVE" -> "query-del ${r.arg}"
            "REDIRECT" -> r.arg.split(" ", limit = 2).let { "${it[0]} ${it.getOrElse(1) { "" }}" }
            else -> return null
        }
        return "$url $type"
    }

    /** One plugin line read as a REGEX rule (id 0, enabled), or null when the importer would skip it. */
    fun fromPlugin(line: String): RewriteRule? = RewritePluginParser.parse(sequenceOf("[Rewrite]", line.trim())).rules.singleOrNull()
        ?.let { RewriteRule(0, it.host, it.subdomains, it.method, it.match, it.pattern, it.action, it.arg, true, 0) }

    /** Optional subdomain label prefix in generated patterns; [RewritePluginParser] reads it back as "host and subdomains". */
    const val SUBDOMAIN_PREFIX = """(?:[^/]+\.)?"""
}
