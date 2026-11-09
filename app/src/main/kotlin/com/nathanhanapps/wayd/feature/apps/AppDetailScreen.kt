package com.nathanhanapps.wayd.feature.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.AppReport
import com.nathanhanapps.wayd.analysis.Destination
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.feature.analysis.AppDetailViewModel
import com.nathanhanapps.wayd.feature.analysis.Evidence
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.HourlyChart
import com.nathanhanapps.wayd.feature.analysis.Loadable
import com.nathanhanapps.wayd.feature.analysis.categoryColor
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.findingTitle
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.analysis.insightText
import com.nathanhanapps.wayd.feature.analysis.severityColor
import com.nathanhanapps.wayd.feature.analysis.severityLabel
import com.nathanhanapps.wayd.analysis.UNKNOWN_PKG
import java.text.DateFormat
import java.util.Date

/** What one app did in the chosen window: summary sentences, activity by hour, who it talks to, findings. */
@Composable
fun AppDetailScreen(onBack: () -> Unit, onOpenMessages: (pkg: String, window: String) -> Unit, vm: AppDetailViewModel = hiltViewModel()) {
    val state by vm.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val name = if (vm.pkg == UNKNOWN_PKG) stringResource(R.string.app_unknown) else vm.labels.label(vm.pkg)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            AppIcon(vm.labels, vm.pkg, 36)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(vm.pkg, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        com.nathanhanapps.wayd.feature.export.ExportButton(stringResource(R.string.export_app), vm::exportName, Modifier.fillMaxWidth()) { vm.export(it) }
        when (val s = state) {
            Loadable.Loading -> Text("…")
            is Loadable.Ready -> {
                val r = s.value
                if (r == null) Text(stringResource(R.string.detail_quiet))
                else Detail(r, vm, context, onOpenMessages)
            }
        }
    }
}

@Composable
private fun Detail(r: AppReport, vm: AppDetailViewModel, context: android.content.Context, onOpenMessages: (String, String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.detail_summary_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.app_row_stats, fmtBytes(context, r.bytesUp), fmtBytes(context, r.bytesDown), r.flows),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (r.insights.isEmpty()) {
            item { Text(stringResource(R.string.detail_quiet), style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(r.insights) { i -> Text("• " + insightText(i), style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            Text(stringResource(R.string.section_activity), style = MaterialTheme.typography.titleMedium)
            HourlyChart(r.hourlyUp, r.hourlyDown)
        }
        item { Text(stringResource(R.string.section_destinations), style = MaterialTheme.typography.titleMedium) }
        items(r.destinations.take(30), key = { if (it.unnamed) "#unnamed" else it.label + it.category }) { d -> DestinationRow(d, context) }

        item { Text(stringResource(R.string.section_findings), style = MaterialTheme.typography.titleMedium) }
        if (r.findings.isEmpty()) item { Text(stringResource(R.string.no_findings), style = MaterialTheme.typography.bodyMedium) }
        items(r.findings) { f -> FindingRow(f, vm, context) }

        item { PermissionSection(vm) }

        item {
            val count by vm.decryptedCount.collectAsStateWithLifecycle()
            if (count > 0) {
                OutlinedButton(onClick = { onOpenMessages(vm.pkg, vm.windowKey) }) { Text(stringResource(R.string.inspector_open, count)) }
            }
        }

        item { com.nathanhanapps.wayd.feature.ai.AiSection() }
    }
}

/** A destination without a hostname is shown by its real address (IPv4 or IPv6), plus how many other addresses it groups. */
@Composable
private fun unnamedLabel(d: Destination): String {
    val first = d.hosts.firstOrNull() ?: return stringResource(R.string.dest_unnamed, d.addressCount)
    return if (d.addressCount > 1) stringResource(R.string.dest_unnamed_more, first, d.addressCount - 1) else first
}

@Composable
private fun DestinationRow(d: Destination, context: android.content.Context) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (d.unnamed) unnamedLabel(d) else d.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            categoryLabel(d.category)?.let { Badge(it, categoryColor(d.category)) }
        }
        Text(
            stringResource(R.string.dest_stats, fmtBytes(context, d.bytesUp), fmtBytes(context, d.bytesDown), d.flows) +
                if (d.screenOffBytes > 0) " · " + stringResource(R.string.dest_screen_off, fmtBytes(context, d.screenOffBytes)) else "",
            style = MaterialTheme.typography.labelMedium,
        )
        if (open && d.hosts.isNotEmpty()) Text(d.hosts.joinToString("\n"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** Tapping a finding expands the exact connections that triggered it ("each finding opens its evidence"). */
@Composable
private fun FindingRow(f: Finding, vm: AppDetailViewModel, context: android.content.Context) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(severityLabel(f.severity), severityColor(f.severity))
                Text(f.ruleId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(findingTitle(f), style = MaterialTheme.typography.bodyMedium)
            if (open) {
                val evidence by produceState(Evidence(emptyList(), emptyList()), f.evidenceJson) { value = vm.evidence(f.evidenceJson) }
                Text(stringResource(R.string.evidence_title), style = MaterialTheme.typography.labelMedium)
                val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)
                evidence.permissions.forEach { p ->
                    Text(
                        "${fmt.format(Date(p.ts))}  ${p.op} ×${p.count}  (${p.source.removePrefix("appops:")})",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                evidence.messages.forEach { m ->
                    Text(
                        "${fmt.format(Date(m.ts))}  ${m.method.orEmpty()} ${m.url.orEmpty()}  ${m.dir.name}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                evidence.flows.forEach { e ->
                    Text(
                        "${fmt.format(Date(e.startAt))}  ${e.host ?: e.dstIp}:${e.dstPort}  ${e.proto.name}  ↑${fmtBytes(context, e.bytesUp)} ↓${fmtBytes(context, e.bytesDown)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
