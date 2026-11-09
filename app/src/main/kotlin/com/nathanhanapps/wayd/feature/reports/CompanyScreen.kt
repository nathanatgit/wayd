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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.UNKNOWN_PKG
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.Loadable
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.analysis.categoryColor
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What one app exchanged with one company in the window. */
data class CompanyApp(
    val pkg: String,
    val flows: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val screenOffBytes: Long,
    /** Busiest hostnames of this company that the app talked to. */
    val hosts: List<String>,
)

data class CompanyDetail(
    val company: String,
    val category: TrackerCategory?,
    val apps: List<CompanyApp>,
    val flows: Int,
    val bytesUp: Long,
    val bytesDown: Long,
)

@HiltViewModel
class CompanyViewModel @Inject constructor(
    private val flowDao: FlowDao,
    val labels: AppLabels,
    savedState: SavedStateHandle,
) : ViewModel() {
    val company: String = savedState["name"] ?: ""
    val windowKey: String = savedState["win"] ?: Windows.TODAY
    private val _detail = MutableStateFlow<Loadable<CompanyDetail>>(Loadable.Loading)
    val detail: StateFlow<Loadable<CompanyDetail>> = _detail

    init {
        viewModelScope.launch {
            _detail.value = Loadable.Ready(withContext(Dispatchers.IO) { load() })
        }
    }

    private suspend fun load(): CompanyDetail {
        val (start, end) = Windows.resolve(windowKey)
        val rows = flowDao.between(start, end).filter { it.company == company }
        val apps = rows.groupBy { it.pkg }.map { (pkg, fs) ->
            CompanyApp(
                pkg = pkg,
                flows = fs.size,
                bytesUp = fs.sumOf { it.bytesUp },
                bytesDown = fs.sumOf { it.bytesDown },
                screenOffBytes = fs.filter { !it.screenOn }.sumOf { it.bytesUp + it.bytesDown },
                hosts = fs.filter { it.host != null }.groupBy { it.host!! }
                    .entries.sortedByDescending { e -> e.value.sumOf { it.bytesUp + it.bytesDown } }.take(5).map { it.key },
            )
        }.sortedByDescending { it.bytesUp + it.bytesDown }
        return CompanyDetail(
            company, rows.firstNotNullOfOrNull { it.category }, apps, rows.size, rows.sumOf { it.bytesUp }, rows.sumOf { it.bytesDown },
        )
    }
}

/** One company (for example Tencent) in a report window: which apps talked to it, how much, and to which hosts. */
@Composable
fun CompanyScreen(onBack: () -> Unit, onOpenApp: (pkg: String, window: String) -> Unit, vm: CompanyViewModel = hiltViewModel()) {
    val state by vm.detail.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(vm.company, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(vm.windowKey, style = MaterialTheme.typography.labelSmall)
            }
            (state as? Loadable.Ready)?.value?.category?.let { c -> categoryLabel(c)?.let { Badge(it, categoryColor(c)) } }
        }
        when (val s = state) {
            Loadable.Loading -> Text("…")
            is Loadable.Ready -> {
                val d = s.value
                if (d.apps.isEmpty()) {
                    Text(stringResource(R.string.company_empty))
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.company_summary, d.apps.size, d.flows, fmtBytes(context, d.bytesUp), fmtBytes(context, d.bytesDown)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(d.apps, key = { it.pkg }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenApp(a.pkg, vm.windowKey) }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                AppIcon(vm.labels, a.pkg, 40)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (a.pkg == UNKNOWN_PKG) stringResource(R.string.app_unknown) else vm.labels.label(a.pkg),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        stringResource(R.string.app_row_stats, fmtBytes(context, a.bytesUp), fmtBytes(context, a.bytesDown), a.flows) +
                                            if (a.screenOffBytes > 0) " · " + stringResource(R.string.dest_screen_off, fmtBytes(context, a.screenOffBytes)) else "",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    if (a.hosts.isNotEmpty()) {
                                        Text(a.hosts.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
