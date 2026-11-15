package com.nathanhanapps.wayd.capture

/** One rule read from a rule plugin, still without an app: the user chooses the app at import. */
data class RewriteDraft(val host: String, val subdomains: Boolean, val method: String, val match: String, val pattern: String, val action: String, val arg: String)

/**
 * Reads the rewrite part of a rule-plugin text (D-070). Lines are `<url-regex> <type> [arguments]` inside a `[Rewrite]`
 * (or `[URL Rewrite]` / `[rewrite_local]`) section; `[MITM] hostname = …` lists the hosts that need decryption.
 *
 * Imported: `reject`, `reject-200`, `reject-img`, `reject-dict`, `reject-array`, `reject-drop`; `302` / `307` redirects;
 * `header-del`, `header-add`, `header-replace` on the request; and WAYD's own `query-del <name>`.
 * Skipped and counted: scripts, response header and body edits, mock bodies, patterns the engine's regex library cannot
 * take (look-around, back-references), and lines whose pattern does not name a host WAYD can scope the rule to.
 *
 * WAYD rules are per app and per host. The host comes from the literal host in the pattern (`^https?://api\.example\.com/…`);
 * when the host part is itself a regex, the literal suffix is used with subdomains (`.example.com`). The whole pattern is kept
 * as a REGEX rule, so matching stays what the plugin author wrote.
 */
object RewritePluginParser {
    data class Result(val name: String?, val rules: List<RewriteDraft>, val mitmHosts: List<String>, val skipped: Int)

    private val REWRITE_SECTIONS = setOf("[rewrite]", "[url rewrite]", "[rewrite_local]")
    private val TOKEN = Regex("[A-Za-z0-9_.-]{1,128}")

    fun parse(text: Sequence<String>): Result {
        var section = ""
        var name: String? = null
        var skipped = 0
        val rules = LinkedHashSet<RewriteDraft>()
        val mitm = LinkedHashSet<String>()
        for (raw in text) {
            val line = raw.trim()
            if (line.startsWith("#!name=")) { name = line.removePrefix("#!name=").trim().take(60); continue }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) { section = line.lowercase(); continue }
            when (section) {
                "[mitm]" -> if (line.substringBefore('=').trim().lowercase() == "hostname") {
                    line.substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%") && !it.startsWith("-") }
                        .mapNotNull { BlockTargets.host(it.removePrefix("*.")) }.forEach { mitm += it }
                }
                in REWRITE_SECTIONS -> draft(line)?.let { rules += it } ?: skipped++
                "[script]" -> skipped++
                else -> Unit // [Rule] lines are block-list material: import the same file under Block lists.
            }
        }
        return Result(name, rules.toList(), mitm.toList(), skipped)
    }

    private fun draft(line: String): RewriteDraft? {
        val t = line.split(Regex("\\s+")).toMutableList()
        if (t.size >= 3 && t[1] == "url") t.removeAt(1) // `<regex> url reject` form
        if (t.size < 2) return null
        val pattern = t[0]
        // `<regex> <replacement> 302|307` and `<regex> - reject` forms put the type last.
        val (type, args) = when {
            t.size == 3 && t[2] in setOf("302", "307") -> t[2] to listOf(t[1])
            t.size == 3 && t[1] == "-" -> t[2] to emptyList()
            else -> t[1] to t.drop(2)
        }
        val (action, arg) = when (type.lowercase()) {
            "reject", "reject-drop" -> "REJECT" to "204"
            "reject-200", "reject-img" -> "REJECT" to "200-empty"
            "reject-dict" -> "REJECT" to "200-json"
            "reject-array" -> "REJECT" to "200-array"
            // A target with capture references ($1) would need substitution, which the engine does not do: skip rather than redirect wrongly.
            "302", "307" -> "REDIRECT" to (type + " " + (args.firstOrNull()?.takeIf { '$' !in it } ?: return null))
            "header-del" -> "HEADER_REMOVE" to (args.singleOrNull()?.takeIf(TOKEN::matches) ?: return null)
            "header-add", "header-replace" -> "HEADER_SET" to ((args.firstOrNull()?.takeIf(TOKEN::matches) ?: return null) + ": " + args.drop(1).joinToString(" ").ifEmpty { return null })
            "query-del" -> "QUERY_REMOVE" to (args.singleOrNull()?.takeIf(TOKEN::matches) ?: return null)
            else -> return null
        }
        val (host, subdomains) = host(pattern) ?: return null
        // The draft is validated exactly like a hand-made rule, with a placeholder package.
        val d = RewriteDraft(host, subdomains, "", "REGEX", pattern, action, arg)
        return d.takeIf { RewriteRule(0, "placeholder.app", host, subdomains, "", "REGEX", pattern, action, arg, true, 0).valid() }
    }

    /** Host named by a URL regex: (host, includeSubdomains), or null when no literal host of at least two labels can be read. */
    internal fun host(pattern: String): Pair<String, Boolean>? {
        var p = pattern.removePrefix("^")
        p = when {
            p.startsWith("https?:\\/\\/") -> p.removePrefix("https?:\\/\\/")
            p.startsWith("https?://") -> p.removePrefix("https?://")
            p.startsWith("https:\\/\\/") -> p.removePrefix("https:\\/\\/")
            p.startsWith("https://") -> p.removePrefix("https://")
            p.startsWith("http:\\/\\/") -> p.removePrefix("http:\\/\\/")
            p.startsWith("http://") -> p.removePrefix("http://")
            else -> return null
        }
        // The host part ends at the first path slash (plain or escaped) or at the end of the pattern.
        val end = listOf(p.indexOf("\\/"), p.indexOf('/')).filter { it >= 0 }.minOrNull() ?: p.length
        val part = p.substring(0, end).removeSuffix("$")
        val literal = StringBuilder()
        var wholeLiteral = true
        var i = part.length
        // Read literal labels from the right: letters, digits, '-', and '\.' as a dot. Stop at the first regex construct.
        while (i > 0) {
            val c = part[i - 1]
            when {
                c.isLetterOrDigit() || c == '-' -> { literal.insert(0, c.lowercaseChar()); i-- }
                c == '.' && i >= 2 && part[i - 2] == '\\' -> { literal.insert(0, '.'); i -= 2 }
                // Many plugins leave the dots of a host unescaped; between two label characters that is clearly meant as a dot.
                c == '.' && i >= 2 && (part[i - 2].isLetterOrDigit() || part[i - 2] == '-') && literal.isNotEmpty() -> { literal.insert(0, '.'); i-- }
                else -> { wholeLiteral = false; break }
            }
        }
        val text = literal.toString().trimStart('.')
        // A cut inside a label ("[a-z]+pi.example.com") must not produce "pi.example.com": keep whole labels only.
        val host = if (wholeLiteral || literal.startsWith(".")) text else text.substringAfter('.', "")
        val normalized = BlockTargets.host(host)?.takeIf { it == host && it.count { ch -> ch == '.' } >= 1 } ?: return null
        return normalized to !wholeLiteral
    }
}
