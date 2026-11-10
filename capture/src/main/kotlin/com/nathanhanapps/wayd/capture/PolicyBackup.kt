package com.nathanhanapps.wayd.capture

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class PortableBlockRule(
    val id: String,
    val revision: Int,
    val enabled: Boolean,
    val packageName: String,
    val signers: List<String>,
    val kind: String,
    val target: String?,
    val redacted: Boolean = false,
)

/** A deliberately small, strict JSON format. No grants, local IDs, file references or executable actions. */
object PolicyBackup {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_RULES = 1000

    fun decode(bytes: ByteArray): List<PortableBlockRule> {
        require(bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = Json(text).read().obj()
        root.keysExactly("format", "schemaVersion", "rules")
        require(root["format"] == "wayd-policy" && root["schemaVersion"] == 1L)
        val rules = (root["rules"] as? List<*>) ?: error("Invalid rules")
        require(rules.size <= MAX_RULES)
        val ids = HashSet<String>()
        return rules.map { item ->
            val r = item.obj()
            r.keysExactly("id", "revision", "enabled", "app", "lifetime", "match", "action", "redacted")
            val id = r.string("id")
            require(UUID.fromString(id).toString() == id && ids.add(id))
            val revision = r["revision"] as? Long ?: error("Invalid revision")
            require(revision in 1..Int.MAX_VALUE.toLong())
            val app = r["app"].obj()
            app.keysExactly("packageName", "signingSha256")
            val pkg = app.string("packageName")
            require(BlockTargets.packageName(pkg))
            val signers = (app["signingSha256"] as? List<*>)?.map { it as? String ?: error("Invalid signer") }
                ?: error("Invalid signers")
            require(signers.size <= 16 && signers.toSet().size == signers.size &&
                signers.all { it.length == 64 && it.all { c -> c in "0123456789abcdef" } })
            require(r["lifetime"] == "persistent")
            val action = r["action"].obj()
            action.keysExactly("type")
            require(action["type"] == "BLOCK")
            val match = r["match"].obj()
            require(match.size == 1)
            val kind = when (match.keys.single()) {
                "app" -> "APP"
                "hostExact" -> "HOST"
                "ipExact" -> "IP"
                else -> error("Unsupported predicate")
            }
            val redacted = r["redacted"] as? Boolean ?: error("Invalid redaction")
            val target = match.values.single().let { if (it == null) null else it as? String ?: error("Invalid target") }
            require(if (redacted) kind != "APP" && target == null else when (kind) {
                "APP" -> target == "*"
                "HOST" -> target != null && BlockTargets.host(target) == target
                else -> target != null && BlockTargets.ip(target) != null
            })
            PortableBlockRule(id, revision.toInt(), r["enabled"] as? Boolean ?: error("Invalid enabled"),
                pkg, signers.sorted(), kind, target, redacted)
        }
    }

    fun encode(rules: List<PortableBlockRule>): ByteArray {
        fun q(value: String) = buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
        val data = buildString {
            append("{\"format\":\"wayd-policy\",\"schemaVersion\":1,\"rules\":[")
            rules.forEachIndexed { i, r ->
                if (i > 0) append(',')
                val key = when (r.kind) { "APP" -> "app"; "HOST" -> "hostExact"; "IP" -> "ipExact"; else -> error("Invalid kind") }
                append("{\"id\":${q(r.id)},\"revision\":${r.revision},\"enabled\":${r.enabled},")
                append("\"app\":{\"packageName\":${q(r.packageName)},\"signingSha256\":[")
                append(r.signers.joinToString(",") { q(it) })
                append("]},\"lifetime\":\"persistent\",\"match\":{${q(key)}:${r.target?.let(::q) ?: "null"}},")
                append("\"action\":{\"type\":\"BLOCK\"},\"redacted\":${r.redacted}}")
            }
            append("]}")
        }.toByteArray(Charsets.UTF_8)
        decode(data) // Validate output with exactly the same limits as input.
        return data
    }

    private fun Any?.obj(): Map<*, *> = this as? Map<*, *> ?: error("Expected object")
    private fun Map<*, *>.keysExactly(vararg keys: String) = require(this.keys == keys.toSet())
    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: error("Expected string")

    /** Strict RFC-style syntax, bounded depth/tokens/strings; duplicate decoded keys are rejected. */
    private class Json(private val text: String) {
        private var pos = 0
        private var tokens = 0
        fun read(): Any? { val result = value(0); ws(); require(pos == text.length); return result }
        private fun ws() { while (pos < text.length && text[pos] in " \r\n\t") pos++ }
        private fun take(c: Char): Boolean { ws(); return if (pos < text.length && text[pos] == c) { pos++; true } else false }
        private fun value(depth: Int): Any? {
            require(depth <= 8 && ++tokens <= 40000)
            ws(); require(pos < text.length)
            return when (text[pos]) {
                '{' -> {
                    pos++; val map = LinkedHashMap<String, Any?>()
                    if (!take('}')) {
                        do {
                            ws(); val key = string(); require(!map.containsKey(key) && take(':'))
                            map[key] = value(depth + 1)
                        } while (take(','))
                        require(take('}'))
                    }
                    map
                }
                '[' -> {
                    pos++; val list = ArrayList<Any?>()
                    if (!take(']')) {
                        do { require(list.size < MAX_RULES); list += value(depth + 1) } while (take(','))
                        require(take(']'))
                    }
                    list
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val start = pos
                    if (text[pos] == '-') pos++
                    require(pos < text.length && text[pos] in '0'..'9')
                    if (text[pos] == '0') pos++ else while (pos < text.length && text[pos] in '0'..'9') pos++
                    text.substring(start, pos).toLong()
                }
            }
        }
        private fun literal(word: String, result: Any?): Any? { require(text.startsWith(word, pos)); pos += word.length; return result }
        private fun string(): String {
            require(pos < text.length && text[pos++] == '"')
            val out = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '"') {
                    val s = out.toString()
                    var i = 0
                    while (i < s.length) {
                        val ch = s[i++]
                        if (ch.isHighSurrogate()) require(i < s.length && s[i++].isLowSurrogate()) else require(!ch.isLowSurrogate())
                    }
                    return s
                }
                require(c >= ' ' && out.length < 1024)
                if (c != '\\') out.append(c) else {
                    require(pos < text.length)
                    out.append(when (val e = text[pos++]) {
                        '"', '\\', '/' -> e
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> { require(pos + 4 <= text.length); val n = text.substring(pos, pos + 4); require(n.all { it in "0123456789abcdefABCDEF" }); pos += 4; n.toInt(16).toChar() }
                        else -> error("Invalid escape")
                    })
                }
            }
            error("Unterminated string")
        }
    }
}
