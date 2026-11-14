package com.nathanhanapps.wayd.feature.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.AppTraffic
import com.nathanhanapps.wayd.analysis.CompanyRow
import com.nathanhanapps.wayd.analysis.CountryRow
import com.nathanhanapps.wayd.analysis.DailyReport
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.feature.analysis.ActivityHeatmap
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.DashboardHero
import com.nathanhanapps.wayd.feature.analysis.DashboardPanel
import com.nathanhanapps.wayd.feature.analysis.Dot
import com.nathanhanapps.wayd.feature.analysis.LegendItem
import com.nathanhanapps.wayd.feature.analysis.PercentRing
import com.nathanhanapps.wayd.feature.analysis.RankedBarRow
import com.nathanhanapps.wayd.feature.analysis.ShareBar
import com.nathanhanapps.wayd.feature.analysis.StatGrid
import com.nathanhanapps.wayd.feature.analysis.StatTile
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.categoryShareColor
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.analysis.regionTitle
import com.nathanhanapps.wayd.feature.analysis.severityColor
import com.nathanhanapps.wayd.feature.analysis.severityLabel

private val SEVERITY_ORDER = listOf(Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO)

/**
 * The report for one window, laid out like app detail (D-060): a headline panel, what needs attention, when it happened,
 * who caused it, where it went, and finally how complete the picture is. Rules decide every finding shown; nothing here comes
 * from the AI. Every bar and ring carries a text value, so colour is never the only signal.
 */
fun LazyListScope.insightsBody(
    r: DailyReport,
    labels: AppLabels,
    window: String,
    onOpenApp: (String, String) -> Unit,
    onOpenCompany: (String, String) -> Unit,
    showCountries: Boolean,
) {
    item(key = "summary") { SummaryCard(r) }
    item(key = "attention") { AttentionCard(r, labels, window, onOpenApp) }
    item(key = "activity") { ActivityCard(r, window) }
    item(key = "apps") { TopAppsCard(r, labels, window, onOpenApp) }
    item(key = "destinations") { DestinationsCard(r, window, onOpenCompany) }
    item(key = "background") { BackgroundCard(r, labels, window, onOpenApp) }
    if (showCountries) item(key = "countries") { CountryCard(r) }
    item(key = "coverage") { CoverageCard(r) }
}

@Composable
private fun SummaryCard(r: DailyReport) {
    val ctx = LocalContext.current
    val up = r.appTraffic.sumOf { it.bytesUp }
    val down = r.appTraffic.sumOf { it.bytesDown }
    DashboardHero(
        title = stringResource(R.string.rep_stat_traffic),
        total = fmtBytes(ctx, up + down),
        up = up, down = down,
        upText = "${stringResource(R.string.rep_sent)} ${fmtBytes(ctx, up)}",
        downText = "${stringResource(R.string.rep_received)} ${fmtBytes(ctx, down)}",
    ) {
        StatGrid(
            listOf(
                StatTile(r.appCount.toString(), stringResource(R.string.rep_stat_apps)),
                StatTile(r.domainCount.toString(), stringResource(R.string.rep_stat_domains)),
                StatTile(r.companyCount.toString(), stringResource(R.string.rep_stat_companies)),
                StatTile(r.trackerContacts.toString(), stringResource(R.string.rep_stat_trackers), if (r.trackerContacts > 0) MaterialTheme.colorScheme.error else null),
            ),
            columns = 4,
        )
    }
}

/**
 * Counts per severity, then one row per app with its severity pills. Finding sentences live on the app page, one tap away,
 * so this section stays short.
 */
