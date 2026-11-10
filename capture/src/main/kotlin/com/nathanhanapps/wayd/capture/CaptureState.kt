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
    val routes: RouteStats = RouteStats(),
)

data class RouteStats(val direct: Long = 0, val proxied: Long = 0, val blocked: Long = 0, val errors: Long = 0) {
    fun add(kind: Int) = when (kind) {
        1 -> copy(direct = direct + 1)
        2 -> copy(proxied = proxied + 1)
        3 -> copy(blocked = blocked + 1)
        4 -> copy(errors = errors + 1)
        else -> this
    }
}

/** Process-wide VPN processing state; traffic-history recording is controlled separately. */
@Singleton
class CaptureState @Inject constructor() {
    private val live = LinkedHashMap<Long, LiveRoutingConnection>()
    private val _connections = MutableStateFlow<List<LiveRoutingConnection>>(emptyList())
    val connections = _connections.asStateFlow()
    private val _ui = MutableStateFlow(CaptureUi())
    val ui: StateFlow<CaptureUi> = _ui.asStateFlow()

    @Synchronized internal fun observe(event: NativeEvent) {
        when (event) {
            is NativeEvent.Open -> if (live.size < 256) live[event.flowId] = LiveRoutingConnection(event.flowId, event.dstIp.hostAddress.orEmpty(), event.dstPort)
            is NativeEvent.Progress -> live[event.flowId]?.let { live[event.flowId] = if (event.down) it.copy(bytesDown = event.total) else it.copy(bytesUp = event.total) }
            is NativeEvent.Close -> live.remove(event.flowId)
            else -> Unit
        }
    }
    @Synchronized internal fun publishConnections() { _connections.value = live.values.toList() }
    @Synchronized internal fun clearConnections() { live.clear(); _connections.value = emptyList() }
    internal fun set(phase: CapturePhase) = _ui.update { it.copy(phase = phase) }

    internal fun set(stats: CaptureStats) = _ui.update { it.copy(stats = stats) }
    internal fun setRoutes(stats: RouteStats) = _ui.update { it.copy(routes = stats) }

    internal fun reset(phase: CapturePhase) {
        clearConnections()
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

data class LiveRoutingConnection(val id: Long, val destination: String, val port: Int, val bytesUp: Long = 0, val bytesDown: Long = 0)
