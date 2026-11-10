package com.nathanhanapps.wayd.feature.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.capture.CaptureController
import com.nathanhanapps.wayd.capture.CaptureState
import com.nathanhanapps.wayd.capture.CaptureUi
import com.nathanhanapps.wayd.capture.TrafficRecording
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.core.db.CoverageCounts
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordViewModel @Inject constructor(
    state: CaptureState,
    flowDao: FlowDao,
    private val controller: CaptureController,
    private val exporter: com.nathanhanapps.wayd.feature.export.ExportService,
    val recording: TrafficRecording,
    val blocking: BlockingRules,
) : ViewModel() {
    val ui: StateFlow<CaptureUi> = state.ui

    val recent: StateFlow<List<Flow>> = flowDao.observeRecent(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val coverage: StateFlow<CoverageCounts> = flowDao.observeCoverage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoverageCounts(0, 0, 0, 0))

    fun exportName() = exporter.captureFileName()

    suspend fun exportCapture(target: android.net.Uri) = exporter.exportLatestCapture(target)

    fun start() = controller.start()

    fun stop() = controller.stop()

    fun refreshRules() = viewModelScope.launch { runCatching { blocking.refresh() } }
}
