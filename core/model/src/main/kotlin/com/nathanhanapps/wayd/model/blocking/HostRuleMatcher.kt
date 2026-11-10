package com.nathanhanapps.wayd.model.blocking

/** Pure host-rule matching. Does not perform DNS lookups or network I/O. */
object HostRuleMatcher {
    enum class Action { BLOCK, ALLOW, LOG_ONLY }

    data class Rule(
        val id: Long,
        val pattern: String,
        val action: Action,
        val packageName: String? = null,
        val enabled: Boolean = true,
    )

    data class Decision(val action: Action, val ruleId: Long)

    /**
     * Accepted forms: example.com (exact), *.example.com (apex and subdomains),
     * and *example* (literal substring). Other '*' placements and regex are
     * deliberately unsupported in this first slice; invalid rules fail closed
     * at configuration time, rather than unexpectedly matching everything.
     *
     * This returns a policy decision, not an enforcement result.
     */
    fun decide(packageName: String, host: String, rules: Collection<Rule>): Decision? {
        val normalizedHost = normalizeHost(host) ?: return null
        return rules.asSequence()
            .filter { it.enabled && (it.packageName == null || it.packageName == packageName) }
            .mapNotNull { rule ->
                val score = matchScore(rule.pattern, normalizedHost) ?: return@mapNotNull null
                Triple(rule, score, if (rule.packageName != null) 1 else 0)
            }
            .sortedWith(
                compareByDescending<Triple<Rule, Int, Int>> { it.second }
                    .thenByDescending { it.third }
                    // Same specificity: explicit ALLOW wins, then BLOCK, then LOG_ONLY.
                    .thenBy { when (it.first.action) { Action.ALLOW -> 0; Action.BLOCK -> 1; Action.LOG_ONLY -> 2 } }
                    .thenBy { it.first.id },
            )
            .firstOrNull()
            ?.let { Decision(it.first.action, it.first.id) }
    }

    fun isValidPattern(pattern: String): Boolean = classify(pattern) != null

    private enum class Kind { EXACT, DOMAIN, CONTAINS }

    private fun classify(pattern: String): Pair<Kind, String>? {
        val value = pattern.trim().lowercase(java.util.Locale.ROOT)
        val kind = when {
            value.startsWith("*.") && value.count { it == '*' } == 1 -> Kind.DOMAIN
            value.startsWith("*") && value.endsWith("*") && value.length > 2 && value.count { it == '*' } == 2 -> Kind.CONTAINS
            '*' !in value && !value.startsWith("re:") -> Kind.EXACT
            else -> return null
        }
        val term = when (kind) {
            Kind.DOMAIN -> value.substring(2)
            Kind.CONTAINS -> value.substring(1, value.length - 1)
            Kind.EXACT -> value
        }
        if (term.isEmpty() || term.length > 253 || term.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) return null
        if (kind != Kind.CONTAINS && normalizeHost(term) != term) return null
        return kind to term
    }

    private fun matchScore(pattern: String, host: String): Int? {
        val (kind, term) = classify(pattern) ?: return null
        return when (kind) {
            Kind.EXACT -> if (host == term) 3_000 + term.length else null
            Kind.DOMAIN -> if (host == term || host.endsWith(".$term")) 2_000 + term.length else null
            Kind.CONTAINS -> if (host.contains(term)) 1_000 + term.length else null
        }
    }

    private fun normalizeHost(host: String): String? {
        val value = host.trim().trimEnd('.').lowercase(java.util.Locale.ROOT)
        if (value.isEmpty() || value.length > 253 || value.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it == '*' }) return null
        // Conservative ASCII DNS host validation; IP/CIDR and IDN conversion are separate slices.
        if (!value.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return null
        if (value.split('.').any { it.isEmpty() || it.length > 63 || it.first() == '-' || it.last() == '-' }) return null
        return value
    }
}
