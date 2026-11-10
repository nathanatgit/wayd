package com.nathanhanapps.wayd.feature.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.core.db.ApkFact
import com.nathanhanapps.wayd.core.model.ApkFactKind
import java.text.DateFormat
import java.util.Date

@Composable
fun ApkScanScreen(onBack: () -> Unit, vm: ApkScanViewModel = hiltViewModel()) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val limited by vm.limited.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val evidence by vm.evidence.collectAsStateWithLifecycle()
    val work by vm.work.collectAsStateWithLifecycle()
    val busy = work?.state?.isFinished == false
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (selected.isNotEmpty()) vm.select("") else onBack() }) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.apk_title), style = MaterialTheme.typography.headlineSmall)
        }
        Text(stringResource(R.string.apk_limits), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.scan(selected.ifEmpty { null }) }, enabled = !busy) {
                Text(stringResource(if (selected.isEmpty()) R.string.apk_scan_all else R.string.apk_rescan))
            }
            if (busy) TextButton(onClick = vm::cancel) { Text(stringResource(R.string.apk_cancel)) }
        }
        work?.let { info ->
            when {
                busy -> Text(stringResource(R.string.apk_progress, info.progress.getInt("done", 0), info.progress.getInt("total", 0), info.progress.getInt("failed", 0)))
                info.state == WorkInfo.State.SUCCEEDED -> Text(stringResource(R.string.apk_finished,
                    info.outputData.getInt("scanned", 0), info.outputData.getInt("cached", 0), info.outputData.getInt("failed", 0), info.outputData.getLong("seconds", 0)))
                info.state == WorkInfo.State.CANCELLED -> Text(stringResource(R.string.apk_cancelled))
                else -> Text(stringResource(R.string.apk_failed))
            }
        }
        if (selected.isEmpty()) {
            OutlinedTextField(query, { query = it.take(255) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.apk_search)) }, singleLine = true)
            if (query.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
                TextButton(onClick = { vm.select(query); vm.scan(query) }, enabled = !busy) { Text(stringResource(R.string.apk_scan_package, query)) }
            }
            Text(stringResource(R.string.apk_results, apps.size))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps.filter { query.isBlank() || it.pkg.contains(query, true) || it.label.contains(query, true) || it.signerSubject?.contains(query, true) == true }, key = { it.pkg }) { app ->
                    Card(Modifier.fillMaxWidth().clickable { vm.select(app.pkg) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(if (app.isSystem) R.string.apk_system else R.string.apk_user), style = MaterialTheme.typography.labelSmall)
                            if (app.pkg in limited) Text(stringResource(R.string.apk_partial), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                            app.signerSubject?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        } else {
            Text(evidence?.app?.label ?: selected, style = MaterialTheme.typography.titleLarge)
            Text(selected, style = MaterialTheme.typography.bodySmall)
            val e = evidence
            if (e == null || e.facts.isEmpty()) Text(stringResource(R.string.apk_not_scanned))
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                e.app?.lastScanned?.let { time -> item { Text(stringResource(R.string.apk_scanned_at, DateFormat.getDateTimeInstance().format(Date(time)))) } }
                item { Text(stringResource(R.string.apk_signer_note), style = MaterialTheme.typography.bodySmall) }
                item { EvidenceSummary(e) }
                ApkFactKind.entries.forEach { kind ->
                    val facts = e.facts.filter { it.kind == kind && it.key != "cache" }
                    if (facts.isNotEmpty()) {
                        item { Text(stringResource(kindLabel(kind), facts.size), style = MaterialTheme.typography.titleMedium) }
                        items(facts, key = { it.id }) { fact -> FactRow(fact, fact.kind == ApkFactKind.ENDPOINT && fact.key in e.hosts) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceSummary(e: StaticEvidence) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.apk_declared to e.facts.count { it.kind != ApkFactKind.COVERAGE }, R.string.apk_used to e.ops.size, R.string.apk_sent to e.hosts.size).forEach { (label, count) ->
                    Column(Modifier.weight(1f)) { Text(stringResource(label)); Text(count.toString(), style = MaterialTheme.typography.titleLarge) }
                }
            }
            Text(stringResource(R.string.apk_link_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.apk_observed_ops, e.ops.joinToString(", ").ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.apk_observed_hosts, e.hosts.joinToString(", ").ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FactRow(fact: ApkFact, contacted: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        if (fact.kind == ApkFactKind.COVERAGE) {
            val label = when {
                fact.key == "dex_files_seen" -> R.string.apk_dex_count
                fact.value == "no_dex_odex_or_packed" -> R.string.apk_no_dex
                fact.value?.let { "cap" in it || "budget" in it || "size" in it } == true -> R.string.apk_cap
                else -> R.string.apk_read_gap
            }
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        } else Text(fact.key, style = MaterialTheme.typography.bodyMedium)
        fact.value?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        fact.evidence?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        if (contacted) Text(stringResource(R.string.apk_contacted), color = MaterialTheme.colorScheme.primary)
    }
}

private fun kindLabel(kind: ApkFactKind): Int = when (kind) {
    ApkFactKind.PERMISSION -> R.string.apk_permissions
    ApkFactKind.RECEIVER -> R.string.apk_receivers
    ApkFactKind.SERVICE -> R.string.apk_services
    ApkFactKind.PROVIDER -> R.string.apk_providers
    ApkFactKind.SDK -> R.string.apk_sdks
    ApkFactKind.ENDPOINT -> R.string.apk_endpoints
    ApkFactKind.NSC -> R.string.apk_nsc
    ApkFactKind.NATIVE_LIB -> R.string.apk_native
    ApkFactKind.PACKER -> R.string.apk_packers
    ApkFactKind.MANIFEST -> R.string.apk_manifest
    ApkFactKind.SIGNER -> R.string.apk_signers
    ApkFactKind.COVERAGE -> R.string.apk_coverage
}
