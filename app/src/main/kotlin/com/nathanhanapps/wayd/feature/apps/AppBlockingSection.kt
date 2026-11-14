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
import androidx.compose.runtime.LaunchedEffect
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
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.LabelViewModel
import com.nathanhanapps.wayd.feature.analysis.LabelContext
import com.nathanhanapps.wayd.feature.analysis.ItemLabelMarkers
import com.nathanhanapps.wayd.feature.analysis.ItemLabelDetails
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
    val windowKey: String = saved["win"] ?: Windows.TODAY
    private val window = Windows.resolve(windowKey)
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

internal fun blockTarget(flow: Flow): BlockTarget? {
    // DNS-to-shared-IP correlation is retained evidence, not an authoritative live host predicate.
    val host = flow.host?.takeIf { flow.hostSource == HostSource.SNI || flow.hostSource == HostSource.HTTP_HOST }
        ?.let { if (flow.hostSource == HostSource.HTTP_HOST) BlockTargets.httpHost(it) else BlockTargets.host(it) }
    return if (host != null) BlockTarget("HOST", host) else BlockTargets.ip(flow.dstIp)?.let { BlockTarget("IP", it) }
}

/**
 * Connection history, organised by destination host (D-063). Each host row carries its labels (role, attention, related
 * permissions/components, AI opinion) and a Block button; records and evidence open on demand. "AI check all" classifies every
 * host in a few chunked requests. Passed-policy noise is not shown; only blocked or failed outcomes are.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AppBlockingSection(vm: AppBlockingViewModel = hiltViewModel(), labelVm: LabelViewModel = hiltViewModel()) {
    val history by vm.history.collectAsStateWithLifecycle()
    val labelContext by labelVm.context.collectAsStateWithLifecycle()
    LaunchedEffect(vm.pkg, vm.windowKey) { labelVm.select(vm.pkg, vm.windowKey) }
    val summaries by vm.messageSummaries.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val undo by vm.undoId.collectAsStateWithLifecycle()
    val progress by labelVm.historyProgress.collectAsStateWithLifecycle()
    val historyNotice by labelVm.historyNotice.collectAsStateWithLifecycle()
    var query by remember(vm.pkg) { mutableStateOf("") }
    var selected by remember(vm.pkg) { mutableStateOf<BlockTarget?>(null) }
    var expanded by rememberSaveable(vm.pkg) { mutableStateOf(false) }
    var openHost by rememberSaveable(vm.pkg) { mutableStateOf<String?>(null) }
    var all by rememberSaveable(vm.pkg) { mutableStateOf(false) }
    val rules = policy.rules.filter { it.pkg == vm.pkg }
    val blockedHosts = remember(rules) { rules.filter { it.matchKind != "APP" }.mapNotNull { it.host }.toSet() }

    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(16.dp).sizeIn(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.block_history), style = MaterialTheme.typography.titleMedium)
                    val hosts = labelContext?.hosts
                    Text(
                        if (hosts == null) stringResource(R.string.report_flows, history.size)
                        else stringResource(R.string.history_hosts_summary, hosts.size, history.size, hosts.count { it.attention >= com.nathanhanapps.wayd.core.model.Attention.REVIEW }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // AI check for the whole history leads the panel, in the shared AI card.
                    com.nathanhanapps.wayd.feature.ai.AiAssistCard(stringResource(R.string.history_ai_title)) {
                        val p = progress
                        if (p != null) {
                            val phase by labelVm.phase.collectAsStateWithLifecycle()
                            com.nathanhanapps.wayd.feature.ai.PhaseNotice(phase)
                            if (p.second > 0) {
                                Text(stringResource(R.string.history_ai_progress, minOf(p.first + 1, p.second), p.second), style = MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth())
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = labelVm::cancelHistory) { Text(stringResource(R.string.ai_cancel)) }
                        } else {
                            Text(stringResource(R.string.history_ai_body), style = MaterialTheme.typography.bodySmall)
                            androidx.compose.material3.FilledTonalButton(onClick = labelVm::checkHistory, enabled = labelContext?.hosts?.isNotEmpty() == true) {
                                Text(stringResource(R.string.history_ai_check))
                            }
                        }
                        historyNotice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    }
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
                    val ctx = labelContext
                    if (ctx == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                        val hosts = remember(ctx, query) {
                            ctx.hosts.filter { a -> query.isBlank() || a.summary.host.orEmpty().contains(query, true) || a.records.any { it.dstIp.contains(query, true) } }
                        }
                        Text(stringResource(R.string.history_hosts_count, hosts.size), style = MaterialTheme.typography.labelSmall)
                        val shown = if (all) hosts.take(150) else hosts.take(12)
                        shown.forEach { a ->
                            val key = a.summary.subject
                            val target = remember(a) { (a.records.firstOrNull { it.hostSource == HostSource.SNI || it.hostSource == HostSource.HTTP_HOST } ?: a.records.first()).let(::blockTarget) }
                            HostRow(
                                a, ctx, labelVm, vm, summaries, open = openHost == key, toggle = { openHost = if (openHost == key) null else key },
                                ruleActive = a.blocked || (target?.value != null && target.value in blockedHosts),
                                onBlock = target?.let { t -> { selected = t } }, enabled = !busy,
                            )
                        }
                        if (hosts.size > 12) TextButton(onClick = { all = !all }) {
                            Text(if (all) stringResource(R.string.rep_show_less) else stringResource(R.string.rep_show_all, minOf(hosts.size, 150)))
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

/** One destination host: labels and a Block button up front; evidence, suggestions and its records only when opened. */
@Composable
private fun HostRow(
    a: com.nathanhanapps.wayd.analysis.HostAssessment,
    ctx: LabelContext,
    labelVm: LabelViewModel,
    vm: AppBlockingViewModel,
    summaries: Map<Long, FlowMessageSummary>,
    open: Boolean,
    toggle: () -> Unit,
    ruleActive: Boolean,
    onBlock: (() -> Unit)?,
    enabled: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val s = a.summary
    val ref = "f:${a.records.first().id}"
    val decrypted = a.records.any { summaries[it.id]?.decrypted == true }
    val total = s.bytesUp + s.bytesDown
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (decrypted) scheme.tertiaryContainer else scheme.surfaceContainer,
        contentColor = if (decrypted) scheme.onTertiaryContainer else scheme.onSurface,
    ) {
        Column {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = toggle).padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.host ?: a.records.first().dstIp, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    when {
                        ruleActive -> Badge(stringResource(R.string.history_blocked), scheme.error)
                        onBlock != null -> TextButton(onClick = onBlock, enabled = enabled) { Text(stringResource(R.string.history_block_short)) }
                    }
                    Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                }
                ItemLabelMarkers(ctx, ref)
                Text(
                    listOfNotNull(
                        stringResource(R.string.history_host_stats, s.flows, fmtBytes(context, s.bytesUp), fmtBytes(context, s.bytesDown)),
                        if (total > 0 && s.screenOffBytes > 0) stringResource(R.string.history_off_share, (s.screenOffBytes * 100 / total).toInt()) else null,
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(a.lastAt)),
                        if (decrypted) stringResource(R.string.history_decrypted_short) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                ctx.historyNote(a)?.let { n ->
                    Text(
                        stringResource(R.string.history_ai_note, com.nathanhanapps.wayd.feature.analysis.roleText(n.role), com.nathanhanapps.wayd.feature.analysis.adviceText(n.advice)) +
                            if (n.note.isNotBlank()) " — ${n.note}" else "",
                        style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (open) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemLabelDetails(ctx, ref, labelVm)
                Text(stringResource(R.string.history_records, a.records.size), style = MaterialTheme.typography.labelLarge)
                var more by remember(s.subject) { mutableStateOf(false) }
                val records = remember(a) { a.records.sortedByDescending { it.startAt } }
                (if (more) records.take(60) else records.take(5)).forEach { f -> androidx.compose.runtime.key(f.id) { RecordRow(f, summaries[f.id], vm) } }
                if (records.size > 5) TextButton(onClick = { more = !more }) {
                    Text(if (more) stringResource(R.string.rep_show_less) else stringResource(R.string.rep_show_all, minOf(records.size, 60)))
                }
            }
        }
    }
}

