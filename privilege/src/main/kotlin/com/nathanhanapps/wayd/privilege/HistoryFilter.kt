package com.nathanhanapps.wayd.privilege

/**
 * Keeps only what the app-ops history parser needs from `dumpsys appops --history`: the bucket `begin`/`end` lines and
 * the block of the target package. The raw dump lists every app in every bucket (7 MB+ on a phone), which exceeds the
 * Binder transaction limit, so it must be reduced inside the privileged process before it is returned.
 */
class HistoryFilter(private val pkg: String) {
    private var inTarget = false

    fun accept(line: String): Boolean {
        val t = line.trimStart()
        return when {
            t.startsWith("begin = ") || t.startsWith("end = ") -> { if (t.startsWith("begin = ")) inTarget = false; true }
            t.startsWith("Uid ") && t.endsWith(":") -> { inTarget = false; false }
            t.startsWith("Package ") && t.endsWith(":") -> {
                inTarget = t.removePrefix("Package ").removeSuffix(":").trim() == pkg
                inTarget
            }
            else -> inTarget
        }
    }
}
