package com.nathanhanapps.wayd.feature.analysis

import android.content.Context
import com.nathanhanapps.wayd.collectors.AppOpsHistoryParser
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
class PermissionHistoryService @Inject constructor(private val shizuku: ShizukuClient) {
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
}
