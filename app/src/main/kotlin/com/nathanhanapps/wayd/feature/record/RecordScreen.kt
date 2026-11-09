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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.nathanhanapps.wayd.capture.CapturePhase
import com.nathanhanapps.wayd.core.db.Flow

@Composable
fun RecordScreen(vm: RecordViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val cov by vm.coverage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = ui.phase == CapturePhase.RUNNING || ui.phase == CapturePhase.STARTING

    // The system VPN consent dialog is shown by the OS; we only continue if the user accepts it.
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.start()
    }
    fun startWithConsent() {
        val intent = VpnService.prepare(context)
        if (intent != null) vpnConsent.launch(intent) else vm.start()
    }
    // Notification permission only affects the status notification; recording works without it.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startWithConsent()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.record_title), style = MaterialTheme.typography.headlineMedium)

        Text(
            when (ui.phase) {
                CapturePhase.IDLE -> stringResource(R.string.record_status_idle)
                CapturePhase.STARTING -> stringResource(R.string.record_status_starting)
                CapturePhase.RUNNING -> stringResource(R.string.record_status_running)
                CapturePhase.ERROR -> stringResource(R.string.record_status_error)
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (ui.phase == CapturePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )

        Button(
            onClick = {
                when {
                    active -> vm.stop()
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> startWithConsent()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = if (active) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
        ) {
            Text(stringResource(if (active) R.string.record_stop else R.string.record_start))
        }

        com.nathanhanapps.wayd.feature.export.ExportButton(stringResource(R.string.export_capture), vm::exportName, Modifier.fillMaxWidth()) { vm.exportCapture(it) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(stringResource(R.string.stat_flows), ui.stats.flows.toString())
            Stat(stringResource(R.string.stat_apps), ui.stats.apps.toString())
            Stat(stringResource(R.string.stat_up), Formatter.formatShortFileSize(context, ui.stats.bytesUp))
            Stat(stringResource(R.string.stat_down), Formatter.formatShortFileSize(context, ui.stats.bytesDown))
        }

        // Coverage gaps are shown, never hidden (AGENTS.md rule 8).
        Text(
            stringResource(
                R.string.coverage_line,
                cov.total,
                if (cov.total == 0) 0 else cov.withPkg * 100 / cov.total,
                cov.tcp,
                if (cov.tcp == 0) 0 else cov.tcpWithHost * 100 / cov.tcp,
            ),
            style = MaterialTheme.typography.labelMedium,
        )

        Text(stringResource(R.string.record_notice), style = MaterialTheme.typography.bodySmall)

        Text(stringResource(R.string.flows_title), style = MaterialTheme.typography.titleMedium)
        if (recent.isEmpty()) {
            Text(stringResource(R.string.flows_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(recent, key = { it.id }) { f ->
                    FlowRow(f, context)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FlowRow(f: Flow, context: Context) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "${f.host ?: stringResource(R.string.flow_no_host)}:${f.dstPort}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${f.pkg} · ${f.proto.name} · ↑${Formatter.formatShortFileSize(context, f.bytesUp)} ↓${Formatter.formatShortFileSize(context, f.bytesDown)}",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
