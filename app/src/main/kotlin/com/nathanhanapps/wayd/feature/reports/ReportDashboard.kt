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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.categoryColor
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.findingTitle
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.analysis.severityColor
import com.nathanhanapps.wayd.feature.analysis.severityLabel

/**
 * The report for one window, ordered by what a person acts on first: the summary, what needs attention, when it happened,
 * who caused it, where it went, and finally how complete the picture is. Rules decide every finding shown; nothing here comes
 * from the AI. Every bar carries a text value, so colour is never the only signal.
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
    item(key = "activity") { ActivityCard(r) }
    item(key = "apps") { TopAppsCard(r, labels, window, onOpenApp) }
    item(key = "destinations") { DestinationsCard(r, window, onOpenCompany) }
    item(key = "background") { BackgroundCard(r, labels, window, onOpenApp) }
    if (showCountries) item(key = "countries") { CountryCard(r) }
    item(key = "coverage") { CoverageCard(r) }
}

/** Flat section: a header line (title, optional data value on the right) above its content, separated by a divider. */
@Composable
private fun SectionCard(title: String, value: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.HorizontalDivider(Modifier.padding(bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (value != null) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun SummaryCard(r: DailyReport) {
    val ctx = LocalContext.current
    val up = r.appTraffic.sumOf { it.bytesUp }
    val down = r.appTraffic.sumOf { it.bytesDown }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(fmtBytes(ctx, up + down), style = MaterialTheme.typography.displaySmall, color = scheme.primary)
        // Sent vs received as one bar: uploads are what leaves the phone, so they are drawn first.
        SegmentBar(listOf(up.toFloat(), down.toFloat()), listOf(scheme.primary, scheme.tertiary))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(scheme.primary, stringResource(R.string.rep_sent) + " " + fmtBytes(ctx, up))
            LegendItem(scheme.tertiary, stringResource(R.string.rep_received) + " " + fmtBytes(ctx, down))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniStat(r.appCount.toString(), stringResource(R.string.rep_stat_apps))
            MiniStat(r.domainCount.toString(), stringResource(R.string.rep_stat_domains))
            MiniStat(r.companyCount.toString(), stringResource(R.string.rep_stat_companies))
            MiniStat(r.trackerContacts.toString(), stringResource(R.string.rep_stat_trackers), if (r.trackerContacts > 0) scheme.tertiary else null)
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String, accent: Color? = null) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = accent ?: androidx.compose.material3.LocalContentColor.current, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Alerts first (actionable), with the full severity mix as one bar above them. */
@Composable
private fun AttentionCard(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit) {
    val all = r.alerts + r.others
    var expanded by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.rep_attention), stringResource(R.string.rep_attention_hint, r.alerts.size, all.size)) {
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
        val list = r.alerts.ifEmpty { r.others }.sortedBy { order.indexOf(it.severity) }
        (if (expanded) list else list.take(3)).forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenApp(f.pkg, window) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(labels, f.pkg, size = 32)
                Column(Modifier.weight(1f)) {
                    Text(findingTitle(f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(labels.label(f.pkg), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Badge(severityLabel(f.severity), severityColor(f.severity))
            }
        }
        if (list.size > 3) TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.rep_show_less else R.string.rep_show_all, list.size))
        }
    }
}