@Composable
private fun AttentionCard(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit) {
    val all = r.alerts + r.others
    var expanded by remember { mutableStateOf(false) }
    val byApp = all.groupBy { it.pkg }.entries
        .sortedWith(compareBy<Map.Entry<String, List<com.nathanhanapps.wayd.core.db.Finding>>> { e -> e.value.minOf { SEVERITY_ORDER.indexOf(it.severity) } }.thenByDescending { it.value.size })
    DashboardPanel(stringResource(R.string.rep_attention), if (all.isEmpty()) null else stringResource(R.string.rep_attention_value, all.size, byApp.size)) {
        if (all.isEmpty()) {
            Text(stringResource(R.string.report_no_alerts), style = MaterialTheme.typography.bodyMedium)
            return@DashboardPanel
        }
        StatGrid(
            SEVERITY_ORDER.map { s ->
                val n = all.count { it.severity == s }
                StatTile(n.toString(), severityLabel(s), if (n > 0) severityColor(s) else MaterialTheme.colorScheme.outline)
            },
            columns = 4, labelLines = 1,
        )
        (if (expanded) byApp else byApp.take(5)).forEach { (pkg, findings) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpenApp(pkg, window) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(labels, pkg, size = 28)
                Text(labels.label(pkg), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SEVERITY_ORDER.forEach { s ->
                    val n = findings.count { it.severity == s }
                    if (n > 0) Badge("${severityLabel(s)} $n", severityColor(s))
                }
            }
        }
        if (byApp.size > 5) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) stringResource(R.string.rep_show_less) else stringResource(R.string.rep_show_all, byApp.size))
        }
    }
}

@Composable
private fun ActivityCard(r: DailyReport, window: String) {
    if (window == Windows.WEEK) {
        DashboardPanel(stringResource(R.string.heatmap_title)) { ActivityHeatmap(r.dailyActivity, internetOnly = false) }
        return
    }
    val max = r.hourlyBytes.maxOrNull() ?: 0L
    val peak = if (max > 0) r.hourlyBytes.indexOfFirst { it == max } else -1
    val ctx = LocalContext.current
    DashboardPanel(stringResource(R.string.rep_activity), if (peak >= 0) stringResource(R.string.rep_peak, peak, (peak + 1) % 24, fmtBytes(ctx, max)) else null) {
        if (peak < 0) {
            Text(stringResource(R.string.apps_empty_window), style = MaterialTheme.typography.bodyMedium)
            return@DashboardPanel
        }
        val bar = MaterialTheme.colorScheme.primary
        val peakBar = MaterialTheme.colorScheme.tertiary
        val grid = MaterialTheme.colorScheme.outlineVariant
        val active = r.hourlyBytes.count { it > 0 }
        val avg = if (active > 0) r.hourlyBytes.sum() / active else 0L
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / 24f
            val w = slot * 0.66f
            for (line in 0..2) {
                val y = (size.height - 1.dp.toPx()) * line / 2f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            for (h in 0 until 24) {
                val v = r.hourlyBytes[h]
                if (v <= 0) continue
                val x = h * slot + (slot - w) / 2
                val bh = (size.height * v / max).coerceAtLeast(3.dp.toPx())
                drawRoundRect(if (h == peak) peakBar else bar, Offset(x, size.height - bh), Size(w, bh), CornerRadius(3.dp.toPx()))
            }
            // Average of the active hours as a dashed reference line.
            val ay = size.height - size.height * avg / max
            drawLine(peakBar, Offset(0f, ay), Offset(size.width, ay), strokeWidth = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
        StatGrid(
            listOf(
                StatTile(active.toString(), stringResource(R.string.rep_active_hours)),
                StatTile(fmtBytes(ctx, avg), stringResource(R.string.rep_avg_hour)),
                StatTile("%02d:00".format(peak), stringResource(R.string.rep_peak_hour), MaterialTheme.colorScheme.tertiary),
            ),
            labelLines = 1,
        )
    }
}

/** What a ranked list is ordered by: data moved or number of connections. */
private enum class Rank { BYTES, CONNECTIONS }

@Composable
private fun RankPills(rank: Rank, onChange: (Rank) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(rank == Rank.BYTES, { onChange(Rank.BYTES) }, { Text(stringResource(R.string.rep_rank_bytes)) })
        FilterChip(rank == Rank.CONNECTIONS, { onChange(Rank.CONNECTIONS) }, { Text(stringResource(R.string.rep_rank_flows)) })
    }
}

/**
 * The first [limit] rows, then (while collapsed) one "Others" row summing the rest, and a Show all / Show less toggle.
 * Expanded, every row is shown individually.
 */
@Composable
private fun <T> CollapsibleRanking(items: List<T>, limit: Int, itemKey: (T) -> Any, row: @Composable (T) -> Unit, others: @Composable (List<T>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    (if (expanded) items else items.take(limit)).forEach { item -> key(itemKey(item)) { row(item) } }
    if (items.size > limit) {
        if (!expanded) others(items.drop(limit))
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) stringResource(R.string.rep_show_less) else stringResource(R.string.rep_show_all, items.size))
        }
    }
}

