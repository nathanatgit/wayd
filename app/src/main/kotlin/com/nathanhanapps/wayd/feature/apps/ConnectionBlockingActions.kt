package com.nathanhanapps.wayd.feature.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The target comes only from the selected stored flow, through the same helper as app history. Never from an AI answer. */
@HiltViewModel
class ConnectionBlockingViewModel @Inject constructor(val blocking: BlockingRules, flows: FlowDao, saved: SavedStateHandle) : ViewModel() {
    val flow = MutableStateFlow<Flow?>(null)
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow<Int?>(null)
    val undo = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            flow.value = flows.byId(saved["id"] ?: 0L)
            runCatching { blocking.refresh() }.onFailure { notice.value = R.string.block_error }
        }
    }

    fun add(persistent: Boolean, done: () -> Unit) {
        val f = flow.value ?: return
        val target = blockTarget(f) ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                undo.value = blocking.add(f.pkg, target.kind, target.value, persistent)
                notice.value = R.string.block_saved
                done()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { notice.value = R.string.block_error }
            finally { busy.value = false }
        }
    }

    fun remove() {
        val id = undo.value ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { blocking.remove(id); undo.value = null; notice.value = R.string.block_removed }
            catch (e: CancellationException) { throw e } catch (_: Exception) { notice.value = R.string.block_error }
            finally { busy.value = false }
        }
    }
}

@Composable
fun ConnectionBlockingActions(vm: ConnectionBlockingViewModel = hiltViewModel()) {
    val f by vm.flow.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val flow = f ?: return
    if (flow.pkg == "?" || flow.pkg.startsWith("uid:")) return
    val target = blockTarget(flow) ?: return
    var confirm by remember(flow.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { confirm = true }, enabled = !busy) { Text(stringResource(R.string.history_block)) }
        notice?.let { Text(stringResource(it)) }
        if (undo != null) TextButton(onClick = vm::remove, enabled = !busy) { Text(stringResource(R.string.block_undo)) }
        if (!policy.running) Text(stringResource(R.string.block_vpn_off))
    }
    if (confirm) AlertDialog(
        onDismissRequest = { if (!busy) confirm = false },
        title = { Text(stringResource(R.string.block_confirm)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(flow.pkg)
                Text("${target.kind}: ${target.value}")
                Text(stringResource(R.string.block_consent))
                if (target.kind == "IP") Text(stringResource(R.string.block_ip_warning))
                notice?.takeIf { it == R.string.block_error }?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { vm.add(false) { confirm = false } }, enabled = !busy && policy.running) { Text(stringResource(R.string.block_this_time)) }
                TextButton(onClick = { vm.add(true) { confirm = false } }, enabled = !busy) { Text(stringResource(R.string.block_forever)) }
            }
        },
        dismissButton = { TextButton(onClick = { confirm = false }, enabled = !busy) { Text(stringResource(R.string.block_cancel)) } },
    )
}
