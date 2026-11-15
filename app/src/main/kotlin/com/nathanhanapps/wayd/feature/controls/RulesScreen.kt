package com.nathanhanapps.wayd.feature.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.feature.analysis.StatGrid
import com.nathanhanapps.wayd.feature.analysis.StatTile
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.NavRow
import com.nathanhanapps.wayd.feature.common.PageHeader
import com.nathanhanapps.wayd.feature.common.SubPage
import com.nathanhanapps.wayd.feature.common.SwitchRow
import com.nathanhanapps.wayd.feature.extensions.ClashExtension
import com.nathanhanapps.wayd.feature.extensions.rememberClashInstalled
import android.widget.Toast
import androidx.compose.ui.res.vectorResource
import kotlinx.coroutines.launch

/**
 * Rules tab (D-071): one row per rule module, in the order the engine applies them: routing (clash variant only), DNS,
 * requests. Each row opens the module page; the switch turns the module off without touching its rules.
 */
@Composable
fun RulesScreen(onOpenDns: () -> Unit, onOpenRequest: () -> Unit, vm: ControlsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listsVm: BlockListsViewModel = hiltViewModel()
    val rewriteVm: RewriteViewModel = hiltViewModel()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val modules by vm.blocking.modules.state.collectAsStateWithLifecycle()
    val lists by listsVm.lists.collectAsStateWithLifecycle()
    val rewrites by rewriteVm.sets.collectAsStateWithLifecycle()
    val routeOn by vm.routing.enabled.collectAsStateWithLifecycle()
    val dnsVm: DnsRulesViewModel = hiltViewModel()
    val dns by dnsVm.state.collectAsStateWithLifecycle()
    val dnsRuleCount = dns.rules.count { it.enabled } + dns.sources.filter { it.enabled }.sumOf { it.applied }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(); scope.launch { vm.routing.refresh() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(stringResource(R.string.tab_rules), help = stringResource(R.string.rules_intro))
        InsetGroup {
            if (rememberClashInstalled()) {
                ModuleRow(
                    ImageVector.vectorResource(R.drawable.ic_cat), stringResource(R.string.dashboard_clash),
                    stringResource(if (routeOn) R.string.rules_on else R.string.rules_off),
                    checked = routeOn,
                    onToggle = { on ->
                        scope.launch {
                            vm.routing.setEnabled(on)?.let { code ->
                                Toast.makeText(context, context.resources.getString(R.string.rules_route_fail, code), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onClick = { ClashExtension.intent(context)?.let(context::startActivity) },
                )
                GroupDivider()
            }
            ModuleRow(
                ImageVector.vectorResource(R.drawable.dns_24), stringResource(R.string.rules_dns),
                if (modules.dns) stringResource(R.string.rules_dns_value, lists.count { it.enabled }, lists.size, dnsRuleCount) else stringResource(R.string.rules_off),
                checked = modules.dns, onToggle = { on -> scope.launch { runCatching { vm.blocking.setDnsModule(on) } } }, onClick = onOpenDns,
            )
            GroupDivider()
            ModuleRow(
                ImageVector.vectorResource(R.drawable.globe_2_question_24), stringResource(R.string.rules_request),
                stringResource(R.string.rules_request_value, policy.rules.count { it.id in policy.activeIds }, if (modules.request) rewrites.filter { s -> s.enabled && s.apps.any { it.enabled } }.sumOf { s -> s.rules.count { it.enabled } } else 0),
                checked = modules.request, switchLabel = stringResource(R.string.rw_title),
                onToggle = { on -> scope.launch { runCatching { vm.blocking.setRequestModule(on) } } }, onClick = onOpenRequest,
            )
        }
    }
}

/** DNS module: the master switch, this run's numbers, DNS rules and record types (D-072), then the block lists. */
@Composable
fun DnsRulesScreen(onBack: () -> Unit, vm: BlockListsViewModel = hiltViewModel(), rulesVm: DnsRulesViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val modules by vm.blocking.modules.state.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    val scheme = MaterialTheme.colorScheme
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { stats = runCatching { vm.blocking.dnsStats() }.getOrNull() }

    SubPage(stringResource(R.string.rules_dns), onBack) {
        InsetGroup {
            SwitchRow(Icons.Filled.Lock, stringResource(R.string.rules_dns_switch), modules.dns, help = stringResource(R.string.rules_dns_help)) { on ->
                scope.launch { runCatching { vm.blocking.setDnsModule(on) } }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
            Column(Modifier.padding(16.dp)) {
                StatGrid(
                    listOf(
                        StatTile("${lists.count { it.enabled }} / ${lists.size}", stringResource(R.string.control_tile_lists)),
                        StatTile(stats?.first?.toString() ?: "–", stringResource(R.string.control_tile_list_hits), scheme.error),
                        StatTile(stats?.second?.toString() ?: "–", stringResource(R.string.control_tile_dns)),
                        StatTile(stats?.third?.toString() ?: "–", stringResource(R.string.dnsr_tile)),
                    ),
                    columns = 2, labelLines = 1,
                )
            }
        }
        DnsRulesSection(rulesVm)
        BlockListsSection(vm)
    }
}

/** A module row: tapping the body opens the module, the switch on the right turns it on or off. */
@Composable
private fun ModuleRow(icon: ImageVector, title: String, value: String, checked: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit, switchLabel: String = title) {
    Row(Modifier.fillMaxWidth().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(onClick = onClick).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        VerticalDivider(Modifier.height(28.dp).padding(end = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Switch(checked, onToggle, modifier = Modifier.semantics { contentDescription = switchLabel })
    }
}
