package com.nathanhanapps.wayd.feature.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.BlockTargets
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.capture.RewriteSet
import com.nathanhanapps.wayd.capture.RewriteStore
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.common.ActionRow
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.ItemRow
import com.nathanhanapps.wayd.feature.common.SubPage
import com.nathanhanapps.wayd.feature.common.SwitchRow
import com.nathanhanapps.wayd.research.CandidateApp
import com.nathanhanapps.wayd.research.MitmController
import com.nathanhanapps.wayd.research.MitmExclusions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An app worth adding to a set: named by the plugin, or seen contacting the set's hosts in recorded traffic. */
data class AppSuggestion(val pkg: String, val fromPlugin: Boolean, val host: String? = null, val connections: Int = 0)

/** Whether [host] is covered by a rule host (exact, or a subdomain when [subdomains]). */
internal fun covers(ruleHost: String, subdomains: Boolean, host: String) = host == ruleHost || (subdomains && host.endsWith(".$ruleHost"))

/**
 * Suggestions for a set (D-073): packages the plugin names first, then apps whose recorded connections (last 30 days) reached
 * the set's rule hosts or `[MITM]` hosts, most connections first. Apps already in the set and WAYD itself are left out.
 */
internal fun suggestions(set: RewriteSet, counts: List<com.nathanhanapps.wayd.core.db.PkgHostCount>, self: String, limit: Int = 6): List<AppSuggestion> {
    val present = set.apps.map { it.pkg }.toSet() + self
    val hosts = set.rules.map { it.host to it.subdomains } + set.mitmHosts.map { it to true }
    val byPkg = counts.filter { c -> c.pkg !in present && hosts.any { (h, sub) -> covers(h, sub, c.host) } }.groupBy { it.pkg }
    val traffic = byPkg.map { (pkg, rows) -> AppSuggestion(pkg, false, rows.maxBy { it.n }.host, rows.sumOf { it.n }) }.sortedByDescending { it.connections }
    val plugin = set.suggested.filter { it !in present }.map { p -> AppSuggestion(p, true, traffic.firstOrNull { it.pkg == p }?.host, traffic.firstOrNull { it.pkg == p }?.connections ?: 0) }
    return (plugin + traffic.filter { t -> plugin.none { it.pkg == t.pkg } }).take(limit)
}

@HiltViewModel
class RewriteSetViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val store: RewriteStore,
    private val flows: FlowDao,
    private val mitm: MitmController,
    val blocking: BlockingRules,
    val labels: AppLabels,
) : ViewModel() {
    val id: Long = savedState.get<Long>("id") ?: -1L
    val set = store.sets.map { all -> all.firstOrNull { it.id == id } }.stateIn(viewModelScope, SharingStarted.Eagerly, store.sets.value.firstOrNull { it.id == id })
    val mitmState = mitm.state
    val hits = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val notice = MutableStateFlow<Int?>(null)
    val suggested = MutableStateFlow<List<AppSuggestion>>(emptyList())
    val candidates = MutableStateFlow<List<CandidateApp>>(emptyList())

    fun refresh() = viewModelScope.launch {
        hits.value = runCatching { blocking.rewriteHits() }.getOrDefault(emptyMap())
        val s = set.value ?: return@launch
        val counts = runCatching { flows.hostCounts(System.currentTimeMillis() - 30L * 24 * 3600 * 1000) }.getOrDefault(emptyList())
        suggested.value = suggestions(s, counts, context.packageName)
    }

    fun loadCandidates() = viewModelScope.launch { candidates.value = runCatching { mitm.candidateApps() }.getOrDefault(emptyList()).sortedBy { it.label.lowercase() } }

    /** Whether WAYD decrypts this app now, mirroring the decryption gate (allowlist, or blacklist mode). */
    fun decrypted(pkg: String): Boolean {
        val m = mitm.state.value
        return m.enabled && !MitmExclusions.isExcludedPackage(pkg) && (if (m.blacklist) pkg !in m.apps else pkg in m.apps)
    }

    private fun run(block: suspend () -> Boolean) = viewModelScope.launch {
        val ok = runCatching { block() }.getOrDefault(false)
        runCatching { blocking.reloadRewrite() }
        notice.value = if (ok) null else R.string.rw_limit
        refresh()
    }

    fun setEnabled(on: Boolean) = run { store.setEnabled(id, on) }
    fun rename(name: String) = run { store.rename(id, name) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        runCatching { store.deleteSet(id) }
        runCatching { blocking.reloadRewrite() }
        onDone()
    }
    fun addApp(pkg: String) = run { store.addApp(id, pkg) }
    fun setAppEnabled(pkg: String, on: Boolean) = run { store.setAppEnabled(id, pkg, on) }
    fun removeApp(pkg: String) = run { store.removeApp(id, pkg) }
    fun setRuleEnabled(ruleId: Long, on: Boolean) = run { store.setRuleEnabled(id, ruleId, on) }
    fun deleteRule(ruleId: Long) = run { store.deleteRule(id, ruleId) }
}

