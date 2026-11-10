package com.nathanhanapps.wayd.feature.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.BlockTargets
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.feature.analysis.Windows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class AppBlockingViewModel @Inject constructor(
    val blocking: BlockingRules,
    flows: FlowDao,
    saved: SavedStateHandle,
) : ViewModel() {
    val pkg: String = saved["pkg"] ?: ""
    private val window = Windows.resolve(saved["win"] ?: Windows.TODAY)
    val history = flows.observeAppHistory(pkg, window.first, window.second)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow<Int?>(null)
    val undoId = MutableStateFlow<Long?>(null)

    init { viewModelScope.launch { runCatching { blocking.refresh() }.onFailure { notice.value = R.string.block_error } } }

    fun add(target: BlockTarget, persistent: Boolean, done: () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            runCatching { blocking.add(pkg, target.kind, target.value, persistent) }
                .onSuccess { undoId.value = it; notice.value = R.string.block_saved; done() }
                .onFailure { notice.value = R.string.block_error }
            busy.value = false
        }
    }

    fun remove(id: Long) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            runCatching { blocking.remove(id) }.onSuccess {
                if (undoId.value == id) undoId.value = null
                notice.value = R.string.block_removed
            }.onFailure { notice.value = R.string.block_error }
            busy.value = false
        }
    }
}

data class BlockTarget(val kind: String, val value: String)

private fun target(flow: Flow): BlockTarget? {
    // DNS-to-shared-IP correlation is retained evidence, not an authoritative live host predicate.
    val host = flow.host?.takeIf { flow.hostSource == HostSource.SNI || flow.hostSource == HostSource.HTTP_HOST }
        ?.let { if (flow.hostSource == HostSource.HTTP_HOST) BlockTargets.httpHost(it) else BlockTargets.host(it) }
    return if (host != null) BlockTarget("HOST", host) else BlockTargets.ip(flow.dstIp)?.let { BlockTarget("IP", it) }
}

@Composable
fun AppBlockingSection(vm: AppBlockingViewModel = hiltViewModel()) {
    val history by vm.history.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val undo by vm.undoId.collectAsStateWithLifecycle()
    var query by remember(vm.pkg) { mutableStateOf("") }
    var selected by remember(vm.pkg) { mutableStateOf<BlockTarget?>(null) }
    val rules = policy.rules.filter { it.pkg == vm.pkg }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.block_history), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.block_coverage), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { selected = BlockTarget("APP", "*") }, enabled = !busy) { Text(stringResource(R.string.block_app)) }
        notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        undo?.let { id -> TextButton(onClick = { vm.remove(id) }, enabled = !busy) { Text(stringResource(R.string.block_undo)) } }
        if (!policy.running) Text(stringResource(R.string.block_vpn_off), style = MaterialTheme.typography.bodySmall)
        rules.forEach { r ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(if (r.matchKind == "APP") stringResource(R.string.block_app) else r.host.orEmpty())
                    Text(
                        stringResource(if (r.id < 0) R.string.block_session else R.string.block_persistent) + " · " +
                            stringResource(if (r.id in policy.activeIds) R.string.block_active else R.string.block_inactive),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = { vm.remove(r.id) }, enabled = !busy) { Text(stringResource(R.string.block_remove)) }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(253) }, label = { Text(stringResource(R.string.block_search)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        val filtered = history.filter { query.isBlank() || it.host.orEmpty().contains(query, true) || it.dstIp.contains(query, true) }
        Text(stringResource(R.string.block_history_limit, filtered.size), style = MaterialTheme.typography.labelSmall)
        filtered.take(30).forEach { f ->
            val t = target(f)
            Card(Modifier.fillMaxWidth().clickable(enabled = t != null && !busy) { selected = t }) {
                Column(Modifier.padding(10.dp)) {
                    Text(f.host ?: f.dstIp, style = MaterialTheme.typography.bodyMedium)
                    Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(f.startAt))} · ${f.proto} · ${f.dstIp}:${f.dstPort}", style = MaterialTheme.typography.labelSmall)
                    val result = when (f.policyOutcome) {
                        "BLOCKED" -> R.string.block_outcome_blocked
                        "GATE_FAILED" -> R.string.block_outcome_error
                        "UNKNOWN_OWNER", "UNKNOWN_HOST" -> R.string.block_outcome_unknown
                        "PASSED_POLICY" -> R.string.block_outcome_passed
                        else -> R.string.block_outcome_legacy
                    }
                    Text(stringResource(result), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    selected?.let { t ->
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(stringResource(R.string.block_confirm)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.pkg)
                    Text(if (t.kind == "APP") stringResource(R.string.block_app) else "${t.kind}: ${t.value}")
                    Text(stringResource(R.string.block_consent))
                    if (t.kind == "IP") Text(stringResource(R.string.block_ip_warning))
                    if (t.kind == "APP") Text(stringResource(R.string.block_app_warning))
                    notice?.takeIf { it == R.string.block_error }?.let { Text(stringResource(it)) }
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = { vm.add(t, false) { selected = null } }, enabled = !busy && policy.running) { Text(stringResource(R.string.block_this_time)) }
                    TextButton(onClick = { vm.add(t, true) { selected = null } }, enabled = !busy) { Text(stringResource(R.string.block_forever)) }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }, enabled = !busy) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
}
