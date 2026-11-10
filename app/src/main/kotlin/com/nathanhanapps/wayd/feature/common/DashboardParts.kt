package com.nathanhanapps.wayd.feature.common

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Compact, Loon-style dashboard building blocks shared by Home and the Clash extension. */

fun rate(context: android.content.Context, bytesPerSecond: Long): String = Formatter.formatShortFileSize(context, bytesPerSecond) + "/s"

/** Upload / download rate over the last minute as two lines with a soft fill; current rates in the corner. */
@Composable
fun SpeedChart(up: List<Long>, down: List<Long>, upLabel: String, downLabel: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val upColor = MaterialTheme.colorScheme.primary
    val downColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RateLabel(upColor, upLabel, rate(context, up.lastOrNull() ?: 0))
            RateLabel(downColor, downLabel, rate(context, down.lastOrNull() ?: 0))
        }
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val max = ((up + down).maxOrNull() ?: 0L).coerceAtLeast(1024L).toFloat()
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            fun series(values: List<Long>, color: Color) {
                if (values.size < 2) return
                val step = size.width / (60 - 1).toFloat()
                val x0 = size.width - step * (values.size - 1)
                val line = Path()
                values.forEachIndexed { i, v ->
                    val x = x0 + i * step
                    val y = size.height - size.height * (v / max)
                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(x0, size.height); close() }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
                drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            series(down, downColor)
            series(up, upColor)
        }
    }
}

@Composable
private fun RateLabel(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** Small number tile: value on top, label below. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent ?: MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** iOS-style inset group: rounded container, rows separated by hairlines. */
@Composable
fun InsetGroup(header: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) Text(header, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer), content = content)
    }
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, enabled: Boolean = true, value: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

/** A read-only row: icon, title and a value on the right, no chevron. */
@Composable
fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NavRow(icon: ImageVector, title: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}

/** Upload/download rate samples (bytes per second), oldest first. */
data class SpeedSamples(val up: List<Long> = emptyList(), val down: List<Long> = emptyList())

/**
 * Samples the VPN byte counters once a second into a one-minute window. Collect it with `WhileSubscribed` so it only runs
 * while a dashboard is on screen; it reads in-memory state only.
 */
fun speedSamples(state: com.nathanhanapps.wayd.capture.CaptureState): kotlinx.coroutines.flow.Flow<SpeedSamples> = kotlinx.coroutines.flow.flow {
    val up = ArrayDeque<Long>()
    val down = ArrayDeque<Long>()
    var last: Pair<Long, Long>? = null
    while (true) {
        val s = state.ui.value
        if (s.phase != com.nathanhanapps.wayd.capture.CapturePhase.RUNNING) {
            up.clear(); down.clear(); last = null
        } else {
            val now = s.stats.bytesUp to s.stats.bytesDown
            last?.let { prev ->
                up.addLast((now.first - prev.first).coerceAtLeast(0)); down.addLast((now.second - prev.second).coerceAtLeast(0))
                while (up.size > 60) up.removeFirst()
                while (down.size > 60) down.removeFirst()
            }
            last = now
        }
        emit(SpeedSamples(up.toList(), down.toList()))
        kotlinx.coroutines.delay(1_000)
    }
}
