package com.nathanhanapps.wayd.research

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional, root-only: makes the system trust WAYD's CA so apps that ignore user-installed CAs can be decrypted.
 *
 * It installs NOTHING persistent. It builds a tmpfs copy of the system CA directory with WAYD's CA added and bind-mounts it
 * over `/system/etc/security/cacerts` and the Conscrypt APEX copy (Android 14+ reads that one), in the global mount
 * namespace and in the zygotes' namespaces so newly started apps see it. A reboot undoes everything, and [remove] undoes it
 * immediately. Apps that are already running must be restarted to see the change.
 */
class SystemCa(private val context: Context) {
    sealed interface Result {
        data object Ok : Result
        data class Failed(val detail: String) : Result
    }

    private val work = "/data/local/tmp/wayd_cacerts"

    fun isInstalled(storeName: String): Boolean =
        su("[ -f /system/etc/security/cacerts/$storeName.0 ] && echo yes").second.trim() == "yes"

    fun install(certPem: String, storeName: String): Result {
        val pem = File(context.cacheDir, "wayd_ca.pem").apply { writeText(certPem) }
        val script = File(context.cacheDir, "wayd_ca_install.sh").apply {
            writeText(
                """
                set -e
                W=$work
                umount /system/etc/security/cacerts 2>/dev/null || true
                umount /apex/com.android.conscrypt/cacerts 2>/dev/null || true
                rm -rf ${'$'}W; mkdir -p ${'$'}W
                mount -t tmpfs tmpfs ${'$'}W
                SRC=/apex/com.android.conscrypt/cacerts
                [ -d ${'$'}SRC ] || SRC=/system/etc/security/cacerts
                cp ${'$'}SRC/* ${'$'}W/
                cp ${pem.absolutePath} ${'$'}W/$storeName.0
                chown root:root ${'$'}W/*
                chmod 644 ${'$'}W/*
                chcon u:object_r:system_file:s0 ${'$'}W ${'$'}W/*
                bind() { mount --bind ${'$'}W "${'$'}1"; }
                bind /system/etc/security/cacerts
                [ -d /apex/com.android.conscrypt/cacerts ] && bind /apex/com.android.conscrypt/cacerts
                for z in $(pidof zygote zygote64); do
                  nsenter --mount=/proc/${'$'}z/ns/mnt -- /bin/sh -c "mount --bind ${'$'}W /system/etc/security/cacerts; [ -d /apex/com.android.conscrypt/cacerts ] && mount --bind ${'$'}W /apex/com.android.conscrypt/cacerts" || true
                done
                echo done
                """.trimIndent(),
            )
        }
        val (code, out) = su("sh ${script.absolutePath}")
        pem.delete()
        script.delete()
        return if (code == 0 && out.contains("done")) Result.Ok else Result.Failed(out.trim().take(300))
    }

    fun remove(): Result {
        val (code, out) = su(
            "umount /system/etc/security/cacerts 2>/dev/null; umount /apex/com.android.conscrypt/cacerts 2>/dev/null; " +
                "for z in \$(pidof zygote zygote64); do nsenter --mount=/proc/\$z/ns/mnt -- /bin/sh -c " +
                "'umount /system/etc/security/cacerts 2>/dev/null; umount /apex/com.android.conscrypt/cacerts 2>/dev/null'; done; " +
                "umount $work 2>/dev/null; rm -rf $work; echo done",
        )
        return if (code == 0 && out.contains("done")) Result.Ok else Result.Failed(out.trim().take(300))
    }

    /** Runs one command as root. Returns (exit code, combined output); (-1, reason) if root is unavailable. */
    private fun su(cmd: String): Pair<Int, String> = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return -1 to "timeout"
        }
        p.exitValue() to out
    }.getOrElse { -1 to (it.message ?: "su unavailable") }
}
