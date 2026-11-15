package com.nathanhanapps.wayd.feature.controls

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
class RewriteViewModel @Inject constructor(private val store: RewriteStore, val blocking: BlockingRules, val labels: AppLabels) : ViewModel() {
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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rw_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rw_intro), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(when {
                    !policy.running -> R.string.rw_vpn_off
                    installed == null -> R.string.rw_old_engine
                    installed == false -> R.string.rw_rejected
                    else -> R.string.rw_active
                }),
                style = MaterialTheme.typography.labelMedium,
                color = if (policy.running && installed != true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { adding = true }) { Text(stringResource(R.string.rw_add)) }
            notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            rules.forEach { r ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(actionText(r), style = MaterialTheme.typography.bodyLarge)
                        Text("${r.method.ifEmpty { "*" }} ${if (r.subdomains) "*." else ""}${r.host}${r.pattern}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(vm.labels.label(r.pkg) + " · " + stringResource(R.string.rw_hits, hits[r.id] ?: 0L), style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = r.enabled, onCheckedChange = { vm.setEnabled(r.id, it) })
                }
                TextButton(onClick = { vm.delete(r.id) }) { Text(stringResource(R.string.block_remove)) }
            }
        }
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
        title = { Text(stringResource(R.string.rw_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rw_consent), style = MaterialTheme.typography.bodySmall)
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
                    listOf("204", "200-empty", "200-json").forEach { k -> FilterChip(arg == k, { arg = k }, { Text(k) }) }
                } else OutlinedTextField(arg, { arg = it.take(1200) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(when (action) { "HEADER_SET" -> R.string.rw_arg_header_set; "REDIRECT" -> R.string.rw_arg_redirect; else -> R.string.rw_arg_name })) })
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(pkg, host, subdomains, method, match, pattern, action, arg) }, enabled = draft.valid()) { Text(stringResource(R.string.cx_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.block_cancel)) } },
    )
}
