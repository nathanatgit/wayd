package com.nathanhanapps.wayd.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.DailyReport
import com.nathanhanapps.wayd.analysis.UNKNOWN_PKG
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.Loadable
import com.nathanhanapps.wayd.feature.analysis.ReportDetailViewModel
import com.nathanhanapps.wayd.feature.analysis.ReportsViewModel
import com.nathanhanapps.wayd.feature.analysis.categoryColor
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.findingTitle
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import com.nathanhanapps.wayd.feature.analysis.severityColor
import com.nathanhanapps.wayd.feature.analysis.severityLabel

/** Reports tab: the days that have recorded activity. Each day is a thin overview that links into apps. */
@Composable
fun ReportsScreen(onOpenDay: (String) -> Unit, vm: ReportsViewModel = hiltViewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.reports_title), style = MaterialTheme.typography.headlineMedium)
        if (days.isEmpty()) {
            Text(stringResource(R.string.apps_empty_window), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(stringResource(R.string.reports_days), style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(days, key = { it.day }) { d ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenDay(d.day) }) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(d.day, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.report_flows, d.flows), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportDetailScreen(onBack: () -> Unit, onOpenApp: (pkg: String, window: String) -> Unit, vm: ReportDetailViewModel = hiltViewModel()) {
    val state by vm.report.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(vm.windowKey, style = MaterialTheme.typography.titleLarge)
        }
        when (val s = state) {
            Loadable.Loading -> Text("…")
            is Loadable.Ready -> ReportBody(s.value, vm.labels, vm.windowKey, onOpenApp)
        }
    }
}

@Composable
private fun ReportBody(r: DailyReport, labels: AppLabels, window: String, onOpenApp: (String, String) -> Unit) {
    val context = LocalContext.current
    fun name(pkg: String) = labels.label(pkg)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.report_summary, r.appCount, r.domainCount, r.companyCount, r.trackerContacts), style = MaterialTheme.typography.bodyLarge) }

        item { Text(stringResource(R.string.report_alerts), style = MaterialTheme.typography.titleMedium) }
        if (r.alerts.isEmpty()) item { Text(stringResource(R.string.report_no_alerts), style = MaterialTheme.typography.bodyMedium) }
        items(r.alerts) { f ->
            Card(Modifier.fillMaxWidth().clickable { onOpenApp(f.pkg, window) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge(severityLabel(f.severity), severityColor(f.severity))
                        Text(name(f.pkg), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(findingTitle(f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item { Text(stringResource(R.string.report_background), style = MaterialTheme.typography.titleMedium) }
        items(r.background) { a ->
            Row(Modifier.fillMaxWidth().clickable { onOpenApp(a.pkg, window) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name(a.pkg), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(fmtBytes(context, a.screenOffUp + a.screenOffDown), style = MaterialTheme.typography.bodyMedium)
            }
        }

        item { Text(stringResource(R.string.report_companies), style = MaterialTheme.typography.titleMedium) }
        items(r.companies.take(15)) { c ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.company, style = MaterialTheme.typography.bodyLarge)
                    categoryLabel(c.category)?.let { Badge(it, categoryColor(c.category)) }
                }
                Text(
                    c.apps.take(4).map { stringResource(R.string.report_company_row, name(it.first), fmtBytes(context, it.second)) }.joinToString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        item { Text(stringResource(R.string.report_new), style = MaterialTheme.typography.titleMedium) }
        items(r.newToday) { f ->
            Text(stringResource(R.string.report_company_row, name(f.pkg), findingTitle(f)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { onOpenApp(f.pkg, window) })
        }

        item { Text(stringResource(R.string.report_countries_na), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }

        item {
            Text(stringResource(R.string.report_coverage), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.report_coverage_text, r.coverage.flows, r.coverage.withPackagePct, r.coverage.withHostnamePct, r.coverage.measurableTcp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Suppress("unused")
private val keep = UNKNOWN_PKG
