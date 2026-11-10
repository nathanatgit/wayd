package com.nathanhanapps.wayd.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.feature.analysis.Loadable
import com.nathanhanapps.wayd.feature.analysis.ReportDetailViewModel
import com.nathanhanapps.wayd.feature.analysis.ReportsViewModel
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.extensions.rememberClashInstalled
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Insights tab: pick a window (today, yesterday, 7 days or any recorded day) and read its report in place. */
@Composable
fun ReportsScreen(
    onOpenApp: (pkg: String, window: String) -> Unit,
    onOpenCompany: (company: String, window: String) -> Unit,
    vm: ReportsViewModel = hiltViewModel(),
) {
    val window by vm.window.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val state by vm.report.collectAsStateWithLifecycle()
    val countries = rememberClashInstalled()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val today = remember { LocalDate.now() }
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val older = days.map { it.day }.filter { it != today.toString() && it != today.minusDays(1).toString() }

    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Text(stringResource(R.string.tab_insights), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineMedium)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(window == Windows.TODAY, { vm.select(Windows.TODAY) }, { Text(stringResource(R.string.window_today)) }) }
            item { FilterChip(window == Windows.YESTERDAY, { vm.select(Windows.YESTERDAY) }, { Text(stringResource(R.string.window_yesterday)) }) }
            item { FilterChip(window == Windows.WEEK, { vm.select(Windows.WEEK) }, { Text(stringResource(R.string.window_week)) }) }
            items(older, key = { it }) { day ->
                val label = runCatching { LocalDate.parse(day).format(fmt) }.getOrDefault(day)
                FilterChip(window == day, { vm.select(day) }, { Text(label) })
            }
        }
        when (val s = state) {
            Loadable.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            is Loadable.Ready -> {
                val r = s.value
                if (r.appTraffic.isEmpty() && r.alerts.isEmpty() && r.others.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.apps_empty_window), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        insightsBody(r, vm.labels, window, onOpenApp, onOpenCompany, countries)
                    }
                }
            }
        }
    }
}

/** A single window's report as its own page (kept for deep links such as `report/2026-11-09`). */
@Composable
fun ReportDetailScreen(
    onBack: () -> Unit,
    onOpenApp: (pkg: String, window: String) -> Unit,
    onOpenCompany: (company: String, window: String) -> Unit,
    vm: ReportDetailViewModel = hiltViewModel(),
) {
    val state by vm.report.collectAsStateWithLifecycle()
    val countries = rememberClashInstalled()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(vm.windowKey, style = MaterialTheme.typography.titleLarge)
        }
        when (val s = state) {
            Loadable.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            is Loadable.Ready -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                insightsBody(s.value, vm.labels, vm.windowKey, onOpenApp, onOpenCompany, countries)
            }
        }
    }
}