/** One record inside a host: a single line; blocked/failed outcomes are called out, decrypted messages open on tap. */
@Composable
private fun RecordRow(f: Flow, summary: FlowMessageSummary?, vm: AppBlockingViewModel) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var open by remember(f.id) { mutableStateOf(false) }
    val count = summary?.messageCount ?: 0
    Column(Modifier.fillMaxWidth().then(if (count > 0) Modifier.clickable(role = Role.Button) { open = !open } else Modifier).padding(vertical = 2.dp)) {
        Text(
            "${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(f.startAt))} · ${f.proto} ${f.dstIp}:${f.dstPort} · ↑${fmtBytes(ctx, f.bytesUp)} ↓${fmtBytes(ctx, f.bytesDown)}" +
                if (!f.screenOn) " · " + stringResource(R.string.history_screen_off) else "",
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        when (f.policyOutcome) {
            "BLOCKED" -> Text(stringResource(if (f.blockRuleId == com.nathanhanapps.wayd.capture.LIST_RULE_ID) R.string.block_outcome_list else R.string.block_outcome_blocked), style = MaterialTheme.typography.labelSmall, color = scheme.error)
            "GATE_FAILED" -> Text(stringResource(R.string.block_outcome_error), style = MaterialTheme.typography.labelSmall, color = scheme.error)
        }
        if (summary != null && count > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (summary.decrypted) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(if (summary.decrypted) R.string.history_decrypted else R.string.history_messages, count), style = MaterialTheme.typography.labelSmall)
        }
        if (open && count > 0) {
            val messages by produceState<List<Message>?>(null, f.id, count) { value = vm.preview(f.id) }
            val list = messages
            if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                Text(stringResource(R.string.inspector_private), style = MaterialTheme.typography.bodySmall)
                list.forEach { m -> androidx.compose.runtime.key(m.id) { MessageItem(m) } }
                if (count > list.size) Text(stringResource(R.string.history_message_limit, list.size), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
