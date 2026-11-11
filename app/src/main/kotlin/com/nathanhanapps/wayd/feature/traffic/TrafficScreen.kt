package com.nathanhanapps.wayd.feature.traffic

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.ai.AiHints
import com.nathanhanapps.wayd.ai.Purpose
import com.nathanhanapps.wayd.analysis.ConnectionLabel
import com.nathanhanapps.wayd.analysis.ConnectionLabels
import com.nathanhanapps.wayd.core.db.AiAnalysisDao
import com.nathanhanapps.wayd.core.db.FindingDao
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.AnalysisService
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.ai.purposeLabel
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.apps.AppsScreen
import com.nathanhanapps.wayd.feature.export.ExportButton
import com.nathanhanapps.wayd.feature.export.ExportRange
import com.nathanhanapps.wayd.feature.export.ExportScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject

/** Traffic tab: per-app summary or the raw connection history, one segmented switch apart. */
@Composable
fun TrafficScreen(initialView: String, onOpenApp: (pkg: String, window: String) -> Unit, onOpenConnection: (Long) -> Unit) {
    var view by rememberSaveable { mutableIntStateOf(if (initialView == VIEW_CONNECTIONS) 1 else 0) }
    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tab_traffic), style = MaterialTheme.typography.headlineMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(R.string.traffic_by_app, R.string.connections_title).forEachIndexed { i, label ->
                    SegmentedButton(selected = view == i, onClick = { view = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                }
            }
        }
        if (view == 0) AppsScreen(onOpenApp) else ConnectionsList(onOpenConnection)
    }
}

const val VIEW_CONNECTIONS = "connections"

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val flows: FlowDao,
    val labels: AppLabels,
    private val analysis: AnalysisService,
    private val findings: FindingDao,
    private val aiAnalyses: AiAnalysisDao,
) : ViewModel() {
    val items = MutableStateFlow<List<Flow>>(emptyList())

    /** Rule label per connection (D-051); every loaded connection has one. */
    val marks = MutableStateFlow<Map<Long, ConnectionLabel>>(emptyMap())

    /** What stored AI analyses guessed a connection is for; a hint next to the label, never the label itself. */
    val hints = MutableStateFlow<Map<Long, Purpose>>(emptyMap())
    private var labelJob: Job? = null
    val loading = MutableStateFlow(false)
    val end = MutableStateFlow(false)
    private var job: Job? = null
    private var seenMaxId = -1L

    /** Reloads the first page only if something new was saved since the last load. */
    fun refreshIfChanged() {
        viewModelScope.launch {
            val id = flows.maxId()
            if (id != seenMaxId) { seenMaxId = id; reload() }
        }
    }

    private fun reload() {
        job?.cancel()
        end.value = false
        job = viewModelScope.launch {
            loading.value = true
            val page = flows.page(Long.MAX_VALUE, Long.MAX_VALUE, PAGE)
            items.value = page
            end.value = page.size < PAGE
            loading.value = false
            relabel()
        }
    }

    /**
     * Runs the rules for every day the loaded connections span (cached per day, the same findings Insights shows), then labels
     * each connection from those findings and its tracker category, and reads the AI hints from stored analyses.
     */
    private fun relabel() {
        labelJob?.cancel()
        labelJob = viewModelScope.launch {
            val list = items.value
            if (list.isEmpty()) { marks.value = emptyMap(); hints.value = emptyMap(); return@launch }
            val zone = ZoneId.systemDefault()
            val days = list.map { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }.toSortedSet()
            for (day in days) runCatching { analysis.analyse(day.toString()) }
            val start = Windows.resolve(days.first().toString()).first
            val end = Windows.resolve(days.last().toString()).second
            marks.value = ConnectionLabels.of(list, findings.between(start, end))
            hints.value = AiHints.flowPurposes(aiAnalyses.recent(200))
        }
    }

    fun more() {
        if (loading.value || end.value) return
        val last = items.value.lastOrNull() ?: return
        job = viewModelScope.launch {
            loading.value = true
            val page = flows.page(last.startAt, last.id, PAGE)
            items.value = items.value + page
            end.value = page.size < PAGE
            loading.value = false
            relabel()
        }
    }


    private companion object { const val PAGE = 100 }
}