/** Neutral row for the summed remainder of a ranking; not clickable. */
@Composable
private fun OthersRow(count: Int, value: String, share: Float, caption: String?, leadingSize: Int?) {
    RankedBarRow(
        title = stringResource(R.string.rep_others, count),
        value = value,
        parts = listOf(share.coerceIn(0f, 1f), (1f - share).coerceAtLeast(0f)),
        colors = listOf(MaterialTheme.colorScheme.outline, Color.Transparent),
        caption = caption,
        leading = {
            if (leadingSize != null) DefaultAppIcon(leadingSize) else Dot(MaterialTheme.colorScheme.outline)
        },
    )
}

/** The system's default app icon (the Android robot), standing in for the apps summed into an "Others" row. */
@Composable
private fun DefaultAppIcon(size: Int) {
    val pm = LocalContext.current.packageManager
    val icon = remember(pm) { pm.defaultActivityIcon.toBitmap(96, 96).asImageBitmap() }
    androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)))
}

@Composable
private fun TopAppsCard(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit) {
    if (r.appTraffic.isEmpty()) return
    var rank by remember { mutableStateOf(Rank.BYTES) }
    val ctx = LocalContext.current
    val sent = MaterialTheme.colorScheme.primary
    val received = MaterialTheme.colorScheme.tertiary
    fun bytes(a: AppTraffic) = a.bytesUp + a.bytesDown
    DashboardPanel(stringResource(R.string.rep_top_apps), stringResource(R.string.rep_company_apps, r.appTraffic.size)) {
        val selectedRank = rank
        fun metric(a: AppTraffic) = if (selectedRank == Rank.BYTES) bytes(a) else a.flows.toLong()
        val sorted = r.appTraffic.sortedByDescending(::metric)
        val total = sorted.sumOf(::metric).coerceAtLeast(1L)
        val max = sorted.maxOf(::metric).coerceAtLeast(1L)
        RankPills(selectedRank) { rank = it }
        CollapsibleRanking(
            sorted, 6,
            itemKey = { it.pkg },
            row = { a ->
                val m = metric(a)
                val (parts, colors) = if (rank == Rank.BYTES) {
                    val up = a.bytesUp.toFloat() / max
                    val down = a.bytesDown.toFloat() / max
                    listOf(up, down, (1f - up - down).coerceAtLeast(0f)) to listOf(sent, received, Color.Transparent)
                } else listOf(m.toFloat() / max, 1f - m.toFloat() / max) to listOf(sent, Color.Transparent)
                RankedBarRow(
                    title = labels.label(a.pkg),
                    value = if (rank == Rank.BYTES) fmtBytes(ctx, m) else stringResource(R.string.detail_dashboard_connections, a.flows),
                    parts = parts, colors = colors,
                    caption = listOf(
                        stringResource(R.string.detail_dashboard_percent, (m * 100 / total).toInt()),
                        "↑${fmtBytes(ctx, a.bytesUp)} ↓${fmtBytes(ctx, a.bytesDown)}",
                        if (rank == Rank.BYTES) stringResource(R.string.detail_dashboard_connections, a.flows) else fmtBytes(ctx, bytes(a)),
                    ).joinToString(" · "),
                    leading = { AppIcon(labels, a.pkg, size = 28) },
                    onClick = { onOpenApp(a.pkg, window) },
                )
            },
            others = { rest ->
                val m = rest.sumOf(::metric)
                OthersRow(
                    rest.size,
                    if (rank == Rank.BYTES) fmtBytes(ctx, m) else stringResource(R.string.detail_dashboard_connections, m.toInt()),
                    m.toFloat() / max, stringResource(R.string.detail_dashboard_percent, (m * 100 / total).toInt()), 28,
                )
            },
        )
        if (rank == Rank.BYTES) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(sent, stringResource(R.string.rep_sent))
            LegendItem(received, stringResource(R.string.rep_received))
        }
    }
}

