package com.nathanhanapps.wayd.feature.ai

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.ai.AiOutcome
import com.nathanhanapps.wayd.ai.AiResult
import com.nathanhanapps.wayd.ai.Certainty
import com.nathanhanapps.wayd.ai.ModelCatalog
import com.nathanhanapps.wayd.ai.ModelDownloads
import com.nathanhanapps.wayd.ai.ModelSpec
import com.nathanhanapps.wayd.ai.ModelState
import com.nathanhanapps.wayd.ai.Purpose
import com.nathanhanapps.wayd.ai.SuggestedAction
import com.nathanhanapps.wayd.ai.Verdict
import com.nathanhanapps.wayd.core.model.DataType
import com.nathanhanapps.wayd.feature.analysis.Badge

/** "Analyse with AI" on the app screen. The result is always labelled as the AI's opinion, apart from the rule findings. */
@Composable
fun AiSection(vm: AiAnalysisViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium)
        when (val s = state) {
            AiUiState.Idle -> Button(onClick = { vm.analyse() }) { Text(stringResource(R.string.ai_analyse)) }
            AiUiState.Running -> {
                Text(stringResource(R.string.ai_running), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm::cancel) { Text(stringResource(R.string.ai_cancel)) }
            }
            AiUiState.NoEvidence -> {
                Text(stringResource(R.string.ai_no_evidence), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { vm.analyse() }) { Text(stringResource(R.string.ai_retry)) }
            }
            is AiUiState.Done -> {
                AiOutcomeView(s.outcome)
                OutlinedButton(onClick = { vm.analyse(useCache = false) }) { Text(stringResource(R.string.ai_retry)) }
            }
        }
    }
}

/** Renders any analysis outcome: the AI opinion card, or why there is none. */
@Composable
fun AiOutcomeView(o: AiOutcome) {
    when (o) {
        is AiOutcome.Success -> AiResultCard(o)
        is AiOutcome.Unreliable -> Message(stringResource(R.string.ai_unreliable), error = true)
        AiOutcome.NoModel -> Message(stringResource(R.string.ai_no_model), error = false)
        is AiOutcome.Failed -> Message(stringResource(R.string.ai_failed, o.message.take(120)), error = true)
    }
}