/** One rule set (D-073): its switch, target apps with suggestions, its rules, and rename / delete. */
@Composable
fun RewriteSetScreen(onBack: () -> Unit, onEditRule: (setId: Long, ruleId: Long?) -> Unit, vm: RewriteSetViewModel = hiltViewModel()) {
    val set by vm.set.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val suggested by vm.suggested.collectAsStateWithLifecycle()
    val mitm by vm.mitmState.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val s = set ?: run { LaunchedEffect(Unit) { onBack() }; return }
    val scheme = MaterialTheme.colorScheme

    SubPage(s.name, onBack) {
        InsetGroup { SwitchRow(Icons.Filled.Edit, stringResource(R.string.rw_set_enabled), s.enabled) { vm.setEnabled(it) } }

        InsetGroup(stringResource(R.string.rw_apps), help = stringResource(R.string.rw_consent)) {
            if (s.apps.isEmpty()) { ItemRow(stringResource(R.string.rw_apps_empty)); GroupDivider() }
            s.apps.forEach { a ->
                var menu by remember(a.pkg) { mutableStateOf(false) }
                // Read the decryption state so rows update when it changes.
                val decrypted = mitm.let { vm.decrypted(a.pkg) }
                ItemRow(
                    vm.labels.label(a.pkg), subtitle = a.pkg,
                    caption = if (decrypted) null else stringResource(R.string.rw_not_decrypted),
                    leading = { AppIcon(vm.labels, a.pkg, 32) },
                    onClick = { menu = true },
                    trailing = {
                        Switch(checked = a.enabled, onCheckedChange = { vm.setAppEnabled(a.pkg, it) })
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ai_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.block_remove)) }, onClick = { menu = false; vm.removeApp(a.pkg) })
                            }
                        }
                    },
                )
                GroupDivider()
            }
            ActionRow(Icons.Filled.Add, stringResource(R.string.rw_add_app)) { vm.loadCandidates(); adding = true }
        }

        if (suggested.isNotEmpty()) InsetGroup(stringResource(R.string.rw_suggested)) {
            suggested.forEachIndexed { i, g ->
                if (i > 0) GroupDivider()
                ItemRow(
                    vm.labels.label(g.pkg), subtitle = g.pkg,
                    caption = listOfNotNull(
                        stringResource(R.string.rw_suggest_plugin).takeIf { g.fromPlugin },
                        g.host?.let { stringResource(R.string.rw_suggest_traffic, it, g.connections) },
                    ).joinToString(" · "),
                    leading = { AppIcon(vm.labels, g.pkg, 32) },
                    trailing = { TextButton(onClick = { vm.addApp(g.pkg) }) { Text(stringResource(R.string.rw_add_app)) } },
                )
            }
        }

        InsetGroup(stringResource(R.string.rw_rules)) {
            s.rules.forEach { r ->
                var menu by remember(r.id) { mutableStateOf(false) }
                ItemRow(
                    actionText(r), subtitle = matchText(r),
                    caption = stringResource(R.string.rw_hits, hits[r.id] ?: 0L),
                    onClick = { onEditRule(s.id, r.id) },
                    trailing = {
                        Switch(checked = r.enabled, onCheckedChange = { vm.setRuleEnabled(r.id, it) })
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ai_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.block_remove)) }, onClick = { menu = false; vm.deleteRule(r.id) })
                            }
                        }
                    },
                )
                GroupDivider()
            }
            ActionRow(Icons.Filled.Add, stringResource(R.string.rw_add)) { onEditRule(s.id, null) }
        }

        if (s.mitmHosts.isNotEmpty()) InsetGroup(stringResource(R.string.rw_mitm_hosts), help = stringResource(R.string.rw_mitm_hosts_help)) {
            ItemRow(s.mitmHosts.take(20).joinToString(", ") + if (s.mitmHosts.size > 20) " …" else "")
        }

        notice?.let { Text(stringResource(it, com.nathanhanapps.wayd.capture.RewriteStore.MAX_ENGINE_LINES), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, color = scheme.error) }

        InsetGroup {
            ActionRow(Icons.Filled.Edit, stringResource(R.string.rw_rename)) { renaming = true }
            GroupDivider()
            ActionRow(Icons.Filled.Delete, stringResource(R.string.rw_delete_set), destructive = true) { deleting = true }
        }
    }

    if (adding) AddAppDialog(vm, s, onDismiss = { adding = false }) { vm.addApp(it); adding = false }
    if (renaming) NameDialog(stringResource(R.string.rw_rename), s.name, onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.rw_delete_set)) },
        text = { Text(stringResource(R.string.rw_delete_set_body, s.name, s.rules.size)) },
        confirmButton = { TextButton(onClick = { deleting = false; vm.delete(onBack) }) { Text(stringResource(R.string.block_remove), color = scheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.block_cancel)) } },
    )
}

/** Picks an app: suggestions first, then installed and recorded apps filtered by the search, or a typed package name. */
@Composable
private fun AddAppDialog(vm: RewriteSetViewModel, set: RewriteSet, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val suggested by vm.suggested.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val present = set.apps.map { it.pkg }.toSet()
    val q = query.trim()
    val shown = (suggested.map { it.pkg } + candidates.map { it.pkg }).distinct().filter { it !in present }
        .filter { q.isEmpty() || it.contains(q, true) || vm.labels.label(it).contains(q, true) }.take(80)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rw_add_app)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(255) }, label = { Text(stringResource(R.string.rw_search_apps)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (BlockTargets.packageName(q) && '.' in q && q !in present && q !in shown) item {
                        ItemRow(stringResource(R.string.rw_add_pkg, q), onClick = { onPick(q) })
                    }
                    items(shown, key = { it }) { p ->
                        ItemRow(vm.labels.label(p), subtitle = p, leading = { AppIcon(vm.labels, p, 28) }, onClick = { onPick(p) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.block_cancel)) } },
    )
}
