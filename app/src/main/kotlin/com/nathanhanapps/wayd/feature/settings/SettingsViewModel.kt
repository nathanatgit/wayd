package com.nathanhanapps.wayd.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.core.db.DbHealth
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
class SettingsViewModel @Inject constructor(private val health: DbHealth) : ViewModel() {
    private val _db = MutableStateFlow<DbStatus>(DbStatus.Checking)
    val db: StateFlow<DbStatus> = _db.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _db.value = health.cipherVersion()?.let { DbStatus.Encrypted(it) } ?: DbStatus.Unencrypted
        }
    }
}
