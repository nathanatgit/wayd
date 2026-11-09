package com.nathanhanapps.wayd.research

import android.content.Context
import com.nathanhanapps.wayd.R
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional, root-only: makes the system trust WAYD's CA so apps that ignore user-installed CAs can be decrypted.
 *
 * It installs NOTHING persistent. For the global mount namespace and each zygote namespace it builds a tmpfs copy of the
 * system CA directory with WAYD's CA added and mounts it over `/system/etc/security/cacerts` and the Conscrypt APEX copy
 * (Android 14+ reads that one). It then verifies every namespace and rolls back if anything is off, so the trust store is
 * never left empty. A reboot undoes it too, and [remove] undoes it immediately. Running apps must be restarted to see it.
 *
 * The scripts run in the global namespace on purpose: KernelSU's `su` otherwise gives the app a private mount namespace
 * and the mounts never reach the zygotes.
 */
class SystemCa(private val context: Context) {
    sealed interface Result {
        data object Ok : Result
        data class Failed(val detail: String) : Result
    }

    fun install(certPem: String, storeName: String): Result {
        val dir = context.cacheDir
        val pem = File(dir, "wayd_ca.pem").apply { writeText(certPem) }
        val apply = File(dir, "wayd_ca_apply.sh").apply { writeText(raw(R.raw.wayd_ca_apply)) }
        val driver = File(dir, "wayd_ca_install.sh").apply {
            writeText(
                raw(R.raw.wayd_ca_install)
                    .replace("@PEM@", pem.absolutePath).replace("@NAME@", storeName).replace("@APPLY@", apply.absolutePath),
            )
        }
        val (code, out) = su("nsenter -t 1 -m -- sh ${driver.absolutePath}")
        listOf(pem, apply, driver).forEach { it.delete() }
        return if (code == 0 && out.lines().any { it.trim() == "done" }) Result.Ok else Result.Failed(out.trim().takeLast(300).ifBlank { "root denied" })
    }

    fun remove(): Result {
        val (code, out) = su(
            "nsenter -t 1 -m -- sh -c 'for p in 1 \$(pidof zygote zygote64); do nsenter --mount=/proc/\$p/ns/mnt -- sh -c " +
                "\"umount /apex/com.android.conscrypt/cacerts 2>/dev/null; umount /system/etc/security/cacerts 2>/dev/null\"; done; " +
                "rm -rf /data/local/tmp/wayd_ca_tmp; echo done'",
        )
        return if (code == 0 && out.lines().any { it.trim() == "done" }) Result.Ok else Result.Failed(out.trim().takeLast(300).ifBlank { "root denied" })
    }

    private fun raw(id: Int) = context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    /** Runs one command as root. Returns (exit code, combined output); (-1, reason) if root is unavailable. */
    private fun su(cmd: String): Pair<Int, String> = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return -1 to "timeout"
        }
        p.exitValue() to out
    }.getOrElse { -1 to (it.message ?: "su unavailable") }
}
