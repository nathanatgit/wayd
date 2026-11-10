package com.nathanhanapps.wayd.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.ai.AiProvider
import com.nathanhanapps.wayd.ai.ExternalAiSettings
import com.nathanhanapps.wayd.ai.ExternalAiUi
import com.nathanhanapps.wayd.ai.ModelManager
import com.nathanhanapps.wayd.core.db.DbHealth
import com.nathanhanapps.wayd.feature.analysis.PermissionHistoryService
import com.nathanhanapps.wayd.privilege.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DbStatus {
    data object Checking : DbStatus
    data class Encrypted(val cipherVersion: String) : DbStatus
    data object Unencrypted : DbStatus
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val health: DbHealth,
    private val rootProbe: RootProbe,
    private val models: ModelManager,
    external: ExternalAiSettings,
    permissions: PermissionHistoryService,
) : ViewModel() {
    val shizuku: StateFlow<ShizukuState> = permissions.state
    val ai: StateFlow<ExternalAiUi> = external.state

    /** Short summary for the AI row: the provider in use and its model. The templates take the model name as `%s`. */
    fun aiSummary(ext: ExternalAiUi, none: String, onDevice: String, external: String): String = when {
        ext.provider == AiProvider.EXTERNAL && ext.usable -> external.format(ext.model)
        else -> models.activeModel()?.first?.let { onDevice.format(it.displayName) } ?: none
    }

    private val _root = MutableStateFlow(rootProbe.last())
    val root: StateFlow<RootState> = _root.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun checkRoot() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            _root.value = rootProbe.check()
            _checking.value = false
        }
    }

    private val _db = MutableStateFlow<DbStatus>(DbStatus.Checking)
    val db: StateFlow<DbStatus> = _db.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _db.value = health.cipherVersion()?.let { DbStatus.Encrypted(it) } ?: DbStatus.Unencrypted
        }
    }
}
