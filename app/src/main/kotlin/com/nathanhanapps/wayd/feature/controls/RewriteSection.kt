package com.nathanhanapps.wayd.feature.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.capture.RewritePluginParser
import com.nathanhanapps.wayd.capture.RewriteRule
import com.nathanhanapps.wayd.capture.RewriteStore
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.common.ActionRow
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.ItemRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Rewrite rules are their own rule set, separate from blocking (D-066), organised as rule sets with target apps (D-073). This
 * section lists the sets; each opens its own page with its apps and rules.
 */
@HiltViewModel
class RewriteViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val store: RewriteStore, val blocking: BlockingRules, val labels: AppLabels,
) : ViewModel() {
    val sets = store.sets
    val notice = MutableStateFlow<Int?>(null)
    /** A rule plugin that was read and is waiting for the user to confirm the import. */
    val pending = MutableStateFlow<Pair<RewritePluginParser.Result, String>?>(null)
    /** Set to open after an import or a new set; consumed by the screen. */
    val open = MutableStateFlow<Long?>(null)

    /** Reads a rule-plugin text file the user picked (≤ 2 MiB). Nothing is saved until the user confirms. */
    fun readPlugin(uri: android.net.Uri) = viewModelScope.launch {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.') ?: "Plugin"
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { b -> b.size <= 2 * 1024 * 1024 }?.toString(Charsets.UTF_8) } }.getOrNull()
        }
        val result = text?.let { RewritePluginParser.parse(it.lineSequence()) }
        when {
            // Unreadable (or over 2 MiB) is told apart from "read, but no [Rewrite] rules", e.g. a block list picked here by mistake.
            result == null -> { notice.value = R.string.rw_import_unreadable; pending.value = null }
            result.rules.isEmpty() -> { notice.value = R.string.rw_import_none; pending.value = null }
            else -> { notice.value = null; pending.value = result to name }
        }
    }

    fun confirmImport() {
        val (plugin, name) = pending.value ?: return
        pending.value = null
        viewModelScope.launch {
            val set = runCatching { store.importPlugin(plugin, name) }.getOrNull()
            notice.value = if (set == null) R.string.rw_invalid else null
            set?.let { open.value = it.id }
        }
    }

    fun cancelImport() { pending.value = null }

    fun createSet(name: String) = viewModelScope.launch {
        val set = runCatching { store.createSet(name) }.getOrNull()
        notice.value = if (set == null) R.string.rw_invalid else null
        set?.let { open.value = it.id }
    }

    fun setEnabled(id: Long, on: Boolean) = viewModelScope.launch {
        if (!runCatching { store.setEnabled(id, on) }.getOrDefault(false)) notice.value = R.string.rw_invalid
        runCatching { blocking.reloadRewrite() }
    }
}

@Composable
fun RewriteSection(onOpenSet: (Long) -> Unit, vm: RewriteViewModel = hiltViewModel()) {
    val sets by vm.sets.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val installed by vm.blocking.rewriteInstalled.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(vm::readPlugin) }
    LaunchedEffect(open) { open?.let { vm.open.value = null; onOpenSet(it) } }

    Column {
        InsetGroup(
            stringResource(R.string.rw_title), help = stringResource(R.string.rw_intro),
            trailing = { if (policy.running && installed == true) Text(stringResource(R.string.rw_active_short), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp)) },
        ) {
            if (policy.running && installed != true) {
                Text(stringResource(if (installed == null) R.string.rw_old_engine else R.string.rw_rejected), Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                GroupDivider()
            }
            sets.forEach { s ->
                val apps = s.apps.filter { it.enabled }
                ItemRow(
                    s.name,
                    subtitle = if (s.apps.isEmpty()) stringResource(R.string.rw_set_no_apps, s.rules.size) else stringResource(R.string.rw_set_row, s.rules.size, s.apps.size),
                    caption = apps.take(3).joinToString(", ") { vm.labels.label(it.pkg) }.ifEmpty { null },
                    onClick = { onOpenSet(s.id) },
                    trailing = {
                        Switch(checked = s.enabled, onCheckedChange = { vm.setEnabled(s.id, it) })
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp))
                    },
                )
                GroupDivider()
            }
            ActionRow(Icons.Filled.Add, stringResource(R.string.rw_import)) { pick.launch(arrayOf("*/*")) }
            GroupDivider()
            ActionRow(Icons.Filled.Add, stringResource(R.string.rw_new_set)) { creating = true }
        }
        notice?.let { Text(stringResource(it), Modifier.padding(start = 12.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    pending?.let { (p, fileName) ->
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text(p.name ?: fileName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rw_import_summary, p.rules.size, p.skipped), style = MaterialTheme.typography.bodyMedium)
                    val hosts = (p.rules.map { (if (it.subdomains) "*." else "") + it.host } + p.mitmHosts).distinct()
                    Text(stringResource(R.string.rw_import_hosts, hosts.take(8).joinToString(", ") + if (hosts.size > 8) " …" else ""), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmImport) { Text(stringResource(R.string.rw_import_review)) } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
    if (creating) NameDialog(stringResource(R.string.rw_new_set), "", onDismiss = { creating = false }) { vm.createSet(it); creating = false }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.blist_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.cx_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.block_cancel)) } },
    )
}

@Composable
internal fun actionText(r: RewriteRule): String = when (r.action) {
    "REJECT" -> stringResource(R.string.rw_action_reject) + " (" + r.arg + ")"
    "HEADER_REMOVE" -> stringResource(R.string.rw_action_header_remove) + ": " + r.arg
    "HEADER_SET" -> stringResource(R.string.rw_action_header_set) + ": " + r.arg
    "REDIRECT" -> stringResource(R.string.rw_action_redirect) + ": " + r.arg
    else -> stringResource(R.string.rw_action_query_remove) + ": " + r.arg
}

internal fun matchText(r: RewriteRule): String =
    "${r.method.ifEmpty { "*" }} ${if (r.subdomains) "*." else ""}${r.host}${if (r.match == "REGEX") " ~ " else ""}${r.pattern}"
