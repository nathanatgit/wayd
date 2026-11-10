package com.nathanhanapps.wayd.feature.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.CapturePhase
import com.nathanhanapps.wayd.capture.CaptureState
import com.nathanhanapps.wayd.capture.RoutingSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoutingViewModel @Inject constructor(val settings: RoutingSettings, val capture: CaptureState) : ViewModel() {
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    fun refresh() = action { settings.refresh() }
    fun enable(value: Boolean) = action { settings.setEnabled(value) }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; failed.value = false
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { failed.value = true } finally { busy.value = false }
        }
    }
}

/** Compact host entry; profile management belongs to the optional extension. */
@Composable
fun RoutingSection(vm: RoutingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by vm.settings.state.collectAsStateWithLifecycle()
    val capture by vm.capture.ui.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val stopped = capture.phase in setOf(CapturePhase.IDLE, CapturePhase.ERROR)
    LaunchedEffect(Unit) { vm.refresh() }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashboard_clash))
            Text(stringResource(R.string.clash_boundary))
            Row {
                Text(stringResource(R.string.routing_enable))
                Switch(state.enabled, vm::enable, enabled = stopped && !busy && state.available && !state.damaged)
            }
            TextButton(onClick = { com.nathanhanapps.wayd.feature.extensions.ClashExtension.intent(context)?.let(context::startActivity) }) {
                Text(stringResource(R.string.clash_open_extension))
            }
            if (failed || state.damaged) Text(stringResource(R.string.clash_error))
            Text(stringResource(R.string.routing_outcomes, capture.routes.direct, capture.routes.proxied, capture.routes.blocked, capture.routes.errors))
        }
    }
}
