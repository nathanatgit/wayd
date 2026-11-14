package com.nathanhanapps.wayd.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nathanhanapps.wayd.core.model.TrackerCategory

/*
 * Shared dashboard pieces for app detail and Insights (D-060): tonal panels, a hero total with a sent/received ring,
 * dense stat tiles, proportional segment bars and ranked bar rows. Every bar or ring has a text value beside it,
 * so colour is never the only signal.
 */

/** One grouped surface on the dashboard page; the optional value sits at the right of the title. */
@Composable
fun DashboardPanel(title: String? = null, value: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (value != null) Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Primary-container headline: big total, sent/received ring and legend, then whatever tiles the caller adds. */
@Composable
fun DashboardHero(title: String, total: String, up: Long, down: Long, upText: String, downText: String, note: String? = null, content: @Composable ColumnScope.() -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(total, style = MaterialTheme.typography.displaySmall, maxLines = 1)
                    LegendItem(scheme.primary, upText)
                    LegendItem(scheme.tertiary, downText)
                }
                Ring(listOf(up.toFloat(), down.toFloat()), listOf(scheme.primary, scheme.tertiary), Modifier.size(76.dp), stroke = 11)
            }
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

data class StatTile(val value: String, val label: String, val accent: Color? = null, val progress: Float? = null, val description: String? = null)

/** Dense grid of number-over-label tiles; [description] replaces the tile's text for screen readers when given. */
@Composable
fun StatGrid(tiles: List<StatTile>, columns: Int = 3, labelLines: Int = 2, container: Color = MaterialTheme.colorScheme.surfaceContainerLowest) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t -> StatTileView(t, Modifier.weight(1f), container, labelLines) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTileView(t: StatTile, modifier: Modifier, container: Color, labelLines: Int) {
    val a11y = if (t.description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = t.description } else Modifier
    Surface(modifier.then(a11y), shape = MaterialTheme.shapes.medium, color = container, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.value, style = MaterialTheme.typography.titleMedium, color = t.accent ?: LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = labelLines, minLines = labelLines, overflow = TextOverflow.Ellipsis)
            if (t.progress != null) SegmentBar(listOf(t.progress.coerceIn(0f, 1f), (1f - t.progress).coerceAtLeast(0f)), listOf(t.accent ?: MaterialTheme.colorScheme.primary, Color.Transparent), height = 4)
        }
    }
}

/** Ranked row: optional leading mark, title with value on the right, a thin proportional bar and a one-line caption. */
@Composable
fun RankedBarRow(
    title: String,
    value: String,
    parts: List<Float>,
    colors: List<Color>,
    caption: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val click = if (onClick != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().then(click).padding(vertical = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (leading != null) Box(Modifier.padding(top = 2.dp)) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            SegmentBar(parts, colors, height = 6)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            extra?.invoke(this)
        }
    }
}

/** Parts of a whole as one bar, with a wrapping legend carrying each part's label and share. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ShareBar(parts: List<Pair<String, Long>>, colors: List<Color>) {
    val total = parts.sumOf { it.second }.coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentBar(parts.map { it.second.toFloat() }, colors, height = 12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            parts.forEachIndexed { i, (label, v) -> LegendItem(colors[i], "$label ${v * 100 / total}%") }
        }
    }
}

@Composable
fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(color)
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Dot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** Horizontal bar split proportionally; [Color.Transparent] segments become the track. */
@Composable
fun SegmentBar(parts: List<Float>, colors: List<Color>, height: Int = 12, track: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    val total = parts.sum().coerceAtLeast(0.0001f)
    Row(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50)).background(track), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEachIndexed { i, p ->
            if (p <= 0f) return@forEachIndexed
            val m = Modifier.weight(p / total).height(height.dp)
            if (colors[i] != Color.Transparent) Box(m.clip(RoundedCornerShape(50)).background(colors[i])) else Box(m)
        }
    }
}

/** Donut of [parts]; an all-zero input draws only the track. */
@Composable
fun Ring(parts: List<Float>, colors: List<Color>, modifier: Modifier, stroke: Int = 10, track: Color = MaterialTheme.colorScheme.outlineVariant) {
    val total = parts.sum()
    Canvas(modifier) {
        val w = stroke.dp.toPx()
        val inset = w / 2
        val arc = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
        if (total <= 0f) return@Canvas
        val gap = if (parts.count { it > 0f } > 1) 4f else 0f
        var start = -90f
        parts.forEachIndexed { i, p ->
            val sweep = 360f * p / total
            if (sweep - gap > 0.5f) drawArc(colors[i], start + gap / 2, sweep - gap, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Butt))
            start += sweep
        }
    }
}

/** Single-value gauge with the percentage written in its centre. */
@Composable
fun PercentRing(pct: Int, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Ring(listOf(pct.toFloat(), (100 - pct).coerceAtLeast(0).toFloat()), listOf(color, Color.Transparent), Modifier.size(64.dp), stroke = 8, track = MaterialTheme.colorScheme.surfaceContainerHighest)
            Text("$pct%", style = MaterialTheme.typography.labelLarge)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, minLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Category palette for traffic shares: watched categories keep their warning colours, the rest stay neutral. */
@Composable
fun categoryShareColor(c: TrackerCategory?): Color = when (c) {
    TrackerCategory.ADS, TrackerCategory.ANALYTICS, TrackerCategory.CRASH -> categoryColor(c)
    TrackerCategory.CDN, TrackerCategory.FIRST_PARTY -> MaterialTheme.colorScheme.primary
    TrackerCategory.OS, TrackerCategory.CARRIER, TrackerCategory.PUSH -> MaterialTheme.colorScheme.secondary
    TrackerCategory.UNKNOWN, null -> MaterialTheme.colorScheme.outline
}
