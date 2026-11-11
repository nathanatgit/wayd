package com.nathanhanapps.wayd.feature.ai

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
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
                val phase by vm.phase.collectAsStateWithLifecycle()
                PhaseNotice(phase)
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

/** Explains the wait: the first run compiles the model for this phone's GPU and can take minutes; later runs only load it. */
@Composable
fun PhaseNotice(phase: com.nathanhanapps.wayd.ai.EnginePhase) {
    Text(
        stringResource(
            when (phase) {
                com.nathanhanapps.wayd.ai.EnginePhase.PREPARING_FIRST_RUN -> R.string.ai_phase_first
                com.nathanhanapps.wayd.ai.EnginePhase.LOADING -> R.string.ai_phase_loading
                else -> R.string.ai_running
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Renders any analysis outcome: the AI opinion card, or why there is none. */
@Composable
fun AiOutcomeView(o: AiOutcome) {
    when (o) {
        is AiOutcome.Success -> AiResultCard(o)
        is AiOutcome.Unreliable -> Message(stringResource(R.string.ai_unreliable), error = true)
        AiOutcome.NoModel -> Message(stringResource(R.string.ai_no_model), error = false)
        is AiOutcome.Failed -> Message(
            if (o.message == "model_mismatch") stringResource(R.string.ai_model_mismatch_failed)
            else stringResource(R.string.ai_failed, if (o.message.startsWith("ext_")) externalErrorLabel(o.message) else o.message.take(120)),
            error = true,
        )
        AiOutcome.Declined -> Message(stringResource(R.string.xg_declined), error = false)
    }
}

@Composable
private fun Message(text: String, error: Boolean) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

/** Verdict colour is decoration only; the verdict is always spelled out. It comes from the model, so it never touches rule severity. */
@Composable
private fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.BENIGN -> MaterialTheme.colorScheme.primary
    Verdict.EXPECTED_TELEMETRY -> MaterialTheme.colorScheme.secondary
    Verdict.EXCESSIVE -> MaterialTheme.colorScheme.tertiary
    Verdict.LIKELY_LEAK -> MaterialTheme.colorScheme.error
    Verdict.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
private fun AiResultCard(o: AiOutcome.Success) {
    val r: AiResult = o.result
    val accent = verdictColor(r.verdict)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Verdict banner
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.14f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(stringResource(R.string.ai_opinion), MaterialTheme.colorScheme.secondary)
                    Text(verdictLabel(r.verdict), style = MaterialTheme.typography.titleLarge, color = accent)
                }
                Text(r.summary, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { r.confidence.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f), color = accent)
                    Text(stringResource(R.string.ai_confidence, (r.confidence * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (r.dataTypes.isNotEmpty()) AiBlock(stringResource(R.string.ai_data_types)) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.dataTypes.forEach { d ->
                    // Decrypted content is the strongest evidence (solid), seen metadata is tonal, inference is outlined.
                    val label = dataTypeLabel(d.type) + " · " + certaintyLabel(d.certainty)
                    when (d.certainty) {
                        Certainty.DECRYPTED -> AssistChip(onClick = {}, label = { Text(label) }, colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
                        Certainty.SEEN -> AssistChip(onClick = {}, label = { Text(label) }, colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer))
                        Certainty.INFERRED -> AssistChip(onClick = {}, label = { Text(label) })
                    }
                }
            }
            r.dataTypes.filter { it.note != null }.forEach { d -> Text("${dataTypeLabel(d.type)}: ${d.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (r.destinations.isNotEmpty()) AiBlock(stringResource(R.string.ai_destinations)) {
            r.destinations.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(d.host, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(d.company, d.evidence.takeIf { it.isNotEmpty() }?.joinToString(" ")).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Badge(purposeLabel(d.purpose), purposeColor(d.purpose))
                }
            }
        }

        if (r.timeline.isNotEmpty()) AiBlock(stringResource(R.string.ai_timeline)) {
            val line = MaterialTheme.colorScheme.outlineVariant
            val dot = MaterialTheme.colorScheme.primary
            r.timeline.forEachIndexed { i, t ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Canvas(Modifier.width(12.dp).fillMaxHeight()) {
                        if (i < r.timeline.lastIndex) drawLine(line, Offset(size.width / 2, 10f), Offset(size.width / 2, size.height), strokeWidth = 3f)
                        drawCircle(dot, radius = 5f, center = Offset(size.width / 2, 10f))
                    }
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Text(t.text, style = MaterialTheme.typography.bodyMedium)
                        if (t.evidence.isNotEmpty()) Text(t.evidence.joinToString(" "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }

        if (r.actions.isNotEmpty()) AiBlock(stringResource(R.string.ai_actions)) {
            r.actions.forEach { a ->
                Column {
                    Text(actionLabel(a.action) + " · " + a.target, style = MaterialTheme.typography.titleSmall)
                    Text(a.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.ai_actions_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }

        if (r.openQuestions.isNotEmpty()) AiBlock(stringResource(R.string.ai_questions)) {
            r.openQuestions.forEach { q -> Text("? $q", style = MaterialTheme.typography.bodyMedium) }
        }

        Text(
            stringResource(R.string.ai_footer, (r.confidence * 100).toInt(), o.model, if (o.fromCache) stringResource(R.string.ai_cached) else "%.1f s".format(o.durationMs / 1000.0)) +
                if (o.stripped > 0) " · " + stringResource(R.string.ai_stripped, o.stripped) else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun AiBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun purposeColor(p: Purpose): Color = when (p) {
    Purpose.ADS -> MaterialTheme.colorScheme.error
    Purpose.ANALYTICS, Purpose.CRASH -> MaterialTheme.colorScheme.tertiary
    Purpose.UNKNOWN -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.primary
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
internal fun purposeLabel(p: Purpose) = stringResource(
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