@Composable
private fun ActivityCard(r: DailyReport) {
    val max = r.hourlyBytes.maxOrNull() ?: 0L
    val peak = if (max > 0) r.hourlyBytes.indexOfFirst { it == max } else -1
    val ctx = LocalContext.current
    SectionCard(stringResource(R.string.rep_activity), if (peak >= 0) stringResource(R.string.rep_peak, peak, (peak + 1) % 24, fmtBytes(ctx, max)) else null) {
        if (peak < 0) {
            Text(stringResource(R.string.apps_empty_window), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val bar = MaterialTheme.colorScheme.primary
        val peakBar = MaterialTheme.colorScheme.tertiary
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / 24f
            val w = slot * 0.62f
            for (h in 0 until 24) {
                val x = h * slot + (slot - w) / 2
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
                val v = r.hourlyBytes[h]
                if (v <= 0) continue
                val bh = (size.height * v / max).coerceAtLeast(w)
                drawRoundRect(if (h == peak) peakBar else bar, Offset(x, size.height - bh), Size(w, bh), CornerRadius(w / 2))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00", "06", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun TopAppsCard(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit) {
    val rows = r.appTraffic.take(6)
    if (rows.isEmpty()) return
    val max = rows.maxOf { it.bytesUp + it.bytesDown }.coerceAtLeast(1L)
    val ctx = LocalContext.current
    val sent = MaterialTheme.colorScheme.primary
    val received = MaterialTheme.colorScheme.tertiary
    SectionCard(stringResource(R.string.rep_top_apps)) {
        rows.forEach { a ->
            val total = a.bytesUp + a.bytesDown
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenApp(a.pkg, window) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(labels, a.pkg, size = 32)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(labels.label(a.pkg), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtBytes(ctx, total), style = MaterialTheme.typography.labelMedium)
                    }
                    val up = a.bytesUp.toFloat() / max
                    val down = a.bytesDown.toFloat() / max
                    SegmentBar(listOf(up, down, (1f - up - down).coerceAtLeast(0f)), listOf(sent, received, Color.Transparent), height = 6)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(sent, stringResource(R.string.rep_sent))
            LegendItem(received, stringResource(R.string.rep_received))
        }
    }
}

/** Category share as a donut (few slices, part of a whole), with the top companies underneath. */
@Composable
private fun DestinationsCard(r: DailyReport, window: String, onOpenCompany: (String, String) -> Unit) {
    val byCat = r.companies.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { c -> c.apps.sumOf { it.second } } }.filter { it.value > 0 }
    val total = byCat.values.sum()
    if (total <= 0 && r.companies.isEmpty()) return
    val ctx = LocalContext.current
    val sorted = byCat.entries.sortedByDescending { it.value }
    val colors = sorted.map { catColor(it.key) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    SectionCard(stringResource(R.string.rep_by_category)) {
        if (total > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Canvas(Modifier.size(112.dp)) {
                val stroke = 18.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                var start = -90f
                val gap = if (sorted.size > 1) 3f else 0f
                sorted.forEachIndexed { i, e ->
                    val sweep = 360f * e.value / total
                    if (sweep - gap > 0.5f) drawArc(colors[i], start + gap / 2, sweep - gap, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sorted.take(6).forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                        Text(categoryLabel(e.key) ?: stringResource(R.string.rep_cat_other), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${e.value * 100 / total}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (r.companies.isNotEmpty()) {
            Text(stringResource(R.string.report_companies), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            r.companies.take(8).forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenCompany(c.company, window) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(catColor(c.category)))
                    Column(Modifier.weight(1f)) {
                        Text(c.company, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(categoryLabel(c.category), stringResource(R.string.rep_company_apps, c.apps.size)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(fmtBytes(ctx, c.apps.sumOf { it.second }), style = MaterialTheme.typography.labelMedium)
                }
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

/** Traffic while the screen was off, as a share of each app's own total. */
@Composable
private fun BackgroundCard(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit) {
    if (r.background.isEmpty()) return
    val ctx = LocalContext.current
    val color = MaterialTheme.colorScheme.secondary
    SectionCard(stringResource(R.string.report_background)) {
        r.background.take(6).forEach { a ->
            val off = a.screenOffUp + a.screenOffDown
            val share = off.toFloat() / (a.bytesUp + a.bytesDown).coerceAtLeast(1L)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenApp(a.pkg, window) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(labels, a.pkg, size = 32)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(labels.label(a.pkg), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${fmtBytes(ctx, off)} · ${(share * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    SegmentBar(listOf(share, 1f - share), listOf(color, Color.Transparent), height = 6)
                }
            }
        }
    }
}

/** Needs the shared GeoIP database, so the caller only shows it when the Clash extension is installed. */
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
    }
}

private fun flagEmoji(code: String): String = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

@Composable
private fun CoverageCard(r: DailyReport) {
    SectionCard(stringResource(R.string.report_coverage), stringResource(R.string.rep_cov_value, r.coverage.flows)) {
        Meter(stringResource(R.string.rep_cov_pkg), r.coverage.withPackagePct)
        Meter(stringResource(R.string.rep_cov_host), r.coverage.withHostnamePct)
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
private fun SegmentBar(parts: List<Float>, colors: List<Color>, height: Int = 12, track: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    val total = parts.sum().coerceAtLeast(0.0001f)
    Row(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50)).background(track), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEachIndexed { i, p ->
            if (p <= 0f) return@forEachIndexed
            val m = Modifier.weight(p / total).height(height.dp)
            if (colors[i] != Color.Transparent) Box(m.clip(RoundedCornerShape(50)).background(colors[i])) else Box(m)
        }
    }
}
