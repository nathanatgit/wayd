package com.nathanhanapps.wayd.feature.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.DailyReport
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.categoryColor
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.analysis.severityColor
import com.nathanhanapps.wayd.feature.analysis.severityLabel

/**
 * Visual summary at the top of a day report. Everything is derived from [DailyReport]; every bar carries a text value so colour is
 * never the only signal. Rules decide the findings shown here, nothing on this card comes from the AI.
 */
@Composable
fun ReportDashboard(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit, showCountries: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTiles(r)
        RiskCard(r)
        ActivityCard(r)
        TopAppsCard(r, labels, window, onOpenApp)
        CategoryCard(r)
        if (showCountries) CountryCard(r)
        CoverageCard(r)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatTiles(r: DailyReport) {
    val total = r.appTraffic.sumOf { it.bytesUp + it.bytesDown }
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(Modifier.weight(1f), r.appCount.toString(), stringResource(R.string.rep_stat_apps))
            Tile(Modifier.weight(1f), r.domainCount.toString(), stringResource(R.string.rep_stat_domains))
            Tile(Modifier.weight(1f), fmtBytes(ctx, total), stringResource(R.string.rep_stat_traffic))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(Modifier.weight(1f), r.companyCount.toString(), stringResource(R.string.rep_stat_companies))
            Tile(Modifier.weight(1f), r.trackerContacts.toString(), stringResource(R.string.rep_stat_trackers), if (r.trackerContacts > 0) MaterialTheme.colorScheme.tertiary else null)
            Tile(Modifier.weight(1f), r.alerts.size.toString(), stringResource(R.string.rep_stat_alerts), if (r.alerts.isNotEmpty()) MaterialTheme.colorScheme.error else null)
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, value: String, label: String, accent: Color? = null) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Findings by severity as one segmented bar plus a labelled legend. */
@Composable
private fun RiskCard(r: DailyReport) {
    val all = r.alerts + r.others
    SectionCard(stringResource(R.string.rep_risk)) {
        if (all.isEmpty()) {
            Text(stringResource(R.string.report_no_alerts), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val order = listOf(Severity.HIGH, Severity.MEDIUM, Severity.LOW, Severity.INFO)
        val counts = order.map { s -> s to all.count { it.severity == s } }.filter { it.second > 0 }
        val colors = counts.map { severityColor(it.first) }
        SegmentBar(counts.map { it.second.toFloat() }, colors)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            counts.forEachIndexed { i, (s, n) -> LegendItem(colors[i], "${severityLabel(s)} $n") }
        }
    }
}

@Composable
private fun ActivityCard(r: DailyReport) {
    val max = r.hourlyBytes.maxOrNull() ?: 0L
    val peak = if (max > 0) r.hourlyBytes.indexOfFirst { it == max } else -1
    val ctx = LocalContext.current
    SectionCard(stringResource(R.string.rep_activity)) {
        if (peak < 0) {
            Text(stringResource(R.string.apps_empty_window), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val bar = MaterialTheme.colorScheme.primary
        val peakBar = MaterialTheme.colorScheme.tertiary
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            val slot = size.width / 24f
            val w = slot * 0.7f
            for (h in 0 until 24) {
                val v = r.hourlyBytes[h]
                if (v <= 0) continue
                val bh = (size.height * v / max).coerceAtLeast(3f)
                drawRoundRect(if (h == peak) peakBar else bar, Offset(h * slot + (slot - w) / 2, size.height - bh), Size(w, bh), CornerRadius(3f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
        Text(stringResource(R.string.rep_peak, peak, (peak + 1) % 24, fmtBytes(ctx, max)), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TopAppsCard(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit) {
    val rows = r.appTraffic.take(6)
    if (rows.isEmpty()) return
    val max = rows.maxOf { it.bytesUp + it.bytesDown }.coerceAtLeast(1L)
    val ctx = LocalContext.current
    SectionCard(stringResource(R.string.rep_top_apps)) {
        rows.forEach { a ->
            val total = a.bytesUp + a.bytesDown
            Column(Modifier.fillMaxWidth().clickable { onOpenApp(a.pkg, window) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppIcon(labels, a.pkg, size = 24)
                    Text(labels.label(a.pkg), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtBytes(ctx, total), style = MaterialTheme.typography.labelMedium)
                }
                // Sent (up) is drawn first so uploads stand out against the downloads that dominate most apps.
                val up = a.bytesUp.toFloat() / max
                val down = a.bytesDown.toFloat() / max
                SegmentBar(listOf(up, down, (1f - up - down).coerceAtLeast(0f)), listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, Color.Transparent), height = 6)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(MaterialTheme.colorScheme.primary, stringResource(R.string.rep_sent))
            LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.rep_received))
        }
    }
}

/** Share of company traffic by category (ads, analytics, CDN…), so "how much of this day went to trackers" is one glance. */
@Composable
private fun CategoryCard(r: DailyReport) {
    val byCat = r.companies.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { c -> c.apps.sumOf { it.second } } }.filter { it.value > 0 }
    val total = byCat.values.sum()
    if (total <= 0) return
    val sorted = byCat.entries.sortedByDescending { it.value }
    SectionCard(stringResource(R.string.rep_by_category)) {
        val colors = sorted.map { catColor(it.key) }
        SegmentBar(sorted.map { it.value.toFloat() }, colors)
        sorted.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                Text(categoryLabel(e.key) ?: stringResource(R.string.rep_cat_other), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${e.value * 100 / total}%", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun catColor(c: TrackerCategory): Color = when (c) {
    TrackerCategory.ADS, TrackerCategory.ANALYTICS, TrackerCategory.CRASH -> categoryColor(c)
    TrackerCategory.CDN, TrackerCategory.FIRST_PARTY -> MaterialTheme.colorScheme.primary
    TrackerCategory.OS, TrackerCategory.CARRIER, TrackerCategory.PUSH -> MaterialTheme.colorScheme.secondary
    TrackerCategory.UNKNOWN -> MaterialTheme.colorScheme.outline
}

/** Needs the shared GeoIP database, so the caller only shows it when the Clash extension is installed. Private and unresolved addresses are not counted. */
@Composable
private fun CountryCard(r: DailyReport) {
    val ctx = LocalContext.current
    SectionCard(stringResource(R.string.rep_countries)) {
        if (r.countries.isEmpty()) {
            Text(stringResource(R.string.rep_countries_empty), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val rows = r.countries.take(8)
        val max = rows.maxOf { it.bytes }.coerceAtLeast(1L)
        rows.forEach { c ->
            val name = java.util.Locale("", c.code).getDisplayCountry(androidx.compose.ui.platform.LocalLocale.current.platformLocale).ifBlank { c.code }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${flagEmoji(c.code)} $name", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtBytes(ctx, c.bytes) + " · " + stringResource(R.string.report_flows, c.flows), style = MaterialTheme.typography.labelMedium)
                }
                val share = c.bytes.toFloat() / max
                SegmentBar(listOf(share, 1f - share), listOf(MaterialTheme.colorScheme.secondary, Color.Transparent), height = 6)
            }
        }
        Text(stringResource(R.string.rep_countries_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun flagEmoji(code: String): String = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

@Composable
private fun CoverageCard(r: DailyReport) {
    SectionCard(stringResource(R.string.report_coverage)) {
        Meter(stringResource(R.string.rep_cov_pkg), r.coverage.withPackagePct)
        Meter(stringResource(R.string.rep_cov_host), r.coverage.withHostnamePct)
        Text(stringResource(R.string.rep_cov_note, r.coverage.flows, r.coverage.measurableTcp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Meter(label: String, pct: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$pct%", style = MaterialTheme.typography.labelLarge)
        }
        SegmentBar(listOf(pct.toFloat(), (100 - pct).coerceAtLeast(0).toFloat()), listOf(MaterialTheme.colorScheme.primary, Color.Transparent), height = 8)
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Horizontal bar split proportionally; [Color.Transparent] segments become the track. */
@Composable
private fun SegmentBar(parts: List<Float>, colors: List<Color>, height: Int = 12) {
    val total = parts.sum().coerceAtLeast(0.0001f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50)).background(track)) {
        parts.forEachIndexed { i, p ->
            if (p > 0f && colors[i] != Color.Transparent) Box(Modifier.weight(p / total).height(height.dp).background(colors[i]))
            else if (p > 0f) Box(Modifier.weight(p / total).height(height.dp))
        }
    }
}
