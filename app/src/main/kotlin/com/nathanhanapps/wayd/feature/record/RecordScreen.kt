package com.nathanhanapps.wayd.feature.record

import android.Manifest
import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.CapturePhase
import com.nathanhanapps.wayd.core.db.Flow

@Composable
fun RecordScreen(onOpenControls: () -> Unit, onOpenReports: () -> Unit, vm: RecordViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val cov by vm.coverage.collectAsStateWithLifecycle()
    val recording by vm.recording.enabled.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = ui.phase == CapturePhase.RUNNING
    val starting = ui.phase == CapturePhase.STARTING
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshRules()
    }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.start()
    }
    fun startWithConsent() {
        val intent = VpnService.prepare(context)
        if (intent != null) vpnConsent.launch(intent) else vm.start()
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startWithConsent() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.dashboard_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.dashboard_intro), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            DashboardCard {
                Text(stringResource(R.string.dashboard_engine), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(when (ui.phase) {
                    CapturePhase.IDLE -> R.string.dashboard_idle
                    CapturePhase.STARTING -> R.string.record_status_starting
                    CapturePhase.RUNNING -> R.string.dashboard_running
                    CapturePhase.ERROR -> R.string.record_status_error
                }), color = if (ui.phase == CapturePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Button(
                    onClick = {
                        when {
                            active -> vm.stop()
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else -> startWithConsent()
                        }
                    }, enabled = !starting, modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (active) R.string.dashboard_stop else R.string.dashboard_start)) }
                Text(stringResource(R.string.dashboard_vpn_notice), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            DashboardCard {
                Text(stringResource(R.string.dashboard_features), style = MaterialTheme.typography.titleLarge)
                ToggleRow(stringResource(R.string.dashboard_record), stringResource(R.string.dashboard_record_hint), recording, !starting, vm.recording::setEnabled)
                Text(stringResource(if (!active) R.string.dashboard_record_waiting else if (recording) R.string.dashboard_record_on else R.string.dashboard_record_off), style = MaterialTheme.typography.bodySmall)
                ToggleRow(stringResource(R.string.dashboard_clash), stringResource(R.string.dashboard_clash_hint), false, false) { }
            }
        }
        item {
            DashboardCard {
                Text(stringResource(R.string.control_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.control_summary, policy.rules.count { it.id in policy.activeIds }, policy.rules.size))
                Text(stringResource(R.string.dashboard_rules_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenControls) { Text(stringResource(R.string.dashboard_rules_open)) }
            }
        }
        item {
            DashboardCard {
                Text(stringResource(R.string.dashboard_activity), style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(stringResource(R.string.stat_flows), ui.stats.flows.toString())
                    Stat(stringResource(R.string.stat_apps), ui.stats.apps.toString())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(stringResource(R.string.stat_up), Formatter.formatShortFileSize(context, ui.stats.bytesUp))
                    Stat(stringResource(R.string.stat_down), Formatter.formatShortFileSize(context, ui.stats.bytesDown))
                }
            }
        }
        item {
            DashboardCard {
                Text(stringResource(R.string.dashboard_history), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.coverage_line, cov.total, if (cov.total == 0) 0 else cov.withPkg * 100 / cov.total, cov.tcp, if (cov.tcp == 0) 0 else cov.tcpWithHost * 100 / cov.tcp), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onOpenReports) { Text(stringResource(R.string.dashboard_reports)) }
                com.nathanhanapps.wayd.feature.export.ExportButton(stringResource(R.string.export_capture), vm::exportName, Modifier.fillMaxWidth()) { vm.exportCapture(it) }
            }
        }
        item { Text(stringResource(R.string.dashboard_recent), style = MaterialTheme.typography.titleMedium) }
        if (recent.isEmpty()) item { Text(stringResource(R.string.flows_empty)) }
        items(recent, key = { it.id }) { FlowRow(it, context) }
    }
}

@Composable
private fun DashboardCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FlowRow(f: Flow, context: Context) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${f.host ?: f.dstIp.let { if (':' in it) "[$it]" else it }}:${f.dstPort}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${f.pkg} · ${f.proto.name} · ↑${Formatter.formatShortFileSize(context, f.bytesUp)} ↓${Formatter.formatShortFileSize(context, f.bytesDown)}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
