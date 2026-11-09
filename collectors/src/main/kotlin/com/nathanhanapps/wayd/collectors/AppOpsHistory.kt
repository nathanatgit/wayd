package com.nathanhanapps.wayd.collectors

/** Process state an access happened in, from the `[top-s]`-style flag in `dumpsys appops --history`. */
enum class ProcState { TOP, FOREGROUND_SERVICE, FOREGROUND, BACKGROUND, CACHED, PERSISTENT, OTHER;

    /** True when the user could plausibly see the app (visible or running a foreground service). */
    val visible get() = this == TOP || this == FOREGROUND || this == FOREGROUND_SERVICE
    val background get() = this == BACKGROUND || this == CACHED
}

/** One permission/op in one history bucket for one process state. */
data class OpBucket(
    val op: String,
    val start: Long,
    val end: Long,
    val state: ProcState,
    val access: Int,
    val reject: Int,
    val durationMs: Long,
)

/**
 * Parser for the output of `dumpsys appops --history --package <pkg>` (Android 11+).
 *
 * The dump is a list of time buckets (≈15 min for recent history, coarser further back). Inside each, the target
 * package lists ops with per-process-state counters. Input is untrusted: every pattern is anchored, loops are linear and
 * anything unrecognised is skipped. Absolute bucket times are derived from the relative `(-1h2m3s)` offsets and the
 * caller's [now], so the device's time zone never matters.
 */
object AppOpsHistoryParser {
    private val BEGIN = Regex("""^\s*begin = \S+ \S+\s+\((.*)\)\s*$""")
    private val END = Regex("""^\s*end = \S+ \S+\s+\((.*)\)\s*$""")
    private val PACKAGE = Regex("""^\s*Package (\S+):\s*$""")
    private val UID = Regex("""^\s*Uid \S+:\s*$""")
    private val OP = Regex("""^\s*([A-Z][A-Z0-9_]{2,}):\s*$""")
    private val STATE = Regex("""^\s*\[([a-z]+)-[a-z]+]\s*=\s*(.*)$""")
    private val ACCESS = Regex("""access=(\d+)""")
    private val REJECT = Regex("""reject=(\d+)""")
    private val DURATION = Regex("""duration=\+?([0-9dhmsµ ]+?)(?:,|$)""")

    fun parse(text: String, pkg: String, now: Long): List<OpBucket> = parseAll(text, now) { it == pkg }[pkg].orEmpty()

    /** Parses every package present in [text] (optionally only those accepted by [accept]), keyed by package name. */
    fun parseAll(text: String, now: Long, accept: (String) -> Boolean = { true }): Map<String, List<OpBucket>> {
        val out = LinkedHashMap<String, MutableList<OpBucket>>()
        var start = 0L
        var end = 0L
        var pkg: String? = null
        var op: String? = null
        for (line in text.lineSequence()) {
            BEGIN.matchEntire(line)?.let {
                start = now - relativeMs(it.groupValues[1])
                pkg = null
                op = null
                return@let
            }
            END.matchEntire(line)?.let { end = now - relativeMs(it.groupValues[1]); return@let }
            if (UID.matches(line)) { pkg = null; op = null; continue }
            PACKAGE.matchEntire(line)?.let { pkg = it.groupValues[1].takeIf(accept); op = null; return@let }
            val currentPkg = pkg ?: continue
            OP.matchEntire(line)?.let { op = it.groupValues[1]; return@let }
            val currentOp = op ?: continue
            STATE.matchEntire(line)?.let { m ->
                val rest = m.groupValues[2]
                val access = ACCESS.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val reject = REJECT.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val dur = DURATION.find(rest)?.groupValues?.get(1)?.let { relativeMs(it) } ?: 0L
                if (access > 0 || reject > 0) {
                    out.getOrPut(currentPkg) { ArrayList() } += OpBucket(currentOp, start, end, state(m.groupValues[1]), access, reject, dur)
                }
            }
        }
        return out
    }

    private fun state(flag: String) = when (flag) {
        "top" -> ProcState.TOP
        "fgsvc" -> ProcState.FOREGROUND_SERVICE
        "fg" -> ProcState.FOREGROUND
        "bg" -> ProcState.BACKGROUND
        "cch" -> ProcState.CACHED
        "pers" -> ProcState.PERSISTENT
        else -> ProcState.OTHER
    }

    private val UNIT = Regex("""(\d+)(ms|d|h|m|s)""")

    /** Parses "-1h0m43s555ms", "+15m21s528ms", "1d5h" or "0" into milliseconds (sign ignored, result >= 0). */
    fun relativeMs(s: String): Long {
        var total = 0L
        for (m in UNIT.findAll(s.trim().removePrefix("-").removePrefix("+"))) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            total += when (m.groupValues[2]) {
                "ms" -> n
                "s" -> n * 1_000
                "m" -> n * 60_000
                "h" -> n * 3_600_000
                "d" -> n * 86_400_000
                else -> 0
            }
        }
        return total
    }
}
