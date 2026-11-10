package com.nathanhanapps.wayd.feature.export

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.research.ResearchFeatures
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExportViewModel @Inject constructor(private val exporter: ExportService) : ViewModel() {
    fun name(r: ExportRequest) = exporter.fileName(r)
    suspend fun export(target: Uri, r: ExportRequest) = exporter.export(target, r)
}

/**
 * "Export" for a scope (the connection history, one app, or one connection): asks which file to make, warns that it is unencrypted
 * personal data, then opens the system "save as" picker so the user chooses where it goes.
 */
@Composable
fun ExportButton(scope: ExportScope, label: String, modifier: Modifier = Modifier, vm: ExportViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val resources = ctx.resources
    val coroutines = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var format by rememberSaveable { mutableStateOf(ExportFormat.WAYD_JSON.name) }
    var range by rememberSaveable { mutableStateOf(ExportRange.SESSION.name) }
    var content by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ExportRequest?>(null) }

    val onPicked: (Uri?) -> Unit = { uri ->
        val req = pending
        pending = null
        if (uri != null && req != null) {
            busy = true
            coroutines.launch {
                val msg = when (val r = vm.export(uri, req)) {
                    is ExportResult.Saved -> resources.getString(R.string.export_saved, r.name, r.flows, r.messages)
                    ExportResult.Empty -> resources.getString(R.string.export_empty)
                    ExportResult.Failed -> resources.getString(R.string.export_failed)
                }
                busy = false
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
    val pcap = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PCAP.mime), onPicked)
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PCAPDROID_CSV.mime), onPicked)
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.WAYD_JSON.mime), onPicked)

    OutlinedButton(onClick = { open = true }, enabled = !busy, modifier = modifier) { Text(label) }
    if (!open) return

    val fmt = ExportFormat.valueOf(format)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.exd_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple(ExportFormat.WAYD_JSON, R.string.exd_fmt_json, R.string.exd_fmt_json_hint),
                    Triple(ExportFormat.PCAP, R.string.exd_fmt_pcap, R.string.exd_fmt_pcap_hint),
                    Triple(ExportFormat.PCAPDROID_CSV, R.string.exd_fmt_csv, R.string.exd_fmt_csv_hint),
                ).forEach { (f, title, hint) ->
                    Row(Modifier.fillMaxWidth().clickable { format = f.name }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = fmt == f, onClick = { format = f.name })
                        Column {
                            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (scope is ExportScope.Capture) {
                    val ranges = listOf(ExportRange.SESSION to R.string.exd_r_session, ExportRange.TODAY to R.string.exd_r_today, ExportRange.WEEK to R.string.exd_r_week, ExportRange.ALL to R.string.exd_r_all)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        ranges.forEachIndexed { i, (r, l) ->
                            SegmentedButton(selected = range == r.name, onClick = { range = r.name }, shape = SegmentedButtonDefaults.itemShape(i, ranges.size)) { Text(stringResource(l), maxLines = 1) }
                        }
                    }
                }
                if (ResearchFeatures.AVAILABLE && fmt != ExportFormat.PCAPDROID_CSV) {
                    Row(Modifier.fillMaxWidth().clickable { content = !content }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.exd_content), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(content, { content = it })
                    }
                }
                Text(stringResource(R.string.exd_warning), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val effective = if (scope is ExportScope.Capture) ExportScope.Capture(ExportRange.valueOf(range)) else scope
                val req = ExportRequest(fmt, effective, includeContent = content && fmt != ExportFormat.PCAPDROID_CSV)
                pending = req
                open = false
                val name = vm.name(req)
                when (fmt) { ExportFormat.PCAP -> pcap.launch(name); ExportFormat.PCAPDROID_CSV -> csv.launch(name); ExportFormat.WAYD_JSON -> json.launch(name) }
            }) { Text(stringResource(R.string.exd_save)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
