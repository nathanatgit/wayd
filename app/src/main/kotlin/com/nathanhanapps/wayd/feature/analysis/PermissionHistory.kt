package com.nathanhanapps.wayd.feature.analysis

import android.content.Context
import com.nathanhanapps.wayd.collectors.AppOpsHistoryParser
import com.nathanhanapps.wayd.collectors.PermissionCategory
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.db.PermEventDao
import com.nathanhanapps.wayd.privilege.PackageNames
import com.nathanhanapps.wayd.collectors.PermissionTimeline
import com.nathanhanapps.wayd.collectors.PermissionUse
import com.nathanhanapps.wayd.privilege.ShizukuClient
import com.nathanhanapps.wayd.privilege.ShizukuState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PrivilegeModule {
    @Provides
    @Singleton
    fun shizuku(@ApplicationContext context: Context): ShizukuClient = ShizukuClient(context)
}

sealed interface PermissionHistory {
    data object Loading : PermissionHistory

    /** Privileged access is not available; [state] says what the user has to do. */
    data class Unavailable(val state: ShizukuState) : PermissionHistory

    /** Shizuku is ready but reading the history failed (service start, Binder or parse problem). */
    data object Failed : PermissionHistory

    data class Ready(val uses: List<PermissionUse>) : PermissionHistory
}

/** Reads one app's AppOps history through Shizuku and turns it into per-permission use for a window. */
@Singleton
class PermissionHistoryService @Inject constructor(private val shizuku: ShizukuClient, private val events: PermEventDao) {
    val state: StateFlow<ShizukuState> = shizuku.state

    fun requestAccess() = shizuku.requestPermission()

    fun refresh() = shizuku.refresh()

    suspend fun forApp(pkg: String, windowKey: String): PermissionHistory = withContext(Dispatchers.IO) {
        shizuku.refresh()
        val st = shizuku.state.value
        if (st != ShizukuState.READY) return@withContext PermissionHistory.Unavailable(st)
        val now = System.currentTimeMillis()
        val text = shizuku.appOpsHistory(pkg)?.takeIf { it.isNotBlank() } ?: return@withContext PermissionHistory.Failed
        val (start, end) = Windows.resolve(windowKey)
        val buckets = AppOpsHistoryParser.parse(text, pkg, now)
        PermissionHistory.Ready(PermissionTimeline.build(buckets, start, minOf(end, now), TimeZone.getDefault().getOffset(now).toLong()))
    }

    /**
     * Stores the AppOps history buckets of [pkgs] that started in the window as [PermEvent] rows, replacing what was
     * stored for that window, so rules can cite them and history survives AppOps' own retention. Best effort: does
     * nothing unless Shizuku is ready. Only fine-grained buckets (<= 2 h) are stored; older coarse ones are skipped.
     */
    suspend fun sync(pkgs: Collection<String>, windowStart: Long, windowEnd: Long) = withContext(Dispatchers.IO) {
        shizuku.refresh()
        if (shizuku.state.value != ShizukuState.READY) return@withContext
        val valid = pkgs.filter { PackageNames.isValid(it) }.distinct().take(64)
        if (valid.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val text = shizuku.appOpsHistoryFor(valid, PermissionCategory.watchedOps)?.takeIf { it.isNotBlank() } ?: return@withContext
        val from = windowStart - 30 * 60_000L
        val to = minOf(windowEnd, now)
        val rows = AppOpsHistoryParser.parseAll(text, now) { it in valid }.flatMap { (pkg, list) ->
            list.filter { it.access > 0 && it.start in from until to && it.end - it.start <= 2 * 3_600_000L }.map {
                PermEvent(
                    pkg = pkg, op = it.op, ts = it.start, durationMs = it.durationMs.takeIf { d -> d > 0 },
                    background = it.state.background, source = "appops:" + it.state.name.lowercase(),
                    count = it.access, bucketEnd = it.end,
                )
            }
        }
        events.deleteAppOpsBetween(from, to)
        events.insertAll(rows)
    }
}
