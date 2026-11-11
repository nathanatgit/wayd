package com.nathanhanapps.wayd.feature.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.FlowMessageSummary
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.analysis.RelatedTcp
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.traffic.MessageItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class AppBlockingViewModel @Inject constructor(
    val blocking: BlockingRules,
    flows: FlowDao,
    private val messages: MessageDao,
    saved: SavedStateHandle,
) : ViewModel() {
    val pkg: String = saved["pkg"] ?: ""
    private val window = Windows.resolve(saved["win"] ?: Windows.TODAY)
    val history = flows.observeAppHistory(pkg, window.first, window.second)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messageSummaries = history.flatMapLatest { list -> messages.observeFlowSummaries(list.map { it.id }) }
        .map { list -> list.associateBy { it.flowId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    suspend fun preview(flowId: Long): List<Message> = withContext(Dispatchers.IO) { messages.previewForFlow(flowId, 50) }
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
    val summaries by vm.messageSummaries.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val undo by vm.undoId.collectAsStateWithLifecycle()
    var query by remember(vm.pkg) { mutableStateOf("") }
    var selected by remember(vm.pkg) { mutableStateOf<BlockTarget?>(null) }
    var expanded by rememberSaveable(vm.pkg) { mutableStateOf(false) }
    var openFlow by rememberSaveable(vm.pkg) { mutableStateOf<Long?>(null) }
    val rules = policy.rules.filter { it.pkg == vm.pkg }

    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(16.dp).sizeIn(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.block_history), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.report_flows, history.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    val groups = remember(filtered) { RelatedTcp.groups(filtered.take(30)) }
                    groups.forEachIndexed { index, group ->
                        val f = group.firstOrNull { it.id == openFlow } ?: group.first()
                        val t = target(f)
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val decryptedRecords = group.filter { summaries[it.id]?.decrypted == true }.map { it.id }.toSet()
                        HistoryConnection(f, summaries[f.id], openFlow == f.id, { openFlow = if (openFlow == f.id) null else f.id }, vm, group, decryptedRecords, { openFlow = it }) {
                            if (t != null) TextButton(onClick = { selected = t }, enabled = !busy) { Text(stringResource(R.string.history_block)) }
                        }
                    }
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

/** One connection expands locally. Only the expanded row loads bounded bodies; list markers use metadata only. */
@Composable
private fun HistoryConnection(
    f: Flow,
    summary: FlowMessageSummary?,
    open: Boolean,
    toggle: () -> Unit,
    vm: AppBlockingViewModel,
    related: List<Flow>,
    decryptedRecords: Set<Long>,
    selectRecord: (Long) -> Unit,
    block: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val decrypted = summary?.decrypted == true
    val containsDecrypted = decrypted || decryptedRecords.isNotEmpty()
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (containsDecrypted) scheme.tertiaryContainer else scheme.surfaceContainerLow,
        contentColor = if (containsDecrypted) scheme.onTertiaryContainer else scheme.onSurface,
    ) {
        Column {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = toggle).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.host ?: f.dstIp, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
                Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(f.startAt))} · ${f.proto} · ${f.dstIp}:${f.dstPort}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(TrafficArrow, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.stat_up) + " " + fmtBytes(ctx, f.bytesUp), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(TrafficArrow, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(16.dp).rotate(180f))
                        Text(stringResource(R.string.stat_down) + " " + fmtBytes(ctx, f.bytesDown), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
                    }
                }
                if (summary != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (decrypted) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(if (decrypted) R.string.history_decrypted else R.string.history_messages, summary.messageCount), style = MaterialTheme.typography.labelMedium)
                }
                if (related.size > 1) Text(stringResource(R.string.history_related, related.size), style = MaterialTheme.typography.labelMedium)
                if (containsDecrypted && !decrypted) Text(stringResource(R.string.history_related_decrypted), style = MaterialTheme.typography.labelMedium)
                val result = when (f.policyOutcome) {
                    "BLOCKED" -> R.string.block_outcome_blocked
                    "GATE_FAILED" -> R.string.block_outcome_error
                    "UNKNOWN_OWNER", "UNKNOWN_HOST" -> R.string.block_outcome_unknown
                    "PASSED_POLICY" -> R.string.block_outcome_passed
                    else -> R.string.block_outcome_legacy
                }
                Text(stringResource(result), style = MaterialTheme.typography.bodySmall, color = if (f.policyOutcome in listOf("BLOCKED", "GATE_FAILED")) scheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
            if (open) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (related.size > 1) {
                    Text(stringResource(R.string.history_related_note), style = MaterialTheme.typography.bodySmall)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        related.forEachIndexed { index, record ->
                            FilterChip(
                                selected = record.id == f.id, onClick = { selectRecord(record.id) }, shape = MaterialTheme.shapes.extraLarge,
                                leadingIcon = if (record.id in decryptedRecords) ({ Icon(Icons.Default.Check, contentDescription = stringResource(R.string.inspector_title), modifier = Modifier.size(16.dp)) }) else null,
                                label = { Text(stringResource(R.string.history_tcp_record, index + 1, DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(record.startAt)))) },
                            )
                        }
                    }
                }
                block()
                if (summary == null || summary.messageCount == 0) {
                    Text(stringResource(R.string.cd_not_decrypted), style = MaterialTheme.typography.bodySmall)
                } else {
                    val messages by produceState<List<Message>?>(null, f.id, summary.messageCount) { value = vm.preview(f.id) }
                    val list = messages
                    if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                        Text(stringResource(R.string.inspector_private), style = MaterialTheme.typography.bodySmall)
                        list.forEach { m -> androidx.compose.runtime.key(m.id) { MessageItem(m) } }
                        if (summary.messageCount > list.size) Text(stringResource(R.string.history_message_limit, list.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Direction is invariant under RTL; the icon uses the row's theme tint. */
private val TrafficArrow = ImageVector.Builder("TrafficUp", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 4f)
        lineTo(4f, 12f)
        lineTo(5.4f, 13.4f)
        lineTo(11f, 7.8f)
        lineTo(11f, 20f)
        lineTo(13f, 20f)
        lineTo(13f, 7.8f)
        lineTo(18.6f, 13.4f)
        lineTo(20f, 12f)
        close()
    }
}.build()
