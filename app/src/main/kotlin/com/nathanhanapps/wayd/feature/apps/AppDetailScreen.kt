package com.nathanhanapps.wayd.feature.apps

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.AppReport
import com.nathanhanapps.wayd.analysis.Destination
import com.nathanhanapps.wayd.analysis.InsightKind
import com.nathanhanapps.wayd.analysis.UNKNOWN_PKG
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.feature.analysis.AppDetailViewModel
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.Evidence
import com.nathanhanapps.wayd.feature.analysis.HourlyChart
import com.nathanhanapps.wayd.feature.analysis.ActivityHeatmap
import com.nathanhanapps.wayd.feature.analysis.Loadable
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.analysis.categoryColor
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.destinationLabel
import com.nathanhanapps.wayd.feature.analysis.findingTitle
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.analysis.insightText
import com.nathanhanapps.wayd.feature.analysis.severityColor
import com.nathanhanapps.wayd.feature.analysis.severityLabel
import com.nathanhanapps.wayd.feature.export.ExportButton
import com.nathanhanapps.wayd.feature.export.ExportScope
import java.text.DateFormat
import java.util.Date

/** App identity stays visible; the report and its actions form one scrollable, themed page. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AppDetailScreen(onBack: () -> Unit, onOpenMessages: (pkg: String, window: String) -> Unit, onOpenScanner: (String) -> Unit, onSelectWindow: (String) -> Unit, vm: AppDetailViewModel = hiltViewModel()) {
    val state by vm.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val name = if (vm.pkg == UNKNOWN_PKG) stringResource(R.string.app_unknown) else vm.labels.label(vm.pkg)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        Column {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                    AppIcon(vm.labels, vm.pkg, 32)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(vm.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    FlowRow(modifier = Modifier.widthIn(max = 190.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                        OutlinedButton(onClick = { onOpenScanner(vm.pkg) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(stringResource(R.string.detail_dashboard_scan), style = MaterialTheme.typography.labelMedium)
                        }
                        ExportButton(ExportScope.App(vm.pkg, vm.windowKey), stringResource(R.string.connections_export))
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Windows.TODAY to R.string.window_today, Windows.YESTERDAY to R.string.window_yesterday, Windows.WEEK to R.string.window_week).forEach { (key, label) ->
                            FilterChip(selected = vm.windowKey == key, onClick = { if (key != vm.windowKey) onSelectWindow(key) }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                when (val s = state) {
                    Loadable.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is Loadable.Ready -> {
                        val r = s.value
                        if (r == null) item { DetailPanel { Text(stringResource(R.string.detail_quiet), style = MaterialTheme.typography.bodyMedium) } }
                        else {
                            item { Summary(r, context) }
                            item {
                                DetailPanel {
                                    if (vm.windowKey == Windows.WEEK) {
                                        SectionTitle(stringResource(R.string.heatmap_title))
                                        ActivityHeatmap(r.dailyActivity)
                                    } else {
                                        SectionTitle(stringResource(R.string.section_activity))
                                        HourlyChart(r.hourlyUp, r.hourlyDown)
                                    }
                                }
                            }
                            item { TrafficInsights(r) }
                            item {
                                DetailPanel {
                                    SectionTitle(stringResource(R.string.section_destinations))
                                    r.destinations.take(30).forEach { d ->
                                        DestinationRow(d, context, r.total)
                                    }
                                }
                            }
                            item {
                                DetailPanel {
                                    SectionTitle(stringResource(R.string.section_findings))
                                    if (r.findings.isEmpty()) Text(stringResource(R.string.no_findings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    r.findings.forEach { f ->
                                        FindingRow(f, vm, context)
                                    }
                                }
                            }
                            item { DetailPanel { PermissionSection(vm) } }
                            item { AppBlockingSection() }
                            item { DetailPanel { com.nathanhanapps.wayd.feature.ai.AiSection() } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Summary(r: AppReport, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.detail_dashboard_title), style = MaterialTheme.typography.titleMedium)
                        Text(fmtBytes(context, r.total), style = MaterialTheme.typography.displaySmall)
                    }
                    val upColor = MaterialTheme.colorScheme.primary
                    val downColor = MaterialTheme.colorScheme.tertiary
                    val emptyColor = MaterialTheme.colorScheme.outlineVariant
                    Canvas(Modifier.size(72.dp)) {
                        val stroke = Stroke(width = 10.dp.toPx())
                        drawArc(emptyColor, 0f, 360f, false, style = stroke)
                        if (r.total > 0) {
                            val sentAngle = 360f * (r.bytesUp.toDouble() / r.total).toFloat()
                            drawArc(upColor, -90f, sentAngle, false, style = stroke)
                            drawArc(downColor, -90f + sentAngle, 360f - sentAngle, false, style = stroke)
                        }
                    }
                }
                Text(stringResource(R.string.detail_dashboard_scope), style = MaterialTheme.typography.bodySmall)
                listOf(
                    listOf(R.string.stat_up to "↑ ${fmtBytes(context, r.bytesUp)}", R.string.stat_down to "↓ ${fmtBytes(context, r.bytesDown)}"),
                    listOf(R.string.stat_flows to r.flows.toString(), R.string.detail_dashboard_destinations to r.destinations.size.toString()),
                ).forEach { metrics ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        metrics.forEach { (label, value) ->
                            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(value, style = MaterialTheme.typography.titleLarge, color = when (label) {
                                        R.string.stat_up -> MaterialTheme.colorScheme.primary
                                        R.string.stat_down -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    })
                                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficInsights(r: AppReport) {
    if (r.insights.isEmpty()) DetailPanel { Text(stringResource(R.string.detail_quiet), style = MaterialTheme.typography.bodyMedium) }
    else DetailPanel {
        SectionTitle(stringResource(R.string.detail_summary_title))
        r.insights.forEach { insight ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(insightText(insight), style = MaterialTheme.typography.bodyMedium)
                    if (insight.kind in setOf(InsightKind.TOP_DESTINATION, InsightKind.BACKGROUND_SHARE, InsightKind.UPLOAD_HEAVY)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(progress = { (insight.percent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                            DashboardPill(stringResource(R.string.detail_dashboard_percent, insight.percent))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardPill(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DestinationRow(d: Destination, context: Context, total: Long) {
    var open by remember(d.label, d.category) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open },
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(destinationLabel(d), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { if (total > 0) (d.total.toFloat() / total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.weight(1f))
                Text(fmtBytes(context, d.total), style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                categoryLabel(d.category)?.let { Badge(it, categoryColor(d.category)) }
                DashboardPill("↑ ${fmtBytes(context, d.bytesUp)}")
                DashboardPill("↓ ${fmtBytes(context, d.bytesDown)}")
                DashboardPill(stringResource(R.string.detail_dashboard_connections, d.flows))
                if (d.screenOffBytes > 0) DashboardPill(stringResource(R.string.dest_screen_off, fmtBytes(context, d.screenOffBytes)))
            }
            if (open) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.hosts.forEach { DashboardPill(it) }
                }
            }
        }
    }
}


/** Findings retain the exact evidence and rule severity within individual disclosure cards. */
@Composable
private fun FindingRow(f: Finding, vm: AppDetailViewModel, context: Context) {
    var open by remember(f.evidenceJson) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(severityLabel(f.severity), severityColor(f.severity))
                Text(findingTitle(f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                val evidence by produceState(Evidence(emptyList(), emptyList()), f.evidenceJson) { value = vm.evidence(f.evidenceJson) }
                Text(f.ruleId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.evidence_title), style = MaterialTheme.typography.labelMedium)
                val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)
                evidence.permissions.forEach { p ->
                    Text("${fmt.format(Date(p.ts))}  ${p.op} ×${p.count}  (${p.source.removePrefix("appops:")})", style = MaterialTheme.typography.bodySmall)
                }
                evidence.messages.forEach { m ->
                    Text("${fmt.format(Date(m.ts))}  ${m.method.orEmpty()} ${m.url.orEmpty()}  ${m.dir.name}", style = MaterialTheme.typography.bodySmall)
                }
                evidence.flows.forEach { e ->
                    Text("${fmt.format(Date(e.startAt))}  ${e.host ?: e.dstIp}:${e.dstPort}  ${e.country.orEmpty()}  ${e.proto.name}  ↑${fmtBytes(context, e.bytesUp)} ↓${fmtBytes(context, e.bytesDown)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
