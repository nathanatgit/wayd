package com.nathanhanapps.wayd.feature.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.capture.CaptureController
import com.nathanhanapps.wayd.capture.CaptureState
import com.nathanhanapps.wayd.capture.CaptureUi
import com.nathanhanapps.wayd.core.db.CoverageCounts
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RecordViewModel @Inject constructor(
    state: CaptureState,
    flowDao: FlowDao,
    private val controller: CaptureController,
    private val exporter: com.nathanhanapps.wayd.feature.export.ExportService,
) : ViewModel() {
    val ui: StateFlow<CaptureUi> = state.ui

    val recent: StateFlow<List<Flow>> = flowDao.observeRecent(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val coverage: StateFlow<CoverageCounts> = flowDao.observeCoverage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoverageCounts(0, 0, 0, 0))

    suspend fun exportCapture() = exporter.exportLatestCapture()

    fun start() = controller.start()

    fun stop() = controller.stop()
}
