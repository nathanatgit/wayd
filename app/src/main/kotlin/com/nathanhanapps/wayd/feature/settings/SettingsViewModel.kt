package com.nathanhanapps.wayd.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    permissions: PermissionHistoryService,
) : ViewModel() {
    val shizuku: StateFlow<ShizukuState> = permissions.state

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
