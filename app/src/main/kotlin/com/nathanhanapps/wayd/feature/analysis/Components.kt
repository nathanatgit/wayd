package com.nathanhanapps.wayd.feature.analysis

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.Insight
import com.nathanhanapps.wayd.analysis.InsightKind
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.TrackerCategory

fun fmtBytes(context: Context, n: Long): String = Formatter.formatShortFileSize(context, n)

@Composable
fun categoryLabel(c: TrackerCategory?): String? = when (c) {
    TrackerCategory.ADS -> stringResource(R.string.cat_ads)
    TrackerCategory.ANALYTICS -> stringResource(R.string.cat_analytics)
    TrackerCategory.CRASH -> stringResource(R.string.cat_crash)
    TrackerCategory.PUSH -> stringResource(R.string.cat_push)
    TrackerCategory.CDN -> stringResource(R.string.cat_cdn)
    TrackerCategory.OS -> stringResource(R.string.cat_os)
    TrackerCategory.CARRIER -> stringResource(R.string.cat_carrier)
    TrackerCategory.FIRST_PARTY -> stringResource(R.string.cat_first_party)
    TrackerCategory.UNKNOWN, null -> null
}

@Composable
fun severityLabel(s: Severity): String = when (s) {
    Severity.HIGH -> stringResource(R.string.sev_high)
    Severity.MEDIUM -> stringResource(R.string.sev_medium)
    Severity.LOW -> stringResource(R.string.sev_low)
    Severity.INFO -> stringResource(R.string.sev_info)
}

/** Colour is never the only signal: every badge also carries its label text. */
@Composable
fun severityColor(s: Severity): Color = when (s) {
    Severity.HIGH -> MaterialTheme.colorScheme.error
    Severity.MEDIUM -> MaterialTheme.colorScheme.tertiary
    Severity.LOW -> MaterialTheme.colorScheme.secondary
    Severity.INFO -> MaterialTheme.colorScheme.outline
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, foreground) = when (color) {
        scheme.primary -> scheme.primaryContainer to scheme.onPrimaryContainer
        scheme.secondary -> scheme.secondaryContainer to scheme.onSecondaryContainer
        scheme.tertiary -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        scheme.error -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = foreground) {
        Text(
            text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Name of a destination; a group of connections without a hostname is shown by its real address (IPv4 or IPv6) plus how many others it groups. */
@Composable
fun destinationLabel(d: com.nathanhanapps.wayd.analysis.Destination): String {
    if (!d.unnamed) return d.label
    val first = d.hosts.firstOrNull() ?: return stringResource(R.string.dest_unnamed, d.addressCount)
    return if (d.addressCount > 1) stringResource(R.string.dest_unnamed_more, first, d.addressCount - 1) else first
}

@Composable
fun categoryColor(c: TrackerCategory?): Color = when (c) {
    TrackerCategory.ADS -> MaterialTheme.colorScheme.error
    TrackerCategory.ANALYTICS, TrackerCategory.CRASH -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun AppIcon(labels: AppLabels, pkg: String, size: Int = 40) {
    // Decoding an icon is a package-manager call plus a bitmap render: keep it off the main thread.
    val icon by androidx.compose.runtime.produceState(labels.cachedIcon(pkg), pkg) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { labels.icon(pkg) }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)))
    } else {
        Surface(Modifier.size(size.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {}
    }
}

/** Turns a structured insight into a sentence. All wording lives in string resources (en + zh). */
@Composable
fun insightText(i: Insight): String {
    val ctx = LocalContext.current
    return when (i.kind) {
        InsightKind.TOP_DESTINATION -> stringResource(R.string.insight_top, i.text, i.percent)
        InsightKind.BACKGROUND_SHARE -> stringResource(R.string.insight_background, i.percent, fmtBytes(ctx, i.number))
        InsightKind.TRACKERS -> stringResource(R.string.insight_trackers, i.text, fmtBytes(ctx, i.number), i.percent)
        InsightKind.UPLOAD_HEAVY -> stringResource(R.string.insight_upload, fmtBytes(ctx, i.number), i.percent)
        InsightKind.UNNAMED_DESTINATIONS -> stringResource(R.string.insight_unnamed, i.number.toInt(), i.percent)
        InsightKind.LOCAL_NETWORK -> stringResource(R.string.insight_local, i.number.toInt(), i.percent)
    }
}

/** 24 stacked bars (received below, sent above). Hour labels give a text alternative to the colours. */
@Composable
fun HourlyChart(up: LongArray, down: LongArray, modifier: Modifier = Modifier) {
    val upColor = MaterialTheme.colorScheme.primary
    val downColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val max = (0 until 24).maxOf { up[it] + down[it] }.coerceAtLeast(1L)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(upColor to R.string.stat_up, downColor to R.string.stat_down).forEach { (color, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            for (line in 0..2) {
                val y = (size.height - 1.dp.toPx()) * line / 2f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val slot = size.width / 24f
            val bar = slot * 0.7f
            for (h in 0 until 24) {
                val x = h * slot + (slot - bar) / 2
                val dh = size.height * down[h] / max
                val uh = size.height * up[h] / max
                if (dh > 0) drawRoundRect(downColor, Offset(x, size.height - dh), Size(bar, dh), CornerRadius(2f))
                if (uh > 0) drawRoundRect(upColor, Offset(x, size.height - dh - uh), Size(bar, uh), CornerRadius(2f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(stringResource(R.string.chart_legend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
