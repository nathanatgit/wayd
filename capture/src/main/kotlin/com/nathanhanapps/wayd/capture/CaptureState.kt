package com.nathanhanapps.wayd.capture

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class CapturePhase { IDLE, STARTING, RUNNING, ERROR }

data class CaptureUi(
    val phase: CapturePhase = CapturePhase.IDLE,
    val stats: CaptureStats = CaptureStats(0, 0, 0, 0),
)

/** Process-wide VPN processing state; traffic-history recording is controlled separately. */
@Singleton
class CaptureState @Inject constructor() {
    private val _ui = MutableStateFlow(CaptureUi())
    val ui: StateFlow<CaptureUi> = _ui.asStateFlow()

    internal fun set(phase: CapturePhase) = _ui.update { it.copy(phase = phase) }

    internal fun set(stats: CaptureStats) = _ui.update { it.copy(stats = stats) }

    internal fun reset(phase: CapturePhase) {
        _ui.value = CaptureUi(phase = phase)
    }

    private inline fun MutableStateFlow<CaptureUi>.update(f: (CaptureUi) -> CaptureUi) {
        while (true) {
            val cur = value
            if (compareAndSet(cur, f(cur))) return
        }
    }
}

/** UI-facing entry point. VPN consent (`VpnService.prepare`) is handled by the caller before [start]. */
@Singleton
class CaptureController @Inject constructor(@ApplicationContext private val context: Context) {
    /** Debug builds only (see [WaydVpnService.EXTRA_EXCLUDE]); null in normal use. */
    @Volatile var debugExclude: String? = null

    fun start() {
        val i = intent(WaydVpnService.ACTION_START)
        debugExclude?.let { i.putExtra(WaydVpnService.EXTRA_EXCLUDE, it) }
        ContextCompat.startForegroundService(context, i)
    }

    fun stop() {
        context.startService(intent(WaydVpnService.ACTION_STOP))
    }

    private fun intent(action: String) = Intent(context, WaydVpnService::class.java).setAction(action)
}
