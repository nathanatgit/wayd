package com.nathanhanapps.wayd.feature.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.ai.AiAnalyzer
import com.nathanhanapps.wayd.ai.AiOutcome
import com.nathanhanapps.wayd.ai.AiProvider
import com.nathanhanapps.wayd.ai.ExternalAiException
import com.nathanhanapps.wayd.ai.ExternalAiSettings
import com.nathanhanapps.wayd.ai.ExternalAiUi
import com.nathanhanapps.wayd.ai.LiteRtEngine
import com.nathanhanapps.wayd.ai.ModelDownloads
import com.nathanhanapps.wayd.ai.ModelManager
import com.nathanhanapps.wayd.ai.ModelSpec
import com.nathanhanapps.wayd.ai.ModelState
import com.nathanhanapps.wayd.ai.OpenAiCompatibleClient
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.Windows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val gate: ExternalAiGate,
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
            _state.value = if (req == null) AiUiState.NoEvidence else when (val o = analyzer.analyse(req, useCache, gate::ask)) {
                AiOutcome.Declined -> AiUiState.Idle // the user chose not to send; nothing happened
                else -> AiUiState.Done(o)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = AiUiState.Idle
    }
}

/** Settings → AI: which provider answers, and the on-device models (download source, install, remove). */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val models: ModelManager,
    private val engine: LiteRtEngine,
    val external: ExternalAiSettings,
    val depths: com.nathanhanapps.wayd.ai.AiDepthSettings,
) : ViewModel() {
    val states: StateFlow<Map<String, ModelState>> = models.states
    val downloading: StateFlow<String?> = models.activeDownload
    val ext: StateFlow<ExternalAiUi> = external.state
    val ramGb: Double get() = models.totalRamGb()

    private val _active = MutableStateFlow(models.activeModel()?.id)
    val active: StateFlow<String?> = _active.asStateFlow()
    private val _source = MutableStateFlow(models.sourceIndex())
    val source: StateFlow<Int> = _source.asStateFlow()

    init {
        viewModelScope.launch { external.load() }
        viewModelScope.launch { models.states.collect { _active.value = models.activeModel()?.id } }
        viewModelScope.launch { models.refresh(); models.checkChanged() }
    }

    fun setProvider(p: AiProvider) = external.setProvider(p)

    fun setSource(index: Int) { models.setSourceIndex(index); _source.value = index }

    fun customUrl(spec: ModelSpec): String? = models.customUrl(spec)

    fun setCustomUrl(spec: ModelSpec, url: String?) = models.setCustomUrl(spec, url)

    fun url(spec: ModelSpec): String = models.downloadUrl(spec)

    fun delete(spec: ModelSpec) {
        viewModelScope.launch {
            engine.release()
            models.delete(spec)
        }
    }

    /** `Download/WAYD` style name of the model folder, or null while models are kept in app storage. */
    fun folderName(): String? = models.folderName()

    fun suggestedFolder() = models.suggestedFolder()

    fun setFolder(uri: android.net.Uri) {
        viewModelScope.launch {
            engine.release()
            runCatching { models.setFolder(uri) }
            models.checkChanged()
        }
    }

    /** Re-reads the folder and checks files that changed, e.g. after the user copied a model in by hand. */
    fun recheck() {
        viewModelScope.launch {
            engine.release()
            models.refresh()
            models.checkChanged()
        }
    }

    fun ignoreMismatch(spec: ModelSpec) = models.ignoreMismatch(spec)

    /** Frees the engine (it may be reading the old file), then downloads the published file over the old one. */
    fun redownload(context: android.content.Context, spec: ModelSpec) {
        viewModelScope.launch {
            engine.release()
            ModelDownloads.start(context, spec, models.downloadUrl(spec))
        }
    }

    fun use(spec: ModelSpec) {
        models.setActive(spec)
        _active.value = spec.id
        viewModelScope.launch { engine.release() }
    }
}

sealed interface ExternalTest {
    data object Idle : ExternalTest
    data object Running : ExternalTest
    data class Ok(val models: List<String>) : ExternalTest
    data class Failed(val code: String) : ExternalTest
}

/** The form for the optional OpenAI-compatible provider. Nothing is sent anywhere except the user's own "Test connection". */
@HiltViewModel
class ExternalAiViewModel @Inject constructor(
    val settings: ExternalAiSettings,
    private val client: OpenAiCompatibleClient,
    val depths: com.nathanhanapps.wayd.ai.AiDepthSettings,
) : ViewModel() {
    val ui: StateFlow<ExternalAiUi> = settings.state
    private val _test = MutableStateFlow<ExternalTest>(ExternalTest.Idle)
    val test: StateFlow<ExternalTest> = _test.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    private var job: Job? = null

    init { viewModelScope.launch { settings.load() } }

    /** Asks the server for its model list: proves the address and key work, and offers the names to pick from. */
    fun testConnection(url: String, model: String, key: String) {
        val ep = settings.draft(url, model.ifBlank { "-" }, key)
        if (ep == null) { job?.cancel(); _test.value = ExternalTest.Failed("ext_url"); return }
        run(ep)
    }

    /**
     * Fetches the model list as soon as the address is valid, so the names appear without pressing anything. Sends no key that is
     * still being typed (a half-typed address could be someone else's server); a stored key goes only to the server it was saved for.
     */
    fun autoLoad(url: String) {
        val ep = settings.draft(url, "-", null)
        if (ep == null) { job?.cancel(); _test.value = ExternalTest.Idle; return }
        run(ep)
    }

    private fun run(ep: com.nathanhanapps.wayd.ai.ExternalEndpoint) {
        job?.cancel()
        _test.value = ExternalTest.Running
        job = viewModelScope.launch {
            _test.value = try {
                ExternalTest.Ok(client.models(ep))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExternalAiException) {
                ExternalTest.Failed(e.code)
            } catch (_: Exception) {
                ExternalTest.Failed("ext_network")
            }
        }
    }

    fun save(url: String, model: String, key: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = settings.save(url, model, key.takeIf { it.isNotBlank() })
        _saved.value = ok
        onResult(ok)
    }

    fun acknowledge(on: Boolean) = viewModelScope.launch { settings.setAcknowledged(on) }

    fun remove() = viewModelScope.launch { settings.clear(); _test.value = ExternalTest.Idle }

    val profiles = settings.profiles

    /** Saves the current (already saved) server and model as a named profile. */
    fun saveProfile(name: String, onResult: (Boolean) -> Unit) = viewModelScope.launch { onResult(settings.saveProfile(name)) }

    fun useProfile(id: String) = viewModelScope.launch { settings.useProfile(id); _test.value = ExternalTest.Idle }

    fun deleteProfile(id: String) = viewModelScope.launch { settings.deleteProfile(id) }
}