/** Category share as one proportional bar with a legend, then companies; both follow the data / connections pill. */
@Composable
private fun DestinationsCard(r: DailyReport, window: String, onOpenCompany: (String, String) -> Unit) {
    if (r.companies.isEmpty()) return
    var rank by remember { mutableStateOf(Rank.BYTES) }
    val ctx = LocalContext.current
    fun bytes(c: CompanyRow) = c.apps.sumOf { it.second }
    val connFmt = stringResource(R.string.detail_dashboard_connections)
    fun valueText(m: Long) = if (rank == Rank.BYTES) fmtBytes(ctx, m) else connFmt.format(m.toInt())
    DashboardPanel(stringResource(R.string.rep_by_category), stringResource(R.string.rep_company_count, r.companies.size)) {
        val selectedRank = rank
        fun metric(c: CompanyRow) = if (selectedRank == Rank.BYTES) bytes(c) else c.flows.toLong()
        val sortedCats = r.companies.groupBy { it.category }.mapValues { (_, v) -> v.sumOf(::metric) }.filter { it.value > 0 }.entries.sortedByDescending { it.value }
        val companies = r.companies.sortedByDescending(::metric)
        val total = companies.sumOf(::metric).coerceAtLeast(1L)
        val max = companies.maxOf(::metric).coerceAtLeast(1L)
        RankPills(selectedRank) { rank = it }
        if (sortedCats.isNotEmpty()) {
            val other = stringResource(R.string.rep_cat_other)
            ShareBar(sortedCats.map { (categoryLabel(it.key) ?: other) to it.value }, sortedCats.map { categoryShareColor(it.key) })
        }
        Text(stringResource(R.string.report_companies), style = MaterialTheme.typography.titleSmall)
        CollapsibleRanking(
            companies, 8,
            itemKey = { it.company to it.category },
            row = { c ->
                val m = metric(c)
                val color = categoryShareColor(c.category)
                RankedBarRow(
                    title = c.company,
                    value = valueText(m),
                    parts = listOf(m.toFloat() / max, 1f - m.toFloat() / max),
                    colors = listOf(color, Color.Transparent),
                    caption = listOfNotNull(
                        stringResource(R.string.detail_dashboard_percent, (m * 100 / total).toInt()),
                        categoryLabel(c.category),
                        stringResource(R.string.rep_company_apps, c.apps.size),
                        if (rank == Rank.BYTES) stringResource(R.string.detail_dashboard_connections, c.flows) else fmtBytes(ctx, bytes(c)),
                        "↑${fmtBytes(ctx, c.bytesUp)}",
                    ).joinToString(" · "),
                    leading = { Dot(color) },
                    onClick = { onOpenCompany(c.company, window) },
                )
            },
            others = { rest ->
                val m = rest.sumOf(::metric)
                OthersRow(rest.size, valueText(m), m.toFloat() / max, stringResource(R.string.detail_dashboard_percent, (m * 100 / total).toInt()), null)
            },
        )
    }
}

