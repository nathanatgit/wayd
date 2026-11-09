package com.nathanhanapps.wayd.privilege

/**
 * Keeps only what the app-ops history parser needs from `dumpsys appops --history`: bucket `begin`/`end` lines and the
 * blocks of the requested packages. The raw dump lists every app in every bucket (7 MB+ on a phone), which exceeds the
 * Binder transaction limit, so it must be reduced inside the privileged process before it is returned.
 *
 * With [ops] set, op blocks outside that set are dropped too (and attribution lines, which the parser ignores).
 */
class HistoryFilter(private val pkgs: Set<String>, private val ops: Set<String>? = null) {
    constructor(pkg: String) : this(setOf(pkg))

    private var inTarget = false
    private var opKept = false

    fun accept(line: String): Boolean {
        val t = line.trimStart()
        return when {
            t.startsWith("begin = ") -> { inTarget = false; opKept = false; true }
            t.startsWith("end = ") -> true
            t.startsWith("Uid ") && t.endsWith(":") -> { inTarget = false; opKept = false; false }
            t.startsWith("Package ") && t.endsWith(":") -> {
                inTarget = t.removePrefix("Package ").removeSuffix(":").trim() in pkgs
                opKept = false
                inTarget
            }
            !inTarget -> false
            ops == null -> true
            t.startsWith("Attribution ") -> false
            OP_LINE.matches(t) -> { opKept = t.removeSuffix(":") in ops; opKept }
            else -> opKept
        }
    }

    private companion object {
        val OP_LINE = Regex("""^[A-Z][A-Z0-9_]{2,}:$""")
    }
}
