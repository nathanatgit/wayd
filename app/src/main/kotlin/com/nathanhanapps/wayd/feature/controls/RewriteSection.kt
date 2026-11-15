package com.nathanhanapps.wayd.feature.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.nathanhanapps.wayd.feature.common.ActionRow
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.HelpHint
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.ItemRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.nathanhanapps.wayd.capture.RewriteRule
import com.nathanhanapps.wayd.capture.RewriteStore
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Rewrite rules are their own rule set, separate from blocking (D-066). They act only on flows already decrypted for the app. */
@HiltViewModel
class RewriteViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val store: RewriteStore, val blocking: BlockingRules, val labels: AppLabels,
) : ViewModel() {
    /** A rule plugin that was read and is waiting for the user to choose the app it applies to. */
    val pending = MutableStateFlow<com.nathanhanapps.wayd.capture.RewritePluginParser.Result?>(null)
    val imported = MutableStateFlow<Int?>(null)

    /** Reads a rule-plugin text file the user picked (≤ 2 MiB). Nothing is saved until an app is chosen and confirmed. */
    fun readPlugin(uri: android.net.Uri) = viewModelScope.launch {
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().takeIf { b -> b.size <= 2 * 1024 * 1024 }?.toString(Charsets.UTF_8) } }.getOrNull()
        }
        val result = text?.let { com.nathanhanapps.wayd.capture.RewritePluginParser.parse(it.lineSequence()) }
        when {
            // Unreadable (or over 2 MiB) is told apart from "read, but no [Rewrite] rules", e.g. a block list picked here by mistake.
            result == null -> { notice.value = R.string.rw_import_unreadable; pending.value = null }
            result.rules.isEmpty() -> { notice.value = R.string.rw_import_none; pending.value = null }
            else -> { notice.value = null; pending.value = result }
        }
    }

    fun confirmImport(pkg: String) {
        val drafts = pending.value?.rules ?: return
        pending.value = null
        viewModelScope.launch {
            val n = runCatching { store.addAll(pkg, drafts) }.getOrDefault(0)
            runCatching { blocking.reloadRewrite() }
            imported.value = n
            notice.value = if (n == 0) R.string.rw_invalid else null
            refreshHits()
        }
    }

    fun cancelImport() { pending.value = null }

    val rules = store.rules
    val hits = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val notice = MutableStateFlow<Int?>(null)

    fun refreshHits() { hits.value = runCatching { blocking.rewriteHits() }.getOrDefault(emptyMap()) }

    private fun run(block: suspend () -> Boolean) = viewModelScope.launch {
        val ok = runCatching { block() }.getOrDefault(false)
        runCatching { blocking.reloadRewrite() }
        notice.value = if (ok) null else R.string.rw_invalid
        refreshHits()
    }

    fun add(pkg: String, host: String, subdomains: Boolean, method: String, match: String, pattern: String, action: String, arg: String) =
        run { store.add(pkg, host, subdomains, method, match, pattern, action, arg) != null }

    fun setEnabled(id: Long, on: Boolean) = run { store.setEnabled(id, on); true }

    fun delete(id: Long) = run { store.delete(id); true }
}