@Composable
private fun Message(text: String, error: Boolean) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun AiResultCard(o: AiOutcome.Success) {
    val r: AiResult = o.result
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(stringResource(R.string.ai_opinion), MaterialTheme.colorScheme.secondary)
                Badge(verdictLabel(r.verdict), MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(r.summary, style = MaterialTheme.typography.bodyLarge)

            if (r.dataTypes.isNotEmpty()) {
                Text(stringResource(R.string.ai_data_types), style = MaterialTheme.typography.titleSmall)
                r.dataTypes.forEach { d ->
                    Text(
                        "• ${dataTypeLabel(d.type)} · ${certaintyLabel(d.certainty)} · ${d.evidence.joinToString(" ")}" + (d.note?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (r.destinations.isNotEmpty()) {
                Text(stringResource(R.string.ai_destinations), style = MaterialTheme.typography.titleSmall)
                r.destinations.forEach { d ->
                    Text(
                        "• ${d.host}" + (d.company?.let { " ($it)" } ?: "") + " · ${purposeLabel(d.purpose)} · ${d.evidence.joinToString(" ")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (r.timeline.isNotEmpty()) {
                Text(stringResource(R.string.ai_timeline), style = MaterialTheme.typography.titleSmall)
                r.timeline.forEach { t -> Text("• ${t.text} · ${t.evidence.joinToString(" ")}", style = MaterialTheme.typography.bodyMedium) }
            }
            if (r.actions.isNotEmpty()) {
                Text(stringResource(R.string.ai_actions), style = MaterialTheme.typography.titleSmall)
                r.actions.forEach { a -> Text("• ${actionLabel(a.action)} ${a.target} — ${a.reason}", style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.ai_actions_note), style = MaterialTheme.typography.labelSmall)
            }
            if (r.openQuestions.isNotEmpty()) {
                Text(stringResource(R.string.ai_questions), style = MaterialTheme.typography.titleSmall)
                r.openQuestions.forEach { q -> Text("• $q", style = MaterialTheme.typography.bodyMedium) }
            }
            Text(
                stringResource(R.string.ai_footer, (r.confidence * 100).toInt(), o.model, if (o.fromCache) stringResource(R.string.ai_cached) else "%.1f s".format(o.durationMs / 1000.0)) +
                    if (o.stripped > 0) " · " + stringResource(R.string.ai_stripped, o.stripped) else "",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun verdictLabel(v: Verdict) = stringResource(
    when (v) {
        Verdict.BENIGN -> R.string.ai_v_benign
        Verdict.EXPECTED_TELEMETRY -> R.string.ai_v_telemetry
        Verdict.EXCESSIVE -> R.string.ai_v_excessive
        Verdict.LIKELY_LEAK -> R.string.ai_v_leak
        Verdict.UNKNOWN -> R.string.ai_v_unknown
    },
)

@Composable
private fun certaintyLabel(c: Certainty) = stringResource(
    when (c) {
        Certainty.SEEN -> R.string.ai_c_seen
        Certainty.DECRYPTED -> R.string.ai_c_decrypted
        Certainty.INFERRED -> R.string.ai_c_inferred
    },
)

@Composable
private fun purposeLabel(p: Purpose) = stringResource(
    when (p) {
        Purpose.ANALYTICS -> R.string.ai_p_analytics
        Purpose.ADS -> R.string.ai_p_ads
        Purpose.CARRIER -> R.string.ai_p_carrier
        Purpose.OS -> R.string.ai_p_os
        Purpose.CRASH -> R.string.ai_p_crash
        Purpose.PUSH -> R.string.ai_p_push
        Purpose.FUNCTIONAL -> R.string.ai_p_functional
        Purpose.UNKNOWN -> R.string.ai_p_unknown
    },
)

@Composable
private fun actionLabel(a: SuggestedAction) = stringResource(
    when (a) {
        SuggestedAction.BLOCK_HOST -> R.string.ai_a_block_host
        SuggestedAction.BLOCK_APP -> R.string.ai_a_block_app
        SuggestedAction.REVOKE_PERMISSION -> R.string.ai_a_revoke
        SuggestedAction.DISABLE_APP -> R.string.ai_a_disable
        SuggestedAction.IGNORE -> R.string.ai_a_ignore
    },
)

@Composable
fun dataTypeLabel(t: DataType) = stringResource(
    when (t) {
        DataType.DEVICE_ID -> R.string.ai_dt_device_id
        DataType.ADVERTISING_ID -> R.string.ai_dt_ad_id
        DataType.ANDROID_ID -> R.string.ai_dt_android_id
        DataType.IMEI_SERIAL -> R.string.ai_dt_imei
        DataType.PHONE_NUMBER -> R.string.ai_dt_phone
        DataType.LOCATION_PRECISE -> R.string.ai_dt_loc_precise
        DataType.LOCATION_COARSE -> R.string.ai_dt_loc_coarse
        DataType.WIFI_BSSID -> R.string.ai_dt_wifi
        DataType.APP_LIST -> R.string.ai_dt_app_list
        DataType.APP_USAGE -> R.string.ai_dt_app_usage
        DataType.CONTACTS -> R.string.ai_dt_contacts
        DataType.SMS -> R.string.ai_dt_sms
        DataType.CLIPBOARD -> R.string.ai_dt_clipboard
        DataType.SCREEN_CONTENT -> R.string.ai_dt_screen
        DataType.FILES_MEDIA -> R.string.ai_dt_files
        DataType.ACCOUNT -> R.string.ai_dt_account
        DataType.NETWORK_INFO -> R.string.ai_dt_network
        DataType.OTHER -> R.string.ai_dt_other
    },
)

/** Settings → On-device AI: pick, download and remove Gemma models. Everything downloaded stays on the phone. */
@Composable
fun AiSettingsSection(vm: AiSettingsViewModel = hiltViewModel()) {
    val states by vm.states.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val recommended = ModelCatalog.recommended(vm.ramGb)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.ai_settings_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.ai_settings_intro), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.ai_settings_ram, "%.1f".format(vm.ramGb)) +
                (recommended?.let { " · " + stringResource(R.string.ai_settings_recommended, it.displayName) } ?: " · " + stringResource(R.string.ai_settings_low_ram)),
            style = MaterialTheme.typography.bodySmall,
        )
        ModelCatalog.all.forEach { spec ->
            ModelCard(spec, states[spec.id] ?: ModelState.NotInstalled, vm, ctx)
        }
    }
}

@Composable
private fun ModelCard(spec: ModelSpec, state: ModelState, vm: AiSettingsViewModel, ctx: android.content.Context) {
    var url by remember(spec.id) { mutableStateOf(vm.url(spec)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                spec.displayName + " · " + Formatter.formatShortFileSize(ctx, spec.sizeBytes),
                style = MaterialTheme.typography.titleSmall,
            )
            when (state) {
                ModelState.NotInstalled, is ModelState.Failed -> {
                    (state as? ModelState.Failed)?.let { Text(stringResource(R.string.ai_state_failed, it.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.ai_source), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        spec.sources.forEach { src ->
                            OutlinedButton(onClick = { url = src.url; vm.setUrl(spec, src.url) }) { Text(src.label) }
                        }
                    }
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; vm.setUrl(spec, it) },
                        label = { Text(stringResource(R.string.ai_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { ModelDownloads.start(ctx, spec, url) }, enabled = url.startsWith("https://")) {
                        Text(stringResource(R.string.ai_download))
                    }
                }
                is ModelState.Downloading -> {
                    LinearProgressIndicator(progress = { (state.doneBytes.toFloat() / state.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        Formatter.formatShortFileSize(ctx, state.doneBytes) + " / " + Formatter.formatShortFileSize(ctx, state.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ModelDownloads.cancel(ctx) }) { Text(stringResource(R.string.ai_cancel)) }
                        OutlinedButton(onClick = { ModelDownloads.start(ctx, spec, url) }) { Text(stringResource(R.string.ai_resume)) }
                    }
                }
                ModelState.Verifying -> Text(stringResource(R.string.ai_state_verifying), style = MaterialTheme.typography.bodyMedium)
                is ModelState.Ready -> {
                    Text(
                        stringResource(if (vm.active == spec.id) R.string.ai_state_active else R.string.ai_state_installed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.active != spec.id) Button(onClick = { vm.use(spec) }) { Text(stringResource(R.string.ai_use)) }
                        OutlinedButton(onClick = { vm.delete(spec) }) { Text(stringResource(R.string.ai_delete)) }
                    }
                }
            }
        }
    }
}
