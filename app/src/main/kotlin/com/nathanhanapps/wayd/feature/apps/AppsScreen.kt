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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.nathanhanapps.wayd.analysis.RiskScorer
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.AppsViewModel
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.destinationLabel
import com.nathanhanapps.wayd.feature.analysis.Loadable
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.analysis.categoryColor
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.analysis.severityColor
import javax.inject.Inject

/** Apps tab: every app that did something in the window, busiest first, with who it talks to. */
@Composable
fun AppsScreen(onOpenApp: (pkg: String, window: String) -> Unit, vm: AppsViewModel = hiltViewModel(), labelsHolder: LabelsHolder = hiltViewModel()) {
    val window by vm.window.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val labels = labelsHolder.labels
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tab_traffic), style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Windows.TODAY to R.string.window_today, Windows.YESTERDAY to R.string.window_yesterday, Windows.WEEK to R.string.window_week)
                .forEach { (key, label) ->
                    FilterChip(selected = window == key, onClick = { vm.select(key) }, label = { Text(stringResource(label)) })
                }
        }
        when (val r = result) {
            Loadable.Loading -> Text("…")
            is Loadable.Ready -> {
                val rows = r.value.apps.values.filter { it.total > 0 || it.flows > 0 }.sortedByDescending { it.total }
                if (rows.isEmpty()) {
                    Text(stringResource(R.string.apps_empty_window), style = MaterialTheme.typography.bodyLarge)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rows, key = { it.pkg }) { app -> AppCard(app, labels, context) { onOpenApp(app.pkg, window) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: AppReport, labels: AppLabels, context: android.content.Context, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(labels, app.pkg)
                Column(Modifier.weight(1f)) {
                    Text(labels.label(app.pkg), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.app_row_stats, fmtBytes(context, app.bytesUp), fmtBytes(context, app.bytesDown), app.flows),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (app.score.total > 0) {
                    val worst = app.findings.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.LOW
                    Badge(stringResource(R.string.score_label, app.score.total), severityColor(worst))
                }
            }
            if (app.total > 0 && app.screenOffBytes > 0) {
                Text(
                    stringResource(R.string.app_row_background, (app.screenOffBytes * 100 / app.total).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                app.destinations.take(3).forEach { d ->
                    val cat = categoryLabel(d.category)
                    destinationLabel(d).let { name -> Badge(if (cat != null) "$name · $cat" else name, categoryColor(d.category)) }
                }
            }
        }
    }
}

/** Tiny holder so composables can reach [AppLabels] through Hilt without a dedicated screen ViewModel. */
@dagger.hilt.android.lifecycle.HiltViewModel
class LabelsHolder @Inject constructor(val labels: AppLabels) : androidx.lifecycle.ViewModel()

@Suppress("unused")
private val keepImport = RiskScorer