/** Screen-off activity ranked by screen-off data or connections; the caption also shows each app's own share. */
@Composable
private fun BackgroundCard(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit) {
    if (r.background.isEmpty()) return
    var rank by remember { mutableStateOf(Rank.BYTES) }
    val ctx = LocalContext.current
    val color = MaterialTheme.colorScheme.secondary
    val connFmt = stringResource(R.string.detail_dashboard_connections)
    fun offBytes(a: AppTraffic) = a.screenOffUp + a.screenOffDown
    fun valueText(m: Long) = if (rank == Rank.BYTES) fmtBytes(ctx, m) else connFmt.format(m.toInt())
    val count = r.background.count { if (rank == Rank.BYTES) offBytes(it) > 0 else it.screenOffFlows > 0 }
    DashboardPanel(stringResource(R.string.report_background), stringResource(R.string.rep_company_apps, count)) {
        val selectedRank = rank
        fun metric(a: AppTraffic) = if (selectedRank == Rank.BYTES) offBytes(a) else a.screenOffFlows.toLong()
        val apps = r.background.filter { metric(it) > 0 }.sortedByDescending(::metric)
        val max = apps.maxOfOrNull(::metric)?.coerceAtLeast(1L) ?: 1L
        RankPills(selectedRank) { rank = it }
        CollapsibleRanking(
            apps, 6,
            itemKey = { it.pkg },
            row = { a ->
                val share = if (rank == Rank.BYTES) offBytes(a).toFloat() / (a.bytesUp + a.bytesDown).coerceAtLeast(1L)
                else a.screenOffFlows.toFloat() / a.flows.coerceAtLeast(1)
                val pct = (share * 100).toInt()
                val bar = metric(a).toFloat() / max
                RankedBarRow(
                    title = labels.label(a.pkg),
                    value = valueText(metric(a)),
                    parts = listOf(bar, 1f - bar),
                    colors = listOf(color, Color.Transparent),
                    caption = if (rank == Rank.BYTES) {
                        listOf(stringResource(R.string.app_row_background, pct), connFmt.format(a.screenOffFlows)).joinToString(" · ")
                    } else {
                        listOf(stringResource(R.string.rep_bg_flows_share, pct), fmtBytes(ctx, offBytes(a))).joinToString(" · ")
                    },
                    leading = { AppIcon(labels, a.pkg, size = 28) },
                    onClick = { onOpenApp(a.pkg, window) },
                )
            },
            others = { rest ->
                val m = rest.sumOf(::metric)
                OthersRow(rest.size, valueText(m), m.toFloat() / max, null, 28)
            },
        )
    }
}

/** Needs the shared GeoIP database, so the caller only shows it when the Clash extension is installed. */
@Composable
private fun CountryCard(r: DailyReport) {
    val ctx = LocalContext.current
    var rank by remember { mutableStateOf(Rank.BYTES) }
    val flowsFmt = stringResource(R.string.report_flows)
    fun valueText(m: Long) = if (rank == Rank.BYTES) fmtBytes(ctx, m) else flowsFmt.format(m.toInt())
    DashboardPanel(stringResource(R.string.rep_countries)) {
        if (r.countries.isEmpty()) {
            Text(stringResource(R.string.rep_countries_empty), style = MaterialTheme.typography.bodyMedium)
            return@DashboardPanel
        }
        val selectedRank = rank
        fun metric(c: CountryRow) = if (selectedRank == Rank.BYTES) c.bytes else c.flows.toLong()
        RankPills(selectedRank) { rank = it }
        val rows = r.countries.sortedByDescending(::metric)
        val total = rows.sumOf(::metric).coerceAtLeast(1L)
        val max = rows.maxOf(::metric).coerceAtLeast(1L)
        CollapsibleRanking(
            rows, 8,
            itemKey = { it.code },
            row = { c ->
                val m = metric(c)
                RankedBarRow(
                    title = regionTitle(c.code),
                    value = valueText(m),
                    parts = listOf(m.toFloat() / max, 1f - m.toFloat() / max),
                    colors = listOf(MaterialTheme.colorScheme.secondary, Color.Transparent),
                    caption = listOf(
                        stringResource(R.string.detail_dashboard_percent, (m * 100 / total).toInt()),
                        if (rank == Rank.BYTES) flowsFmt.format(c.flows) else fmtBytes(ctx, c.bytes),
                    ).joinToString(" · "),
                )
            },
            others = { rest ->
                val m = rest.sumOf(::metric)
                OthersRow(rest.size, valueText(m), m.toFloat() / max, stringResource(R.string.detail_dashboard_percent, (m * 100 / total).toInt()), null)
            },
        )
    }
}

@Composable
private fun CoverageCard(r: DailyReport) {
    DashboardPanel(stringResource(R.string.report_coverage), stringResource(R.string.rep_cov_value, r.coverage.flows)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PercentRing(r.coverage.withPackagePct, stringResource(R.string.rep_cov_pkg), Modifier.weight(1f))
            PercentRing(r.coverage.withHostnamePct, stringResource(R.string.rep_cov_host), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
        }
        Text(
            stringResource(R.string.rep_cov_note, r.coverage.flows, r.coverage.measurableTcp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
