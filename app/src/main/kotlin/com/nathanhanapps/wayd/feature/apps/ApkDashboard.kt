package com.nathanhanapps.wayd.feature.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.FactIndex
import com.nathanhanapps.wayd.analysis.LabelRelations
import com.nathanhanapps.wayd.analysis.LabelRules
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.core.model.ConnectionRole
import com.nathanhanapps.wayd.core.model.PermissionMeaning
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.DashboardPanel
import com.nathanhanapps.wayd.feature.analysis.ItemLabelMarkers
import com.nathanhanapps.wayd.feature.analysis.LabelContext
import com.nathanhanapps.wayd.feature.analysis.LabelViewModel
import com.nathanhanapps.wayd.feature.analysis.ShareBar
import com.nathanhanapps.wayd.feature.analysis.StatGrid
import com.nathanhanapps.wayd.feature.analysis.StatTile
import com.nathanhanapps.wayd.feature.analysis.permissionText
import com.nathanhanapps.wayd.feature.analysis.roleText

/** What the summary panels need, computed once per scan snapshot instead of on every recomposition. */
private class ApkSummary(e: StaticEvidence) {
    val index = FactIndex(e.facts)
    val counts: Map<ApkFactKind, Int> = e.facts.filter { it.key != "cache" }.groupingBy { it.kind }.eachCount()
    val sensitive: List<PermissionMeaning> = index.declared.keys.filter { it in LabelRelations.SENSITIVE }.sortedBy { it.ordinal }
    val used: Set<PermissionMeaning> = e.ops.map(LabelRelations::opMeaning).toSet()
    val contacted: List<String> = e.hosts.mapNotNull(LabelRules::host).filter { it in index.endpoints }
    val roles: List<Pair<ConnectionRole, Long>> = index.endpoints.groupingBy { LabelRules.role(it, null) }.eachCount()
        .map { it.key to it.value.toLong() }.sortedByDescending { it.second }
    val sdkNames: List<String> = index.sdks.map { it.first.key }
}

/**
 * APK analysis as a dashboard (D-063): numbers, capability chips, endpoint purposes and SDKs first; every fact list stays one
 * tap away under its own collapsed header. Chips tie declared permissions to observed use and to today's related connections.
 */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.apkDashboard(e: StaticEvidence, labels: LabelContext?, vm: LabelViewModel, open: Set<ApkFactKind>, toggle: (ApkFactKind) -> Unit) {
    item(key = "apk-overview") {
        val s = remember(e) { ApkSummary(e) }
        val scheme = MaterialTheme.colorScheme
        val components = FactIndex.COMPONENTS.sumOf { s.counts[it] ?: 0 }
        val endpoints = s.counts[ApkFactKind.ENDPOINT] ?: 0
        DashboardPanel(stringResource(R.string.apk_overview)) {
            StatGrid(listOf(
                StatTile((s.counts[ApkFactKind.PERMISSION] ?: 0).toString(), stringResource(R.string.apk_tile_permissions)),
                StatTile(s.sensitive.size.toString(), stringResource(R.string.apk_tile_sensitive), scheme.error),
                StatTile(s.used.count { it in LabelRelations.SENSITIVE }.toString(), stringResource(R.string.apk_tile_used), scheme.tertiary),
                StatTile(components.toString(), stringResource(R.string.apk_tile_components)),
                StatTile(s.sdkNames.size.toString(), stringResource(R.string.apk_tile_sdks)),
                StatTile(endpoints.toString(), stringResource(R.string.apk_tile_endpoints, s.contacted.size), scheme.primary,
                    if (endpoints > 0) s.contacted.size.toFloat() / endpoints else null),
            ))
            Text(stringResource(R.string.apk_link_note), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
    item(key = "apk-capabilities") {
        val s = remember(e) { ApkSummary(e) }
        val scheme = MaterialTheme.colorScheme
        if (s.sensitive.isNotEmpty()) DashboardPanel(stringResource(R.string.apk_capabilities), s.sensitive.size.toString()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.sensitive.forEach { m ->
                    val related = s.index.declared[m]?.let { labels?.relatedHosts?.get(it.id)?.size } ?: 0
                    val used = m in s.used
                    val text = permissionText(m) + (if (used) " · " + stringResource(R.string.apk_chip_used) else "") +
                        if (related > 0) " · " + stringResource(R.string.apk_chip_related, related) else ""
                    Badge(text, if (used && related > 0) scheme.error else if (used) scheme.tertiary else scheme.secondary)
                }
            }
            Text(stringResource(R.string.apk_capabilities_note), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
    item(key = "apk-endpoints") {
        val s = remember(e) { ApkSummary(e) }
        val scheme = MaterialTheme.colorScheme
        if (s.roles.isNotEmpty()) DashboardPanel(stringResource(R.string.apk_endpoint_roles), s.roles.sumOf { it.second }.toString()) {
            val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error, scheme.outline, scheme.inversePrimary, scheme.primaryContainer, scheme.tertiaryContainer)
            val top = s.roles.take(7)
            ShareBar(top.map { roleText(it.first) to it.second }, top.indices.map { palette[it % palette.size] })
            if (s.contacted.isNotEmpty()) Text(stringResource(R.string.apk_observed_hosts, s.contacted.take(6).joinToString(", ")), style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
    }
    item(key = "apk-sdks") {
        val s = remember(e) { ApkSummary(e) }
        if (s.sdkNames.isNotEmpty()) DashboardPanel(stringResource(R.string.apk_tile_sdks), s.sdkNames.size.toString()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.sdkNames.take(16).forEach { Badge(it.take(32), MaterialTheme.colorScheme.outline) }
            }
        }
    }
    item(key = "apk-lists") { Text(stringResource(R.string.apk_lists_title), style = MaterialTheme.typography.titleSmall) }
    ApkFactKind.entries.forEach { kind ->
        val facts = e.facts.filter { it.kind == kind && it.key != "cache" }
        if (facts.isEmpty()) return@forEach
        item(key = "h-$kind") { KindHeader(kind, facts.size, kind in open) { toggle(kind) } }
        if (kind in open) items(facts, key = { it.id }) { fact -> FactRow(fact, fact.kind == ApkFactKind.ENDPOINT && fact.key in e.hosts, labels, vm) }
    }
}

@Composable
private fun KindHeader(kind: ApkFactKind, count: Int, open: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = toggle).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(kindLabel(kind), count), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
