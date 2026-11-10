package com.nathanhanapps.wayd.feature.apps

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nathanhanapps.wayd.apkscan.ApkScanWorker
import com.nathanhanapps.wayd.core.db.ApkFact
import com.nathanhanapps.wayd.core.db.ApkScanDao
import com.nathanhanapps.wayd.core.db.AppInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StaticEvidence(val app: AppInfo?, val facts: List<ApkFact>, val ops: List<String>, val hosts: List<String>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ApkScanViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: ApkScanDao,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val manager = WorkManager.getInstance(context)
    val selected = savedState.getStateFlow("pkg", "")
    val apps = dao.observeApps().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val limited = dao.observeLimitedPackages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val work = manager.getWorkInfosForUniqueWorkFlow(ApkScanWorker.WORK).map { infos ->
        infos.maxByOrNull { info -> info.tags.firstOrNull { it.startsWith("started:") }?.substringAfter(':')?.toLongOrNull() ?: 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val evidence = selected.flatMapLatest { pkg ->
        if (pkg.isEmpty()) flowOf(null)
        else dao.observeFacts(pkg).map { facts -> StaticEvidence(dao.app(pkg), facts, dao.observedOps(pkg), dao.observedHosts(pkg)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun select(pkg: String) { savedState["pkg"] = pkg }
    fun scan(pkg: String? = null) {
        val request = OneTimeWorkRequestBuilder<ApkScanWorker>()
            .setInputData(if (pkg == null) workDataOf() else workDataOf("pkg" to pkg))
            .addTag("started:${System.currentTimeMillis()}").build()
        manager.enqueueUniqueWork(ApkScanWorker.WORK, ExistingWorkPolicy.KEEP, request)
    }
    fun cancel() { manager.cancelUniqueWork(ApkScanWorker.WORK) }
}
