package com.nathanhanapps.wayd.feature.analysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.analysis.AppReport
import com.nathanhanapps.wayd.analysis.DailyReport
import com.nathanhanapps.wayd.core.db.DayRow
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

/** Apps tab: one row per app, ranked by how much it did in the chosen window. */
@HiltViewModel
class AppsViewModel @Inject constructor(private val analysis: AnalysisService) : ViewModel() {
    val window = MutableStateFlow(Windows.TODAY)
    private val _result = MutableStateFlow<Loadable<AnalysisResult>>(Loadable.Loading)
    val result: StateFlow<Loadable<AnalysisResult>> = _result

    init {
        refresh()
    }

    fun select(key: String) {
        window.value = key
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _result.value = Loadable.Loading
            _result.value = Loadable.Ready(analysis.analyse(window.value))
        }
    }
}

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    private val analysis: AnalysisService,
    val labels: AppLabels,
    private val permissionService: PermissionHistoryService,
    private val exporter: com.nathanhanapps.wayd.feature.export.ExportService,
    savedState: SavedStateHandle,
) : ViewModel() {
    val pkg: String = savedState["pkg"] ?: ""
    val windowKey: String = savedState["win"] ?: Windows.TODAY
    private val _report = MutableStateFlow<Loadable<AppReport?>>(Loadable.Loading)
    val report: StateFlow<Loadable<AppReport?>> = _report

    private val _permissions = MutableStateFlow<PermissionHistory>(PermissionHistory.Loading)
    val permissions: StateFlow<PermissionHistory> = _permissions

    init {
        viewModelScope.launch { _report.value = Loadable.Ready(analysis.analyse(windowKey).apps[pkg]) }
        // The user may allow WAYD in Shizuku while this screen is open: reload whenever Shizuku's state changes.
        viewModelScope.launch { permissionService.state.collect { loadPermissions() } }
    }

    private fun loadPermissions() {
        viewModelScope.launch { _permissions.value = permissionService.forApp(pkg, windowKey) }
    }

    fun exportName() = exporter.appFileName(pkg)

    suspend fun export(target: android.net.Uri) = exporter.exportApp(pkg, windowKey, target)

    fun requestShizuku() = permissionService.requestAccess()

    fun retryPermissions() {
        permissionService.refresh()
        loadPermissions()
    }

    suspend fun evidence(json: String): Evidence = analysis.evidence(json)

    suspend fun flows(ids: List<Long>): List<Flow> = analysis.flowsByIds(ids)
}

@HiltViewModel
class ReportsViewModel @Inject constructor(flowDao: FlowDao) : ViewModel() {
    val days: StateFlow<List<DayRow>> = flowDao.observeDays(60)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@HiltViewModel
class ReportDetailViewModel @Inject constructor(
    private val analysis: AnalysisService,
    val labels: AppLabels,
    savedState: SavedStateHandle,
) : ViewModel() {
    val windowKey: String = savedState["win"] ?: Windows.TODAY
    private val _report = MutableStateFlow<Loadable<DailyReport>>(Loadable.Loading)
    val report: StateFlow<Loadable<DailyReport>> = _report

    init {
        viewModelScope.launch { _report.value = Loadable.Ready(analysis.analyse(windowKey).report) }
    }
}
