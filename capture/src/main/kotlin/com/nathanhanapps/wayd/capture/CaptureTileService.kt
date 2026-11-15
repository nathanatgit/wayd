package com.nathanhanapps.wayd.capture

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that starts and stops the WAYD VPN. Starting needs the system VPN consent; when it has not been
 * granted yet the tile opens the app instead, where the user accepts it once.
 */
@AndroidEntryPoint
class CaptureTileService : TileService() {
    @Inject lateinit var state: CaptureState
    @Inject lateinit var controller: CaptureController
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        state.ui.onEach { render(it.phase) }.launchIn(s)
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        when (state.ui.value.phase) {
            CapturePhase.RUNNING, CapturePhase.STARTING -> controller.stop()
            else -> if (VpnService.prepare(this) == null) controller.start() else openApp()
        }
    }

    private fun render(phase: CapturePhase) {
        val tile = qsTile ?: return
        tile.state = when (phase) {
            CapturePhase.RUNNING -> Tile.STATE_ACTIVE
            CapturePhase.STARTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (phase == CapturePhase.STARTING) R.string.tile_starting else if (phase == CapturePhase.RUNNING) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(launch)
        }
    }
}
