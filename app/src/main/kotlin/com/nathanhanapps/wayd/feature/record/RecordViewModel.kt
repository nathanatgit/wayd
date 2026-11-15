package com.nathanhanapps.wayd.feature.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.capture.CaptureController
import com.nathanhanapps.wayd.capture.CaptureState
import com.nathanhanapps.wayd.capture.CaptureUi
import com.nathanhanapps.wayd.capture.TrafficRecording
import com.nathanhanapps.wayd.feature.common.SpeedSamples
import com.nathanhanapps.wayd.feature.common.speedSamples
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.nathanhanapps.wayd.feature.analysis.AnalysisService
import com.nathanhanapps.wayd.feature.analysis.Windows

/** Home only reads in-memory VPN state; it deliberately observes no database table. */
@HiltViewModel
class RecordViewModel @Inject constructor(
    state: CaptureState,
    private val controller: CaptureController,
    val recording: TrafficRecording,
    val blocking: BlockingRules,
    private val routing: com.nathanhanapps.wayd.capture.RoutingBridge,
    private val analysis: AnalysisService,
) : ViewModel() {
    /** Findings that need attention in today's report (the same count as the Insights tab), or null until loaded. */
    val attention = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    fun refreshAttention() {
        viewModelScope.launch {
            attention.value = runCatching { analysis.analyse(Windows.TODAY).report.let { it.alerts.size + it.others.size } }.getOrNull()
        }
    }
    val ui: StateFlow<CaptureUi> = state.ui

    /** Runs only while Home is on screen. */
    val speed: StateFlow<SpeedSamples> = speedSamples(state).stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), SpeedSamples())

    fun start() = controller.start()

    fun stop() = controller.stop()

    fun refreshRules() = viewModelScope.launch { runCatching { blocking.refresh(); routing.refresh() } }
}
