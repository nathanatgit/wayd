package com.nathanhanapps.wayd.feature.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.nathanhanapps.wayd.core.db.BlockRule
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class ControlsViewModel @Inject constructor(val blocking: BlockingRules, val labels: AppLabels) : ViewModel() {
    val busy = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val notice = MutableStateFlow<Int?>(null)

    fun refresh() = update(R.string.control_refresh_error) { blocking.refresh() }

    fun remove(id: Long) = update(R.string.block_error) {
        blocking.remove(id)
        notice.value = R.string.block_removed
    }

    fun approveImport(id: Long) = update(R.string.policy_review_failed) { blocking.approveImport(id) }

    private fun update(error: Int, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        notice.value = null
        viewModelScope.launch {
            try {
                action()
                ready.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice.value = error
            } finally {
                busy.value = false
            }
        }
    }
}

/** One inventory, independent of report windows and per-app history. Active IDs are native-acknowledged. */
@Composable
fun ControlsScreen(onOpenApp: (String) -> Unit, vm: ControlsViewModel = hiltViewModel()) {
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var removingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reviewingId by rememberSaveable { mutableStateOf<Long?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val effective = policy.rules.count { it.id in policy.activeIds }
    val shown = policy.rules.filter { rule ->
        (filter == "ALL" || (rule.id in policy.activeIds) == (filter == "EFFECTIVE")) &&
            (query.isBlank() || rule.pkg.orEmpty().contains(query, true) || rule.host.orEmpty().contains(query, true))
    }.sortedWith(compareByDescending<BlockRule> { it.id in policy.activeIds }.thenByDescending { it.createdAt })

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Text(stringResource(R.string.control_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.control_intro), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.control_summary, effective, policy.rules.size), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (policy.running) R.string.control_vpn_on else R.string.block_vpn_off), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.block_coverage), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::refresh, enabled = !busy) { Text(stringResource(R.string.control_refresh)) }
                    notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            PolicyTransferSection()
        }
        item {
            Text(stringResource(R.string.block_rules_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(253) },
                label = { Text(stringResource(R.string.control_search)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to R.string.control_all, "EFFECTIVE" to R.string.control_effective, "INACTIVE" to R.string.control_inactive).forEach { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(stringResource(label)) })
                }
            }
            if (!ready && busy) Text(stringResource(R.string.control_loading))
            else if (ready && shown.isEmpty()) Text(stringResource(if (policy.rules.isEmpty()) R.string.block_rules_empty else R.string.control_no_matches))
        }
        items(shown, key = { it.id }) { rule ->
            RuleCard(
                rule, rule.id in policy.activeIds, policy.running, busy,
                appLabel = rule.pkg?.let(vm.labels::label).orEmpty(),
                onOpenApp = { rule.pkg?.let(onOpenApp) },
                onRemove = { removingId = rule.id },
                onReview = { reviewingId = rule.id },
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.control_alterations), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.control_alterations_unavailable), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    policy.rules.firstOrNull { it.id == removingId }?.let { rule ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text(stringResource(R.string.control_remove_title)) },
            text = { Column {
                Text(rule.pkg.orEmpty())
                Text(if (rule.matchKind == "APP") stringResource(R.string.block_app) else rule.host.orEmpty())
                Text(stringResource(R.string.control_remove_warning))
            } },
            confirmButton = { TextButton(onClick = { vm.remove(rule.id); removingId = null }, enabled = !busy) { Text(stringResource(R.string.block_remove)) } },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
    policy.rules.firstOrNull { it.id == reviewingId && it.needsImportReview }?.let { rule ->
        AlertDialog(
            onDismissRequest = { reviewingId = null },
            title = { Text(stringResource(R.string.policy_review)) },
            text = { Column {
                Text(rule.pkg.orEmpty())
                Text(rule.host ?: stringResource(R.string.policy_redacted))
                Text(stringResource(R.string.control_identity, rule.signingSha256.ifEmpty { stringResource(R.string.control_unbound) }))
                Text(stringResource(R.string.policy_review_warning))
            } },
            confirmButton = { TextButton(onClick = { vm.approveImport(rule.id); reviewingId = null }, enabled = !busy && rule.host != null) {
                Text(stringResource(R.string.policy_review_confirm))
            } },
            dismissButton = { TextButton(onClick = { reviewingId = null }) { Text(stringResource(R.string.export_cancel)) } },
        )
    }
}

@Composable
private fun RuleCard(rule: BlockRule, effective: Boolean, running: Boolean, busy: Boolean, appLabel: String, onOpenApp: () -> Unit, onRemove: () -> Unit, onReview: () -> Unit) {
    var details by rememberSaveable(rule.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(appLabel.ifBlank { rule.pkg.orEmpty() }, style = MaterialTheme.typography.titleMedium)
            Text(rule.pkg.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.control_block_action), style = MaterialTheme.typography.labelLarge)
            val targetLabel = when (rule.matchKind) {
                "APP" -> R.string.control_target_app
                "HOST" -> R.string.control_target_host
                "IP" -> R.string.control_target_ip
                else -> R.string.control_target_unknown
            }
            Text(stringResource(targetLabel), style = MaterialTheme.typography.labelMedium)
            if (rule.matchKind != "APP") SelectionContainer { Text(rule.host.orEmpty()) }
            Text(stringResource(if (rule.id < 0) R.string.block_session else R.string.block_persistent), style = MaterialTheme.typography.bodySmall)
            val status = when {
                effective -> R.string.control_status_effective
                rule.needsImportReview -> R.string.policy_pending_review
                !rule.enabled -> R.string.control_status_disabled
                !running -> R.string.control_status_waiting
                else -> R.string.control_status_invalid
            }
            Text(stringResource(status), color = if (effective) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = !details }) { Text(stringResource(if (details) R.string.control_hide_details else R.string.control_details)) }
            if (details) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.control_rule_id, rule.id, rule.userId))
                    Text(stringResource(R.string.control_created, DateFormat.getDateTimeInstance().format(Date(rule.createdAt))))
                    Text(stringResource(R.string.control_identity, rule.signingSha256.ifEmpty { stringResource(R.string.control_unbound) }))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenApp, enabled = !rule.pkg.isNullOrBlank()) { Text(stringResource(R.string.control_app_history)) }
                TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.block_remove)) }
            }
            if (rule.needsImportReview) TextButton(onClick = onReview, enabled = !busy) { Text(stringResource(R.string.policy_review)) }
        }
    }
}
