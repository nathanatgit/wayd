package com.nathanhanapps.wayd.privilege

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Strict package-name check: the only user-supplied value that ever reaches a privileged command line. */
object PackageNames {
    private val VALID = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")

    fun isValid(pkg: String?): Boolean = pkg != null && pkg.length <= 255 && VALID.matches(pkg)
}

/** Strict op-name check (`FINE_LOCATION`, `READ_CLIPBOARD`...). */
object OpNames {
    private val VALID = Regex("""^[A-Z][A-Z0-9_]{1,63}$""")

    fun isValid(op: String?): Boolean = op != null && VALID.matches(op)
}

/**
 * Runs in Shizuku's privileged (shell) process. Every method maps to one fixed command template; arguments are
 * validated and passed as separate argv entries (no shell, no string concatenation into a command line).
 * Replies cross Binder, so output is filtered/capped here (see [MAX_REPLY]).
 */
class PrivilegedService : IPrivilegedService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun appOpsHistory(pkg: String?): String {
        if (!PackageNames.isValid(pkg)) return ""
        val filter = HistoryFilter(pkg!!)
        return run(listOf("dumpsys", "appops", "--history", "--package", pkg), filter::accept)
    }

    override fun appOpsHistoryFor(pkgsCsv: String?, opsCsv: String?): String {
        val pkgs = pkgsCsv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val ops = opsCsv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (pkgs.isEmpty() || pkgs.size > MAX_PACKAGES || ops.size > MAX_OPS) return ""
        if (!pkgs.all { PackageNames.isValid(it) } || !ops.all { OpNames.isValid(it) }) return ""
        val filter = HistoryFilter(pkgs.toSet(), ops.toSet())
        // `--package` is omitted on purpose: the dump already lists every app, and filtering happens here.
        return run(listOf("dumpsys", "appops", "--history"), filter::accept)
    }

    override fun appOpsGet(pkg: String?): String =
        if (PackageNames.isValid(pkg)) run(listOf("cmd", "appops", "get", pkg!!)) { true } else ""

    private fun run(argv: List<String>, keep: (String) -> Boolean): String {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        p.inputStream.bufferedReader().use { r ->
            while (System.nanoTime() < deadline) {
                val line = r.readLine() ?: break
                if (keep(line)) {
                    if (sb.length + line.length + 1 > MAX_REPLY) break
                    sb.append(line).append('\n')
                }
            }
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        return sb.toString()
    }

    private companion object {
        /** Stay well under the ~1 MB Binder transaction buffer shared by all in-flight transactions. */
        const val MAX_REPLY = 600 * 1024
        const val MAX_PACKAGES = 64
        const val MAX_OPS = 200
    }
}