@Composable
private fun ConnectionsList(onOpenConnection: (Long) -> Unit, vm: ConnectionsViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val end by vm.end.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val hints by vm.hints.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshIfChanged() }
    val shown = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) items else items.filter { f ->
            f.host?.contains(q, ignoreCase = true) == true || f.pkg.contains(q, ignoreCase = true) || f.dstIp.contains(q) ||
                vm.labels.label(f.pkg).contains(q, ignoreCase = true)
        }
    }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 10 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) vm.more() }
    val is24 = DateFormat.is24HourFormat(context)
    val timeFormat = remember(is24) { java.text.SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", java.util.Locale.getDefault()) }
    val dayFormat = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.connections_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.connections_count, shown.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExportButton(ExportScope.Capture(ExportRange.SESSION), stringResource(R.string.connections_export))
            }
        }
        if (shown.isEmpty()) item { Text(stringResource(R.string.flows_empty), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge) }
        var lastDay: String? = null
        shown.forEachIndexed { index, f ->
            val day = dayFormat.format(Date(f.startAt))
            if (day != lastDay) {
                lastDay = day
                item(key = "d$day") { Text(day, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            }
            item(key = f.id) { ConnectionRow(f, vm.labels, timeFormat.format(Date(f.startAt)), marks[f.id], hints[f.id]) { onOpenConnection(f.id) } }
            if (index == shown.lastIndex && !end) item { TextButton(onClick = vm::more, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connections_more)) } }
        }
    }
}

@Composable
private fun ConnectionRow(f: Flow, labels: AppLabels, time: String, mark: ConnectionLabel?, hint: Purpose?, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val blocked = stringResource(R.string.connections_blocked)
        AppIcon(labels, f.pkg, size = 36)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(f.host ?: f.dstIp.let { if (':' in it) "[$it]" else it }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (mark != null) Badge(connectionLabelText(mark), connectionLabelColor(mark))
                if (hint != null) AiHintBadge(hint)
            }
            Text(
                listOfNotNull(labels.label(f.pkg), "${f.proto.name} ${f.dstPort}", f.country, f.policyOutcome?.takeIf { it == "BLOCKED" }?.let { blocked }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(time, style = MaterialTheme.typography.labelMedium)
            Text("↑${fmtBytes(context, f.bytesUp)} ↓${fmtBytes(context, f.bytesDown)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun connectionLabelText(l: ConnectionLabel): String = stringResource(
    when (l) {
        ConnectionLabel.DANGER -> R.string.conn_label_danger
        ConnectionLabel.WARN -> R.string.conn_label_warn
        ConnectionLabel.SENSITIVE -> R.string.conn_label_sensitive
        ConnectionLabel.AD -> R.string.conn_label_ad
        ConnectionLabel.NORMAL -> R.string.conn_label_normal
    },
)

/** Colour is never the only signal: the badge always carries its text. */
@Composable
fun connectionLabelColor(l: ConnectionLabel) = when (l) {
    ConnectionLabel.DANGER -> MaterialTheme.colorScheme.error
    ConnectionLabel.WARN -> MaterialTheme.colorScheme.tertiary
    ConnectionLabel.SENSITIVE -> MaterialTheme.colorScheme.primary
    ConnectionLabel.AD -> MaterialTheme.colorScheme.secondary
    ConnectionLabel.NORMAL -> MaterialTheme.colorScheme.outline
}

/** The AI's guess, drawn outlined and neutral so it never reads as a verdict (AGENTS.md rule 2). */
@Composable
fun AiHintBadge(p: Purpose) {
    Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface) {
        Text(
            stringResource(R.string.conn_ai_hint, purposeLabel(p)), Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}