@Composable
fun RewriteSection(vm: RewriteViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val installed by vm.blocking.rewriteInstalled.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHits() }
    val pending by vm.pending.collectAsStateWithLifecycle()
    val imported by vm.imported.collectAsStateWithLifecycle()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(vm::readPlugin) }

    Column {
        InsetGroup(
            stringResource(R.string.rw_title), help = stringResource(R.string.rw_intro),
            trailing = { if (policy.running && installed == true) Text(stringResource(R.string.rw_active_short), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp)) },
        ) {
            if (policy.running && installed != true) {
                Text(stringResource(if (installed == null) R.string.rw_old_engine else R.string.rw_rejected), Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                GroupDivider()
            }
            rules.forEach { r ->
                var menu by remember(r.id) { mutableStateOf(false) }
                ItemRow(
                    actionText(r),
                    subtitle = "${r.method.ifEmpty { "*" }} ${if (r.subdomains) "*." else ""}${r.host}${if (r.match == "REGEX") " ~ " else ""}${r.pattern}",
                    caption = vm.labels.label(r.pkg) + " · " + stringResource(R.string.rw_hits, hits[r.id] ?: 0L),
                    onClick = { menu = true },
                    trailing = {
                        Switch(checked = r.enabled, onCheckedChange = { vm.setEnabled(r.id, it) })
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ai_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.block_remove)) }, onClick = { menu = false; vm.delete(r.id) })
                            }
                        }
                    },
                )
                GroupDivider()
            }
            ActionRow(Icons.Filled.Add, stringResource(R.string.rw_add)) { adding = true }
            GroupDivider()
            ActionRow(Icons.Filled.Add, stringResource(R.string.rw_import)) { pick.launch(arrayOf("*/*")) }
        }
        imported?.takeIf { it > 0 }?.let { Text(stringResource(R.string.rw_imported, it), Modifier.padding(start = 12.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(stringResource(it), Modifier.padding(start = 12.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    pending?.let { p ->
        var pkg by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(p.name ?: stringResource(R.string.rw_import), Modifier.weight(1f, fill = false)); HelpHint(stringResource(R.string.rw_consent)) } },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rw_import_summary, p.rules.size, p.skipped), style = MaterialTheme.typography.bodyMedium)
                    val hosts = (p.rules.map { (if (it.subdomains) "*." else "") + it.host } + p.mitmHosts).distinct()
                    Text(stringResource(R.string.rw_import_hosts, hosts.take(8).joinToString(", ") + if (hosts.size > 8) " …" else ""), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(pkg, { pkg = it.take(255) }, label = { Text(stringResource(R.string.rw_pkg)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmImport(pkg) }, enabled = com.nathanhanapps.wayd.capture.BlockTargets.packageName(pkg.trim())) { Text(stringResource(R.string.blist_import)) } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
    if (adding) AddRewriteDialog(onDismiss = { adding = false }) { pkg, host, sub, method, match, pattern, action, arg ->
        vm.add(pkg, host, sub, method, match, pattern, action, arg)
        adding = false
    }
}

@Composable
private fun actionText(r: RewriteRule): String = when (r.action) {
    "REJECT" -> stringResource(R.string.rw_action_reject) + " (" + r.arg + ")"
    "HEADER_REMOVE" -> stringResource(R.string.rw_action_header_remove) + ": " + r.arg
    "HEADER_SET" -> stringResource(R.string.rw_action_header_set) + ": " + r.arg
    "REDIRECT" -> stringResource(R.string.rw_action_redirect) + ": " + r.arg
    else -> stringResource(R.string.rw_action_query_remove) + ": " + r.arg
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AddRewriteDialog(onDismiss: () -> Unit, onAdd: (String, String, Boolean, String, String, String, String, String) -> Unit) {
    var pkg by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var subdomains by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf("") }
    var match by remember { mutableStateOf("PREFIX") }
    var pattern by remember { mutableStateOf("/") }
    var action by remember { mutableStateOf("REJECT") }
    var arg by remember { mutableStateOf("204") }
    val draft = RewriteRule(0, pkg.trim(), host.trim().lowercase(), subdomains, method.trim(), match, pattern.trim(), action, arg.trim(), true, 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.rw_add)); HelpHint(stringResource(R.string.rw_consent)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pkg, { pkg = it.take(255) }, label = { Text(stringResource(R.string.rw_pkg)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it.take(253) }, label = { Text(stringResource(R.string.rw_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(subdomains, { subdomains = !subdomains }, { Text(stringResource(R.string.rw_subdomains)) })
                    listOf("PREFIX" to R.string.rw_match_prefix, "EXACT" to R.string.rw_match_exact, "CONTAINS" to R.string.rw_match_contains, "REGEX" to R.string.rw_match_regex).forEach { (k, label) ->
                        FilterChip(match == k, { match = k }, { Text(stringResource(label)) })
                    }
                }
                OutlinedTextField(method, { method = it.take(16) }, label = { Text(stringResource(R.string.rw_method)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pattern, { pattern = it.take(2048) }, label = { Text(stringResource(if (match == "REGEX") R.string.rw_pattern_regex else R.string.rw_pattern)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("REJECT" to R.string.rw_action_reject, "HEADER_REMOVE" to R.string.rw_action_header_remove, "HEADER_SET" to R.string.rw_action_header_set,
                        "QUERY_REMOVE" to R.string.rw_action_query_remove, "REDIRECT" to R.string.rw_action_redirect).forEach { (k, label) ->
                        FilterChip(action == k, { action = k; arg = if (k == "REJECT") "204" else "" }, { Text(stringResource(label)) })
                    }
                }
                if (action == "REJECT") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("204", "200-empty", "200-json", "200-array").forEach { k -> FilterChip(arg == k, { arg = k }, { Text(k) }) }
                } else OutlinedTextField(arg, { arg = it.take(1200) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(when (action) { "HEADER_SET" -> R.string.rw_arg_header_set; "REDIRECT" -> R.string.rw_arg_redirect; else -> R.string.rw_arg_name })) })
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(pkg, host, subdomains, method, match, pattern, action, arg) }, enabled = draft.valid()) { Text(stringResource(R.string.cx_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.block_cancel)) } },
    )
}
