package com.nathanhanapps.wayd.apkscan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Unique user-started, non-expedited local work, at most two APK scans concurrently. */
class ApkScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies { fun scanner(): ApkScanner }

    override suspend fun doWork(): Result {
        val scanner = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).scanner()
        val target = inputData.getString("pkg")
        val packages = if (target != null) listOf(target) else scanner.candidates()
        var failed = 0
        var scanned = 0
        var cached = 0
        val start = System.nanoTime()
        var done = 0
        for (chunk in packages.chunked(2)) {
            coroutineContext.ensureActive()
            val results = coroutineScope {
                chunk.map { pkg -> async {
                    try { if (scanner.scan(pkg, force = target != null)) 1 else 0 }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { -1 } // Never log package data or hostile parser messages.
                } }.awaitAll()
            }
            scanned += results.count { it == 1 }
            cached += results.count { it == 0 }
            failed += results.count { it == -1 }
            done += chunk.size
            setProgress(workDataOf("done" to done, "total" to packages.size, "failed" to failed))
        }
        return Result.success(workDataOf("done" to packages.size, "total" to packages.size,
            "scanned" to scanned, "cached" to cached, "failed" to failed,
            "seconds" to ((System.nanoTime() - start) / 1_000_000_000)))
    }

    companion object { const val WORK = "apk-scan" }
}
