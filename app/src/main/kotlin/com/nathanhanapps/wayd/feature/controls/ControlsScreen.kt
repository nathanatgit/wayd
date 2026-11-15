package com.nathanhanapps.wayd.feature.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import com.nathanhanapps.wayd.feature.common.SubPage
import com.nathanhanapps.wayd.feature.common.SwitchRow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.nathanhanapps.wayd.feature.analysis.StatGrid
import com.nathanhanapps.wayd.feature.analysis.StatTile
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.HelpHint
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.ItemRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
class ControlsViewModel @Inject constructor(val blocking: BlockingRules, val labels: AppLabels, val routing: com.nathanhanapps.wayd.capture.RoutingBridge) : ViewModel() {
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

/**
 * Request module (D-071): per-app connection blocks and rewrite rules, independent of report windows and per-app history.
 * Active IDs are native-acknowledged. The module switch gates rewrites only; connection blocks always apply.
 */
@Composable
fun RequestRulesScreen(onBack: () -> Unit, onOpenApp: (String) -> Unit, onOpenRewriteSet: (Long) -> Unit, vm: ControlsViewModel = hiltViewModel()) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val modules by vm.blocking.modules.state.collectAsStateWithLifecycle()
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

    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    val rewriteVm: RewriteViewModel = hiltViewModel()
    val rewrites by rewriteVm.sets.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    SubPage(stringResource(R.string.rules_request), onBack) {
        InsetGroup {
            SwitchRow(Icons.Filled.Edit, stringResource(R.string.rw_title), modules.request, help = stringResource(R.string.rules_request_help)) { on ->
                scope.launch { runCatching { vm.blocking.setRequestModule(on) } }
            }
        }
        // Summary in the dashboard style: one status line and the numbers of this module.
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (policy.running) scheme.primary else scheme.outline))
                    Text(stringResource(if (policy.running) R.string.control_vpn_on else R.string.block_vpn_off), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (!policy.running) HelpHint(stringResource(R.string.block_vpn_off_help))
                    IconButton(onClick = { vm.refresh() }, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.control_refresh))
                    }
                }
                StatGrid(
                    listOf(
                        StatTile("$effective / ${policy.rules.size}", stringResource(R.string.control_tile_rules)),
                        StatTile(if (modules.request) "${rewrites.count { it.enabled }} / ${rewrites.size}" else stringResource(R.string.rules_off), stringResource(R.string.rw_tile_sets)),
                    ),
                    columns = 2, labelLines = 1,
                )
                notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
        // Rewrite rules change decrypted requests; the per-app rules below block whole connections.
        RewriteSection(onOpenRewriteSet, rewriteVm)
        run {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(253) },
                    placeholder = { Text(stringResource(R.string.control_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(28.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ALL" to R.string.control_all, "EFFECTIVE" to R.string.control_effective, "INACTIVE" to R.string.control_inactive).forEach { (key, label) ->
                        FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(stringResource(label)) })
                    }
                }
                InsetGroup(stringResource(R.string.block_rules_title), help = stringResource(R.string.block_coverage)) {
                    when {
                        !ready && busy -> ItemRow(stringResource(R.string.control_loading))
                        shown.isEmpty() -> ItemRow(stringResource(if (policy.rules.isEmpty()) R.string.block_rules_empty else R.string.control_no_matches))
                    }
                    shown.forEachIndexed { i, rule ->
                        if (i > 0) GroupDivider()
                        val active = rule.id in policy.activeIds
                        ItemRow(
                            rule.pkg?.let(vm.labels::label).orEmpty().ifBlank { rule.pkg.orEmpty() },
                            subtitle = if (rule.matchKind == "APP") stringResource(R.string.block_app) else rule.host.orEmpty(),
                            caption = stringResource(ruleState(rule, active)) + " · " + stringResource(if (rule.id < 0) R.string.block_session else R.string.block_persistent),
                            leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) scheme.primary else if (rule.needsImportReview) scheme.tertiary else scheme.outline)) },
                            onClick = { detailId = rule.id },
                        )
                    }
                }
            }
        }
        PolicyTransferSection()
    }
    policy.rules.firstOrNull { it.id == detailId }?.let { rule ->
        AlertDialog(
            onDismissRequest = { detailId = null },
            title = { Text(rule.pkg?.let(vm.labels::label).orEmpty().ifBlank { rule.pkg.orEmpty() }) },
            text = { RuleDetails(rule, rule.id in policy.activeIds, policy.running) },
            confirmButton = {
                Row {
                    if (rule.needsImportReview) TextButton(onClick = { detailId = null; reviewingId = rule.id }, enabled = !busy) { Text(stringResource(R.string.policy_review)) }
                    TextButton(onClick = { detailId = null; rule.pkg?.let(onOpenApp) }, enabled = !rule.pkg.isNullOrBlank()) { Text(stringResource(R.string.control_app_history)) }
                    TextButton(onClick = { detailId = null; removingId = rule.id }, enabled = !busy) { Text(stringResource(R.string.block_remove), color = scheme.error) }
                }
            },
        )
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

/** Short state for the rule list; the details dialog carries the full reason. */
private fun ruleState(rule: BlockRule, active: Boolean): Int = when {
    active -> R.string.control_effective
    rule.needsImportReview -> R.string.control_state_review
    !rule.enabled -> R.string.control_state_disabled
    else -> R.string.control_inactive
}

@Composable
private fun RuleDetails(rule: BlockRule, effective: Boolean, running: Boolean) {
    val targetLabel = when (rule.matchKind) {
        "APP" -> R.string.control_target_app
        "HOST" -> R.string.control_target_host
        "IP" -> R.string.control_target_ip
        else -> R.string.control_target_unknown
    }
    val status = when {
        effective -> R.string.control_status_effective
        rule.needsImportReview -> R.string.policy_pending_review
        !rule.enabled -> R.string.control_status_disabled
        !running -> R.string.control_status_waiting
        else -> R.string.control_status_invalid
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(rule.pkg.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(targetLabel), style = MaterialTheme.typography.labelLarge)
            if (rule.matchKind != "APP") Text(rule.host.orEmpty())
            Text(stringResource(status), color = if (effective) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.control_rule_id, rule.id, rule.userId), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.control_created, DateFormat.getDateTimeInstance().format(Date(rule.createdAt))), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.control_identity, rule.signingSha256.ifEmpty { stringResource(R.string.control_unbound) }), style = MaterialTheme.typography.bodySmall)
        }
    }
}
