package com.nathanhanapps.wayd.feature.export

import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
import com.nathanhanapps.wayd.R
import kotlinx.coroutines.launch

/** A button that, after a confirmation (the file is unencrypted raw data), runs [export] and reports the outcome. */
@Composable
fun ExportButton(label: String, modifier: Modifier = Modifier, export: suspend () -> ExportResult) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { confirm = true }, enabled = !busy, modifier = modifier) { Text(label) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.export_title)) },
            text = { Text(stringResource(R.string.export_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        busy = true
                        scope.launch {
                            val msg = when (val r = export()) {
                                is ExportResult.Saved -> ctx.getString(R.string.export_saved, r.name, r.flows, r.messages)
                                ExportResult.Empty -> ctx.getString(R.string.export_empty)
                                ExportResult.Failed -> ctx.getString(R.string.export_failed)
                            }
                            busy = false
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text(stringResource(R.string.export_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.export_cancel)) } },
        )
    }
}
