package com.nathanhanapps.wayd.feature.apps

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.destinationLabel
import com.nathanhanapps.wayd.feature.analysis.findingTitle
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.analysis.insightText
import com.nathanhanapps.wayd.feature.analysis.severityColor
import com.nathanhanapps.wayd.feature.analysis.severityLabel
import com.nathanhanapps.wayd.feature.export.ExportButton
import com.nathanhanapps.wayd.feature.export.ExportScope
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.nathanhanapps.wayd.feature.analysis.DashboardHero
import com.nathanhanapps.wayd.feature.analysis.DashboardPanel
import com.nathanhanapps.wayd.feature.analysis.Dot
import com.nathanhanapps.wayd.feature.analysis.LegendItem
import com.nathanhanapps.wayd.feature.analysis.RankedBarRow
import com.nathanhanapps.wayd.feature.analysis.ShareBar
import com.nathanhanapps.wayd.feature.analysis.StatGrid
import com.nathanhanapps.wayd.feature.analysis.StatTile
import com.nathanhanapps.wayd.feature.analysis.categoryShareColor
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
                // AI assistance leads the page so it reads as the guide to the evidence below; rules still decide severity.
                if (vm.pkg != UNKNOWN_PKG) item(key = "ai") { com.nathanhanapps.wayd.feature.ai.AiSection() }
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
                                val week = vm.windowKey == Windows.WEEK
                                DashboardPanel(stringResource(if (week) R.string.heatmap_title else R.string.section_activity)) {
                                    if (week) ActivityHeatmap(r.dailyActivity) else HourlyChart(r.hourlyUp, r.hourlyDown)
                                }
                            }
                            item { TrafficInsights(r) }
                            item { Destinations(r, context) }
                            item {
                                DashboardPanel(stringResource(R.string.section_findings), r.findings.size.takeIf { it > 0 }?.toString()) {
                                    if (r.findings.isEmpty()) Text(stringResource(R.string.no_findings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    r.findings.forEach { f ->
                                        FindingRow(f, vm, context)
                                    }
                                }
                            }
                            item { DetailPanel { PermissionSection(vm) } }
                            item { AppBlockingSection() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailPanel(content: @Composable ColumnScope.() -> Unit) = DashboardPanel(content = content)

@Composable
private fun Summary(r: AppReport, context: Context) {
    DashboardHero(
        title = stringResource(R.string.detail_dashboard_title),
        total = fmtBytes(context, r.total),
        up = r.bytesUp, down = r.bytesDown,
        upText = "${stringResource(R.string.stat_up)} ${fmtBytes(context, r.bytesUp)}",
        downText = "${stringResource(R.string.stat_down)} ${fmtBytes(context, r.bytesDown)}",
        note = stringResource(R.string.detail_dashboard_scope),
    ) {
        StatGrid(
            listOf(
                StatTile(r.flows.toString(), stringResource(R.string.stat_flows)),
                StatTile(r.destinations.size.toString(), stringResource(R.string.detail_dashboard_destinations)),
                StatTile(
                    fmtBytes(context, r.screenOffBytes), stringResource(R.string.stat_screen_off),
                    progress = if (r.total > 0) r.screenOffBytes.toFloat() / r.total else 0f,
                ),
            ),
        )
    }
}

/** "What it did": each traffic insight becomes one number tile; the full sentence stays available to screen readers. */
@Composable
private fun TrafficInsights(r: AppReport) {
    if (r.insights.isEmpty()) {
        DetailPanel { Text(stringResource(R.string.detail_quiet), style = MaterialTheme.typography.bodyMedium) }
        return
    }
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val tiles = r.insights.map { i ->
        val pct = (i.percent / 100f).coerceIn(0f, 1f)
        val full = insightText(i)
        when (i.kind) {
            InsightKind.TOP_DESTINATION -> StatTile("${i.percent}%", stringResource(R.string.ins_tile_top, i.text), scheme.primary, pct, full)
            InsightKind.BACKGROUND_SHARE -> StatTile("${i.percent}%", stringResource(R.string.ins_tile_background, fmtBytes(ctx, i.number)), scheme.secondary, pct, full)
            InsightKind.TRACKERS -> StatTile(fmtBytes(ctx, i.number), stringResource(R.string.ins_tile_trackers, i.percent), scheme.error, null, full)
            InsightKind.UPLOAD_HEAVY -> StatTile("${i.percent}%", stringResource(R.string.ins_tile_upload, fmtBytes(ctx, i.number)), scheme.primary, pct, full)
            InsightKind.UNNAMED_DESTINATIONS -> StatTile(i.number.toString(), stringResource(R.string.ins_tile_unnamed, i.percent), null, null, full)
            InsightKind.LOCAL_NETWORK -> StatTile(i.number.toString(), stringResource(R.string.ins_tile_local, i.percent), null, null, full)
        }
    }
    DashboardPanel(stringResource(R.string.detail_summary_title)) { StatGrid(tiles, columns = 2) }
}

/** "Who it talks to": category share bar, then ranked rows with a sent/received bar scaled to the largest destination. */
@Composable
private fun Destinations(r: AppReport, context: Context) {
    var all by remember { mutableStateOf(false) }
    DashboardPanel(stringResource(R.string.section_destinations), stringResource(R.string.detail_dashboard_groups, r.destinations.size)) {
        if (r.destinations.isEmpty()) {
            Text(stringResource(R.string.detail_quiet), style = MaterialTheme.typography.bodyMedium)
            return@DashboardPanel
        }
        val byCat = r.destinations.groupBy { it.category?.takeIf { c -> c != com.nathanhanapps.wayd.core.model.TrackerCategory.UNKNOWN } }
            .mapValues { (_, v) -> v.sumOf { it.total } }.filter { it.value > 0 }.entries.sortedByDescending { it.value }
        if (byCat.isNotEmpty()) {
            val other = stringResource(R.string.rep_cat_other)
            ShareBar(byCat.map { (categoryLabel(it.key) ?: other) to it.value }, byCat.map { categoryShareColor(it.key) })
        }
        val max = r.destinations.maxOf { it.total }.coerceAtLeast(1L)
        val shown = if (all) r.destinations.take(30) else r.destinations.take(8)
        shown.forEach { d -> DestinationRow(d, context, r.total, max) }
        if (r.destinations.size > 8) TextButton(onClick = { all = !all }) {
            Text(if (all) stringResource(R.string.rep_show_less) else stringResource(R.string.rep_show_all, minOf(r.destinations.size, 30)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(MaterialTheme.colorScheme.primary, stringResource(R.string.stat_up))
            LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.stat_down))
        }
    }
}

@Composable
private fun DestinationRow(d: Destination, context: Context, total: Long, max: Long) {
    var open by remember(d.label, d.category) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val share = if (total > 0) (d.total * 100 / total).toInt() else 0
    val caption = listOfNotNull(
        stringResource(R.string.detail_dashboard_percent, share),
        categoryLabel(d.category),
        "↑${fmtBytes(context, d.bytesUp)} ↓${fmtBytes(context, d.bytesDown)}",
        stringResource(R.string.detail_dashboard_connections, d.flows),
        if (d.screenOffBytes > 0) stringResource(R.string.dest_screen_off, fmtBytes(context, d.screenOffBytes)) else null,
    ).joinToString(" · ")
    val up = d.bytesUp.toFloat() / max
    val down = d.bytesDown.toFloat() / max
    val hosts: (@Composable ColumnScope.() -> Unit)? = if (open && d.hosts.isNotEmpty()) {
        {
            d.hosts.forEach { h ->
                Text(h, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    } else null
    RankedBarRow(
        title = destinationLabel(d),
        value = fmtBytes(context, d.total),
        parts = listOf(up, down, (1f - up - down).coerceAtLeast(0f)),
        colors = listOf(scheme.primary, scheme.tertiary, Color.Transparent),
        caption = caption,
        leading = { Dot(categoryShareColor(d.category)) },
        onClick = { open = !open },
        extra = hosts,
    )
}

/** Findings retain the exact evidence and rule severity within individual disclosure cards. */
@Composable
private fun FindingRow(f: Finding, vm: AppDetailViewModel, context: Context) {
    var open by remember(f.evidenceJson) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { open = !open }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        run {
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
