package com.nathanhanapps.wayd.feature.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.HourlyActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.min

/** Compact 7×24 contribution-style grid. One chart touch target, with accessibility actions for cell navigation. */
@Composable
fun ActivityHeatmap(activity: Map<LocalDate, HourlyActivity>, internetOnly: Boolean = true) {
    val today = LocalDate.now()
    val days = remember(today) { List(7) { today.minusDays(6L - it) } }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHour by rememberSaveable { mutableIntStateOf(12) }
    val ctx = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("M/d", locale) }
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.surfaceContainerHighest) + (1..4).map { lerp(scheme.surfaceContainerHighest, scheme.primary, it / 4f) }
    val peak = days.maxOf { day -> activity[day]?.let { a -> (0..23).maxOf { a.up[it] + a.down[it] } } ?: 0L }.coerceAtLeast(1L)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rowHeight = 18.dp * fontScale
    val labelWidth = 42.dp * fontScale
    val selected = selectedDay?.let { activity[LocalDate.parse(it)] }
    val selection = selectedDay?.let { stringResource(R.string.heatmap_selection, it, selectedHour, selectedHour + 1) }
    val selectedUp = stringResource(R.string.stat_up) + " " + fmtBytes(ctx, selected?.up?.get(selectedHour) ?: 0)
    val selectedDown = stringResource(R.string.stat_down) + " " + fmtBytes(ctx, selected?.down?.get(selectedHour) ?: 0)
    val description = stringResource(if (internetOnly) R.string.heatmap_note else R.string.heatmap_note_all)
    val inspect = stringResource(R.string.heatmap_inspect)
    val previousHour = stringResource(R.string.heatmap_previous_hour)
    val nextHour = stringResource(R.string.heatmap_next_hour)
    val previousDay = stringResource(R.string.heatmap_previous_day)
    val nextDay = stringResource(R.string.heatmap_next_day)
    fun moveCell(delta: Int): Boolean {
        val day = days.indexOfFirst { it.toString() == selectedDay }.takeIf { it >= 0 } ?: 6
        val cell = (day * 24 + selectedHour + delta).coerceIn(0, 167)
        selectedDay = days[cell / 24].toString()
        selectedHour = cell % 24
        return true
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = labelWidth), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(labelWidth)) {
                days.forEach { day ->
                    Box(Modifier.height(rowHeight), contentAlignment = Alignment.CenterStart) {
                        Text(day.format(dateFormat), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Canvas(
                Modifier.weight(1f).height(rowHeight * 7f).pointerInput(days) {
                    detectTapGestures { point ->
                        selectedDay = days[(point.y / size.height * 7).toInt().coerceIn(0, 6)].toString()
                        selectedHour = (point.x / size.width * 24).toInt().coerceIn(0, 23)
                    }
                }.semantics {
                    contentDescription = description
                    stateDescription = listOfNotNull(selection, selectedDay?.let { "$selectedUp · $selectedDown" }).joinToString(" · ")
                    onClick(label = inspect) { if (selectedDay == null) selectedDay = today.toString(); true }
                    customActions = listOf(
                        CustomAccessibilityAction(previousHour) { moveCell(-1) },
                        CustomAccessibilityAction(nextHour) { moveCell(1) },
                        CustomAccessibilityAction(previousDay) { moveCell(-24) },
                        CustomAccessibilityAction(nextDay) { moveCell(24) },
                    )
                },
            ) {
                val slotX = size.width / 24f
                val slotY = size.height / 7f
                val cell = min(slotX - 2.dp.toPx(), slotY - 3.dp.toPx()).coerceAtLeast(1f)
                days.forEachIndexed { row, day ->
                    val data = activity[day]
                    for (hour in 0..23) {
                        val total = data?.let { it.up[hour] + it.down[hour] } ?: 0L
                        val level = if (total <= 0) 0 else (ln(1.0 + total) / ln(1.0 + peak) * 4).toInt().coerceIn(1, 4)
                        val offset = Offset(hour * slotX + (slotX - cell) / 2, row * slotY + (slotY - cell) / 2)
                        drawRoundRect(colors[level], offset, Size(cell, cell), CornerRadius(2.dp.toPx()))
                        if (selectedDay == day.toString() && selectedHour == hour) drawRoundRect(scheme.onSurface, offset, Size(cell, cell), CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.heatmap_less), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            colors.forEach { color -> Canvas(Modifier.padding(start = 4.dp).size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx())) } }
            Text(stringResource(R.string.heatmap_more), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        if (selection != null) {
            Text(selection, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("↑ $selectedUp", style = MaterialTheme.typography.bodySmall, color = scheme.primary)
                Text("↓ $selectedDown", style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
            }
        }
    }
}
