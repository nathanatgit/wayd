package com.nathanhanapps.wayd.feature.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.ai.AiAnalyzer
import com.nathanhanapps.wayd.ai.AiOutcome
import com.nathanhanapps.wayd.ai.LiteRtEngine
import com.nathanhanapps.wayd.ai.ModelManager
import com.nathanhanapps.wayd.ai.ModelSpec
import com.nathanhanapps.wayd.ai.ModelState
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.Windows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

sealed interface AiUiState {
    data object Idle : AiUiState
    data object Running : AiUiState

    /** No analysis possible for this window: nothing recorded to explain. */
    data object NoEvidence : AiUiState
    data class Done(val outcome: AiOutcome) : AiUiState
}

/** "Analyse with AI" for one app and window. The model explains; the rule findings above it stay the source of severity. */
@HiltViewModel
class AiAnalysisViewModel @Inject constructor(
    private val analyzer: AiAnalyzer,
    private val evidence: AiEvidenceService,
    private val labels: AppLabels,
    private val models: ModelManager,
    engine: com.nathanhanapps.wayd.ai.AiEngine,
    savedState: SavedStateHandle,
) : ViewModel() {
    val phase: StateFlow<com.nathanhanapps.wayd.ai.EnginePhase> = engine.phase
    private val pkg: String = savedState["pkg"] ?: ""
    private val windowKey: String = savedState["win"] ?: Windows.TODAY
    private val _state = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val state: StateFlow<AiUiState> = _state.asStateFlow()
    private var job: Job? = null

    val hasModel: Boolean get() = models.activeModel() != null

    fun analyse(useCache: Boolean = true) {
        if (job?.isActive == true) return
        _state.value = AiUiState.Running
        job = viewModelScope.launch {
            val locale = evidence.uiLocale()
            val req = evidence.requestFor(pkg, labels.label(pkg), windowKey, locale)
            _state.value = if (req == null) AiUiState.NoEvidence else AiUiState.Done(analyzer.analyse(req, useCache))
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = AiUiState.Idle
    }
}

/** Settings → On-device AI: which model is installed, downloads, mirror choice. */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val models: ModelManager,
    private val engine: LiteRtEngine,
) : ViewModel() {
    val states: StateFlow<Map<String, ModelState>> = models.states
    val ramGb: Double get() = models.totalRamGb()
    val active: String? get() = models.activeModel()?.first?.id

    fun url(spec: ModelSpec): String = models.customUrl(spec) ?: spec.sources.first().url

    fun setUrl(spec: ModelSpec, url: String) = models.setCustomUrl(spec, url.takeUnless { it == spec.sources.first().url })

    fun delete(spec: ModelSpec) {
        viewModelScope.launch {
            engine.release()
            models.delete(spec)
        }
    }

    fun use(spec: ModelSpec) {
        models.setActive(spec)
        viewModelScope.launch { engine.release() }
    }
}
