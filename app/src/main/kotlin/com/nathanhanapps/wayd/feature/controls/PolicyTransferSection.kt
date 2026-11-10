package com.nathanhanapps.wayd.feature.controls

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.capture.PolicyBackup
import com.nathanhanapps.wayd.capture.PolicyImportPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

@HiltViewModel
class PolicyTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val blocking: BlockingRules,
) : ViewModel() {
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow<Int?>(null)
    val preview = MutableStateFlow<PolicyImportPlan?>(null)
    val undo = MutableStateFlow<List<Long>>(emptyList())
    private var exportBytes: ByteArray? = null

    fun prepareExport(ids: Set<Long>, sensitive: Boolean, ready: () -> Unit) = action {
        exportBytes = blocking.exportPolicy(ids, sensitive)
        ready()
    }

    fun save(uri: Uri?) {
        val bytes = exportBytes
        exportBytes = null
        if (uri == null || bytes == null) return
        action {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            }
            notice.value = R.string.policy_saved
        }
    }

    fun load(uri: Uri?) {
        if (uri == null) return
        action {
            val bytes = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= PolicyBackup.MAX_BYTES)
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
            }
            preview.value = blocking.previewImport(bytes)
        }
    }

    fun apply() = action {
        val plan = requireNotNull(preview.value)
        undo.value = blocking.importPolicy(plan)
        preview.value = null
        notice.value = R.string.policy_imported
    }

    fun undo() = action {
        blocking.undoImport(undo.value)
        undo.value = emptyList()
        notice.value = R.string.policy_undone
    }

    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        notice.value = null
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice.value = R.string.policy_failed }
            finally { busy.value = false }
        }
    }
}

@Composable
fun PolicyTransferSection(vm: PolicyTransferViewModel = hiltViewModel()) {
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var sensitive by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), vm::save)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::load)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.policy_title))
            Text(stringResource(R.string.policy_intro))
            Row {
                TextButton(onClick = {
                    selected = policy.rules.filter { it.id > 0 }.map { it.id }.toSet()
                    sensitive = false
                    exporting = true
                }, enabled = !busy && policy.rules.any { it.id > 0 }) { Text(stringResource(R.string.policy_export)) }
                TextButton(onClick = { open.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy) {
                    Text(stringResource(R.string.policy_import))
                }
            }
            if (undo.isNotEmpty()) TextButton(onClick = { vm.undo() }, enabled = !busy) { Text(stringResource(R.string.policy_undo)) }
            notice?.let { Text(stringResource(it)) }
        }
    }
    if (exporting) AlertDialog(
        onDismissRequest = { if (!busy) exporting = false },
        title = { Text(stringResource(R.string.policy_export)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.policy_export_warning))
                notice?.let { Text(stringResource(it)) }
                Row {
                    Checkbox(checked = sensitive, onCheckedChange = { sensitive = it }, enabled = !busy)
                    Text(stringResource(R.string.policy_include_targets))
                }
                policy.rules.filter { it.id > 0 }.forEach { rule ->
                    Row {
                        Checkbox(checked = rule.id in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + rule.id else selected - rule.id
                        }, enabled = !busy)
                        Column {
                            Text(rule.pkg.orEmpty())
                            Text(if (rule.matchKind == "APP") stringResource(R.string.block_app) else rule.host ?: stringResource(R.string.policy_redacted))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            vm.prepareExport(selected, sensitive) { exporting = false; save.launch("wayd-policy.json") }
        }, enabled = !busy && selected.isNotEmpty()) { Text(stringResource(R.string.policy_export)) } },
        dismissButton = { TextButton(onClick = { exporting = false }, enabled = !busy) { Text(stringResource(R.string.export_cancel)) } },
    )
    preview?.let { plan -> AlertDialog(
        onDismissRequest = { if (!busy) vm.preview.value = null },
        title = { Text(stringResource(R.string.policy_preview)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.policy_preview_counts, plan.additions, plan.conflicts, plan.missingIdentity, plan.mismatchedIdentity))
                Text(stringResource(R.string.policy_import_warning))
                notice?.let { Text(stringResource(it)) }
                plan.rules.forEach { rule ->
                    Text(rule.packageName)
                    Text(if (rule.redacted) stringResource(R.string.policy_redacted) else rule.target.orEmpty())
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.apply() }, enabled = !busy && plan.additions > 0) { Text(stringResource(R.string.policy_import_inactive)) } },
        dismissButton = { TextButton(onClick = { vm.preview.value = null }, enabled = !busy) { Text(stringResource(R.string.export_cancel)) } },
    ) }
}
