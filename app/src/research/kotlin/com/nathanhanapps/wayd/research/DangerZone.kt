package com.nathanhanapps.wayd.research

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DangerZoneViewModel @Inject constructor(val controller: MitmController) : ViewModel()

/** Settings section for HTTPS decryption (research build only). Collapsed by default, as docs/08 requires. */
@Composable
fun DangerZone(vm: DangerZoneViewModel = hiltViewModel()) {
    val c = vm.controller
    val st by c.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(0) } // 1 warning, 2 statement
    var confirmSystem by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dz_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.dz_collapse else R.string.dz_expand))
                }
            }
            if (expanded) {
                Text(stringResource(R.string.dz_intro), style = MaterialTheme.typography.bodyMedium)
                SwitchRow(stringResource(R.string.dz_decrypt), st.enabled && st.consented) { on ->
                    if (!st.consented) { if (on) step = 1 } else scope.launch { c.setEnabled(on) }
                }
                if (failed) Text(stringResource(R.string.dz_ca_failed), color = MaterialTheme.colorScheme.error)
                if (st.consented && st.caReady) {
                    Text(stringResource(R.string.dz_apply_hint), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { picker = true }, enabled = !st.busy) {
                        Text(stringResource(R.string.dz_pick_apps, st.apps.size))
                    }
                    Text(stringResource(R.string.dz_ca_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.dz_ca_body), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val res = c.startUserInstall()
                            Toast.makeText(
                                ctx,
                                ctx.getString(if (res == MitmController.UserInstall.FAILED) R.string.dz_toast_failed else R.string.dz_toast_install_steps),
                                Toast.LENGTH_LONG,
                            ).show()
                        }) {
                            Text(stringResource(R.string.dz_install_user))
                        }
                        OutlinedButton(onClick = {
                            val ok = c.exportCertificate()
                            Toast.makeText(ctx, ctx.getString(if (ok) R.string.dz_toast_saved else R.string.dz_toast_failed), Toast.LENGTH_LONG).show()
                        }) { Text(stringResource(R.string.dz_export)) }
                    }
                    SwitchRow(stringResource(R.string.dz_system_ca), st.systemCa, enabled = !st.busy) { on ->
                        if (on) confirmSystem = true else scope.launch { c.removeSystemCa() }
                    }
                    Text(stringResource(R.string.dz_system_ca_note), style = MaterialTheme.typography.bodySmall)
                    st.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { scope.launch { c.deleteCapturedContent() } }) {
                        Text(stringResource(R.string.dz_delete_content))
                    }
                }
                if (st.consented || st.caReady) {
                    Button(
                        onClick = { confirmRemove = true },
                        enabled = !st.busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.dz_remove)) }
                }
            }
        }
    }

    if (step == 1) {
        FullScreenStep(
            title = stringResource(R.string.dz_warn_title),
            body = stringResource(R.string.dz_warn_body),
            confirm = stringResource(R.string.dz_continue),
            requireScroll = false,
            onCancel = { step = 0 },
            onConfirm = { step = 2 },
        )
    }
    if (step == 2) {
        FullScreenStep(
            title = stringResource(R.string.dz_stmt_title),
            body = stringResource(R.string.dz_stmt_body),
            confirm = stringResource(R.string.dz_agree),
            requireScroll = true,
            destructive = true,
            onCancel = { step = 0 },
            onConfirm = {
                step = 0
                scope.launch { failed = !withContext(Dispatchers.Default) { c.consentAndCreateCa() } }
            },
        )
    }
    if (confirmSystem) {
        AlertDialog(
            onDismissRequest = { confirmSystem = false },
            title = { Text(stringResource(R.string.dz_sys_warn_title)) },
            text = { Text(stringResource(R.string.dz_sys_warn_body)) },
            confirmButton = {
                TextButton(onClick = { confirmSystem = false; scope.launch { c.installSystemCa() } }) {
                    Text(stringResource(R.string.dz_sys_warn_ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSystem = false }) { Text(stringResource(R.string.dz_cancel)) } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.dz_remove)) },
            text = { Text(stringResource(R.string.dz_remove_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; scope.launch { c.disableAndRemove() } }) {
                    Text(stringResource(R.string.dz_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.dz_cancel)) } },
        )
    }
    if (picker) {
        var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var extra by remember { mutableStateOf<Set<String>>(emptySet()) }
        var typed by remember { mutableStateOf("") }
        LaunchedEffect(extra) { apps = c.candidateApps(extra) }
        Dialog(onDismissRequest = { picker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dz_picker_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.dz_picker_note), style = MaterialTheme.typography.bodySmall)
                    if (apps.isEmpty()) Text(stringResource(R.string.dz_picker_empty), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.dz_picker_type)) },
                        )
                        Button(
                            onClick = {
                                val p = typed.trim()
                                if (p.isNotEmpty() && !MitmExclusions.isExcludedPackage(p)) {
                                    scope.launch { c.setApp(p, true) }
                                    extra = extra + p
                                }
                                typed = ""
                            },
                        ) { Text(stringResource(R.string.dz_picker_add)) }
                    }
                    OutlinedButton(onClick = { scope.launch { extra = extra + c.rootPackages() } }) {
                        Text(stringResource(R.string.dz_picker_root))
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(apps, key = { it.first }) { (pkg, label) ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                                }
                                Checkbox(checked = pkg in st.apps, onCheckedChange = { on -> scope.launch { c.setApp(pkg, on) } })
                            }
                        }
                    }
                    Button(onClick = { picker = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.dz_done)) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A full-screen step that back/outside taps cannot dismiss; [requireScroll] forces reading to the end. */
@Composable
private fun FullScreenStep(
    title: String,
    body: String,
    confirm: String,
    requireScroll: Boolean,
    destructive: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scroll = rememberScrollState()
    val atEnd by remember { derivedStateOf { !requireScroll || scroll.value >= scroll.maxValue - 8 } }
    var understood by rememberSaveable { mutableStateOf(!requireScroll) }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f).verticalScroll(scroll)) { Text(body, style = MaterialTheme.typography.bodyLarge) }
                if (requireScroll) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it }, enabled = atEnd)
                        Text(stringResource(R.string.dz_understand))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.dz_cancel)) }
                    Button(
                        onClick = onConfirm,
                        enabled = atEnd && understood,
                        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                    ) { Text(confirm) }
                }
            }
        }
    }
}
