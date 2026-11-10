package com.nathanhanapps.wayd.feature.export

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.nathanhanapps.wayd.R
import kotlinx.coroutines.launch

/**
 * After a privacy confirmation (the file is unencrypted raw data) this opens the system "save as" document picker, so the
 * user chooses the place and can rename the file, then runs [export] into the chosen document and reports the outcome.
 */
@Composable
fun ExportButton(
    label: String,
    suggestedName: () -> String,
    modifier: Modifier = Modifier,
    export: suspend (Uri) -> ExportResult,
) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val msg = when (val r = export(uri)) {
                is ExportResult.Saved -> resources.getString(R.string.export_saved, r.name, r.flows, r.messages)
                ExportResult.Empty -> resources.getString(R.string.export_empty)
                ExportResult.Failed -> resources.getString(R.string.export_failed)
            }
            busy = false
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
    OutlinedButton(onClick = { confirm = true }, enabled = !busy, modifier = modifier) { Text(label) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.export_title)) },
            text = { Text(stringResource(R.string.export_warning)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; picker.launch(suggestedName()) }) {
                    Text(stringResource(R.string.export_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.export_cancel)) } },
        )
    }
}
