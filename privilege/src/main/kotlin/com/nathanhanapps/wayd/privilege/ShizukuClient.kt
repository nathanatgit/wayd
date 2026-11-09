package com.nathanhanapps.wayd.privilege

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuState {
    /** The Shizuku app is not installed. */
    NOT_INSTALLED,

    /** Installed, but its service is not running (start it from the Shizuku app). */
    NOT_RUNNING,

    /** Running; WAYD has not been granted access yet (the user must allow it in Shizuku's dialog). */
    NEEDS_PERMISSION,
    READY,
}

/** App-side access to the privileged [IPrivilegedService]. Privileges are opt-in and only ever the fixed calls in the AIDL. */
class ShizukuClient(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var service: IPrivilegedService? = null
    private var connecting: CompletableDeferred<IPrivilegedService>? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, PrivilegedService::class.java.name))
            .processNameSuffix("privileged")
            .daemon(false)
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = IPrivilegedService.Stub.asInterface(binder)
            service = s
            connecting?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener { service = null; refresh() }
            Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        }
        refresh()
    }

    fun refresh() {
        _state.value = computeState()
    }

    private fun computeState(): ShizukuState {
        if (!installed()) return ShizukuState.NOT_INSTALLED
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return ShizukuState.NOT_RUNNING
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) ShizukuState.READY else ShizukuState.NEEDS_PERMISSION
    }

    private fun installed() = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PKG, 0) }.isSuccess

    /** Shows Shizuku's own permission dialog. Does nothing unless the service is running. */
    fun requestPermission() {
        if (_state.value == ShizukuState.NEEDS_PERMISSION) runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** `dumpsys appops --history --package <pkg>` output, or null if privileged access is unavailable or fails. */
    suspend fun appOpsHistory(pkg: String): String? = call { it.appOpsHistory(pkg) }

    /** One history run for several apps, reduced to [ops]; null if privileged access is unavailable or the call fails. */
    suspend fun appOpsHistoryFor(pkgs: Collection<String>, ops: Collection<String>): String? =
        call { it.appOpsHistoryFor(pkgs.joinToString(","), ops.joinToString(",")) }

    /** `cmd appops get <pkg>` output, or null. */
    suspend fun appOpsGet(pkg: String): String? = call { it.appOpsGet(pkg) }

    private suspend fun call(block: (IPrivilegedService) -> String): String? {
        refresh()
        if (_state.value != ShizukuState.READY) return null
        val s = connect() ?: return null
        return runCatching { block(s) }.getOrNull()
    }

    private suspend fun connect(): IPrivilegedService? = mutex.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val d = CompletableDeferred<IPrivilegedService>()
        connecting = d
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { return null }
        withTimeoutOrNull(8_000) { d.await() }
    }

    private companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4711
    }
}
