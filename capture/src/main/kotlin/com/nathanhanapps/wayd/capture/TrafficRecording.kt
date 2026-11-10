package com.nathanhanapps.wayd.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Recording is opt-in and independent of VPN enforcement. A mode change invalidates open captures. */
@Singleton
class TrafficRecording @Inject constructor() {
    data class Window(val enabled: Boolean, val generation: Long)

    @Volatile var window = Window(false, 0)
        private set
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()

    @Synchronized fun setEnabled(enabled: Boolean) {
        if (window.enabled == enabled) return
        window = Window(enabled, window.generation + 1)
        _enabled.value = enabled
    }
}
