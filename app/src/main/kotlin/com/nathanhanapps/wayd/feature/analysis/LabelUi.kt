package com.nathanhanapps.wayd.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.analysis.LabelRules
import com.nathanhanapps.wayd.core.model.AdviceReason
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.core.model.BlockAdvice
import com.nathanhanapps.wayd.core.model.ConnectionRole
import com.nathanhanapps.wayd.core.model.PermissionMeaning
import com.nathanhanapps.wayd.core.model.RelationStrength
import com.nathanhanapps.wayd.core.model.RoleSource
import com.nathanhanapps.wayd.core.model.Attention
import com.nathanhanapps.wayd.core.model.SuggestionKind
import com.nathanhanapps.wayd.analysis.HostAssessment
import com.nathanhanapps.wayd.analysis.Relation
import com.nathanhanapps.wayd.feature.ai.AiAssistCard

@Composable
fun roleText(role: ConnectionRole): String = stringResource(when (role) {
    ConnectionRole.FUNCTIONAL -> R.string.labels_role_functional
    ConnectionRole.LOGIN -> R.string.labels_role_login
    ConnectionRole.LOCATION -> R.string.labels_role_location
    ConnectionRole.PUSH -> R.string.labels_role_push
    ConnectionRole.ANALYTICS -> R.string.labels_role_analytics
    ConnectionRole.DIAGNOSTICS -> R.string.labels_role_diagnostics
    ConnectionRole.ADS -> R.string.labels_role_ads
    ConnectionRole.MEDIA -> R.string.labels_role_media
    ConnectionRole.CONTENT -> R.string.labels_role_content
    ConnectionRole.UPDATE -> R.string.labels_role_update
    ConnectionRole.CARRIER -> R.string.labels_role_carrier
    ConnectionRole.SYSTEM -> R.string.labels_role_system
    ConnectionRole.UNKNOWN -> R.string.labels_role_unknown
})

@Composable
fun permissionText(meaning: PermissionMeaning): String = stringResource(when (meaning) {
    PermissionMeaning.LOCATION -> R.string.labels_perm_location
    PermissionMeaning.CONTACTS -> R.string.labels_perm_contacts
    PermissionMeaning.ACCOUNTS -> R.string.labels_perm_accounts
    PermissionMeaning.PHONE -> R.string.labels_perm_phone
    PermissionMeaning.CALLS -> R.string.labels_perm_calls
    PermissionMeaning.CALL_LOG -> R.string.labels_perm_call_log
    PermissionMeaning.SMS -> R.string.labels_perm_sms
    PermissionMeaning.CALENDAR -> R.string.labels_perm_calendar
    PermissionMeaning.CAMERA -> R.string.labels_perm_camera
    PermissionMeaning.MICROPHONE -> R.string.labels_perm_microphone
    PermissionMeaning.MEDIA -> R.string.labels_perm_media
    PermissionMeaning.MEDIA_LOCATION -> R.string.labels_perm_media_location
    PermissionMeaning.SENSORS -> R.string.labels_perm_sensors
    PermissionMeaning.ACTIVITY_RECOGNITION -> R.string.labels_perm_activity
    PermissionMeaning.CLIPBOARD -> R.string.labels_perm_clipboard
    PermissionMeaning.BLUETOOTH -> R.string.labels_perm_bluetooth
    PermissionMeaning.WIFI -> R.string.labels_perm_wifi
    PermissionMeaning.NFC -> R.string.labels_perm_nfc
    PermissionMeaning.NOTIFICATIONS -> R.string.labels_perm_notifications
    PermissionMeaning.NOTIFICATION_ACCESS -> R.string.labels_perm_notification_access
    PermissionMeaning.PUSH -> R.string.labels_perm_push
    PermissionMeaning.ACCESSIBILITY -> R.string.labels_perm_accessibility
    PermissionMeaning.OVERLAY -> R.string.labels_perm_overlay
    PermissionMeaning.APP_LIST -> R.string.labels_perm_app_list
    PermissionMeaning.USAGE_STATS -> R.string.labels_perm_usage_stats
    PermissionMeaning.AD_ID -> R.string.labels_perm_ad_id
    PermissionMeaning.INSTALL_APPS -> R.string.labels_perm_install
    PermissionMeaning.REMOVE_APPS -> R.string.labels_perm_remove
    PermissionMeaning.REBOOT -> R.string.labels_perm_reboot
    PermissionMeaning.BOOT -> R.string.labels_perm_boot
    PermissionMeaning.NETWORK -> R.string.labels_perm_network
    PermissionMeaning.STORAGE -> R.string.labels_perm_storage
    PermissionMeaning.UNKNOWN -> R.string.labels_perm_unknown
})

@Composable
fun adviceText(advice: BlockAdvice): String = stringResource(when (advice) {
    BlockAdvice.TRY_TEMPORARY -> R.string.labels_advice_try
    BlockAdvice.MAY_AFFECT_FUNCTION -> R.string.labels_advice_impact
    BlockAdvice.INSUFFICIENT -> R.string.labels_advice_unknown
})

@Composable
private fun reasonText(reason: AdviceReason): String = stringResource(when (reason) {
    AdviceReason.TRACKING_ROLE -> R.string.labels_reason_tracking
    AdviceReason.FEATURE_ROLE -> R.string.labels_reason_feature
    AdviceReason.PLATFORM_ROLE -> R.string.labels_reason_platform
    AdviceReason.UNKNOWN_ROLE -> R.string.labels_reason_unknown_role
    AdviceReason.NO_HOST_EVIDENCE -> R.string.labels_reason_no_host
    AdviceReason.NO_BYTES -> R.string.labels_reason_no_bytes
    AdviceReason.UNATTRIBUTED -> R.string.labels_reason_unattributed
    AdviceReason.SCREEN_OFF -> R.string.labels_reason_screen_off
    AdviceReason.UPLOAD_HEAVY -> R.string.labels_reason_upload
    AdviceReason.ALREADY_BLOCKED -> R.string.labels_reason_blocked
    AdviceReason.NAME_ONLY -> R.string.labels_reason_name_only
})

/**
 * Descriptive markers only; all lookups are prebuilt in [LabelContext]. When the AI agrees with the local role, the local role
 * stays primary and the AI adds a check, so a rule-derived role is never presented as AI output.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ItemLabelMarkers(context: LabelContext?, ref: String) {
    if (context == null) return
    val answer = context.answer(ref)
    val fact = context.factsById[ref]
    val local = context.localRole(ref)
    var linked by remember(ref) { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (local != null) {
            val name = roleText(local.role)
            Badge(if (local.source == RoleSource.NAME_PATTERN) stringResource(R.string.labels_role_by_name, name) else name, MaterialTheme.colorScheme.secondary)
            answer?.role?.takeIf { it != ConnectionRole.UNKNOWN }?.let {
                Badge(if (it == local.role) stringResource(R.string.labels_ai_agrees) else stringResource(R.string.labels_ai, roleText(it)), MaterialTheme.colorScheme.outline)
            }
            // Rule-computed attention and the APK relations behind it: permission, component and connection labels read together.
            context.assessment(ref)?.let { a ->
                attentionLabel(a.attention)?.let { (text, color) -> Badge(text, color) }
                if (a.inApk) Badge(stringResource(R.string.labels_in_apk), MaterialTheme.colorScheme.outline)
                a.permissions.take(2).forEach { r -> Badge(relationText(r), if (r.strength <= RelationStrength.SAME_TIME) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline) }
                a.components.firstOrNull()?.fact?.let { Badge(stringResource(R.string.labels_rel_component, it.key.substringAfterLast('.').take(28)), MaterialTheme.colorScheme.outline) }
            }
        } else if (fact?.kind == ApkFactKind.PERMISSION) {
            Badge(permissionText(LabelRules.permission(fact.key)), MaterialTheme.colorScheme.secondary)
            answer?.explanation?.takeIf { it.isNotBlank() }?.let { Badge(stringResource(R.string.labels_ai, it), MaterialTheme.colorScheme.outline) }
        } else {
            answer?.explanation?.takeIf { it.isNotBlank() }?.let { Badge(stringResource(R.string.labels_ai, it), MaterialTheme.colorScheme.outline) }
        }
        answer?.permissionRefs?.forEach { id ->
            val p = context.factsById[id] ?: return@forEach
            SuggestionChip(onClick = { linked = id }, label = { Text(stringResource(R.string.labels_ai_possible_permission, permissionText(LabelRules.permission(p.key)))) })
        }
        answer?.featureRefs?.forEach { id ->
            val feature = answer.featureNames[id] ?: return@forEach
            SuggestionChip(onClick = { linked = id }, label = { Text(stringResource(R.string.labels_ai_possible_feature, feature)) })
        }
    }
    linked?.let { id ->
        val f = context.factsById[id]
        AlertDialog(onDismissRequest = { linked = null }, title = { Text(stringResource(R.string.labels_linked_evidence)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f?.key.orEmpty())
                Text(f?.value.orEmpty())
                Text(stringResource(R.string.labels_snapshot, f?.versionCode ?: 0L))
                Text(stringResource(R.string.labels_link_limit))
            }
        }, confirmButton = { TextButton(onClick = { linked = null }) { Text(stringResource(R.string.block_cancel)) } })
    }
}

/**
 * Expanded details. The AI card comes first (it is the user's question); the deterministic algorithm view follows. The existing block
 * button remains a separately confirmed action outside this panel.
 */
@Composable
fun ItemLabelDetails(context: LabelContext?, ref: String, vm: LabelViewModel) {
    if (context == null) return
    val answer = context.answer(ref)
    val busy by vm.busyRef.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val flow = context.flowsById[ref]
    val fact = context.factsById[ref]
    val summary = context.summary(ref)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AiAssistCard(stringResource(R.string.labels_ai_title)) {
            if (answer != null) {
                if (answer.explanation.isNotBlank()) Text(stringResource(R.string.labels_ai, answer.explanation), style = MaterialTheme.typography.bodyMedium)
                if (flow != null) Text(stringResource(R.string.labels_ai_advice, adviceText(answer.advice)), style = MaterialTheme.typography.bodyMedium)
                if (answer.reason.isNotBlank()) Text(stringResource(R.string.labels_ai, answer.reason), style = MaterialTheme.typography.bodySmall)
                if (flow != null && answer.target != ref) Text(stringResource(R.string.labels_ai_other_record), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.labels_citations, answer.evidence.joinToString(" · ")), style = MaterialTheme.typography.labelSmall)
                answer.createdAt?.let { at -> Text(stringResource(R.string.labels_saved, answer.model.orEmpty(), java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))), style = MaterialTheme.typography.labelSmall) }
            } else Text(stringResource(R.string.labels_ai_none), style = MaterialTheme.typography.bodySmall)
            if (busy == ref) {
                val phase by vm.phase.collectAsStateWithLifecycle()
                com.nathanhanapps.wayd.feature.ai.PhaseNotice(phase)
                TextButton(onClick = vm::cancel) { Text(stringResource(R.string.ai_cancel)) }
            } else FilledTonalButton(onClick = { vm.generate(ref) }, enabled = busy == null) {
                Text(stringResource(if (answer == null) R.string.labels_generate else R.string.labels_refresh))
            }
            budget?.let { Text(stringResource(R.string.labels_context_limit, it.maxItems), style = MaterialTheme.typography.labelSmall) }
            notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
        if (summary != null) {
            Text(stringResource(R.string.labels_algorithm, adviceText(summary.advice)), style = MaterialTheme.typography.bodyMedium)
            if (summary.flows > 1) Text(stringResource(R.string.labels_host_records, summary.flows), style = MaterialTheme.typography.labelSmall)
            summary.reasons.forEach { Text("• " + reasonText(it), style = MaterialTheme.typography.bodySmall) }
            val matches = remember(context, ref) { flow?.let { LabelRules.exactEndpoints(it, context.facts) }.orEmpty() }
            if (matches.isNotEmpty()) Text(stringResource(R.string.labels_apk_match, matches.size), style = MaterialTheme.typography.bodySmall)
        }
        context.assessment(ref)?.let { a -> Relations(a) }
        if (fact != null) Text(stringResource(R.string.labels_snapshot, fact.versionCode), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.labels_link_limit), style = MaterialTheme.typography.labelSmall)
    }
}

/** Attention badge text and colour; colour is never the only signal. NONE shows nothing. */
@Composable
fun attentionLabel(a: Attention): Pair<String, androidx.compose.ui.graphics.Color>? = when (a) {
    Attention.ACT -> stringResource(R.string.labels_attention_act) to MaterialTheme.colorScheme.error
    Attention.REVIEW -> stringResource(R.string.labels_attention_review) to MaterialTheme.colorScheme.tertiary
    Attention.NOTE -> stringResource(R.string.labels_attention_note) to MaterialTheme.colorScheme.outline
    Attention.NONE -> null
}

@Composable
fun relationText(r: Relation): String = stringResource(
    if (r.strength <= RelationStrength.SAME_TIME) R.string.labels_rel_used else R.string.labels_rel_declared,
    permissionText(r.meaning ?: PermissionMeaning.UNKNOWN),
)

/** External tools the suggestions point to. Opened only if installed; WAYD changes nothing in them. */
private val APPOPS_PACKAGES = listOf("rikka.appops", "rikka.appops.pro")
private val THANOX_PACKAGES = listOf("github.tornaco.android.thanos", "github.tornaco.android.thanos.pro")

private fun launcher(context: android.content.Context, packages: List<String>): (() -> Unit)? {
    val intent = packages.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) } ?: return null
    return { runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/**
 * The rule relations behind the attention level, then what the user could do: a block trial here, a permission to restrict in
 * AppOps, or a component to redirect (移花接木) or disable in Thanox. Suggestions are pointers; nothing is applied.
 */
@Composable
private fun Relations(a: HostAssessment) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (a.permissions.isNotEmpty() || a.components.isNotEmpty() || a.sdks.isNotEmpty()) {
            Text(stringResource(R.string.labels_rel_title), style = MaterialTheme.typography.labelLarge)
            a.permissions.forEach { r ->
                Text("• " + relationText(r) + (r.op?.let { " ($it)" } ?: "") + if (r.strength == RelationStrength.USED_TOGETHER) " · " + stringResource(R.string.labels_rel_fits_role) else "", style = MaterialTheme.typography.bodySmall)
            }
            a.components.forEach { r -> r.fact?.let { Text("• " + stringResource(R.string.labels_rel_component, it.key), style = MaterialTheme.typography.bodySmall) } }
            a.sdks.forEach { r -> r.fact?.let { Text("• " + stringResource(R.string.labels_rel_sdk, it.key), style = MaterialTheme.typography.bodySmall) } }
        }
        attentionLabel(a.attention)?.let { Text(stringResource(R.string.labels_attention_why, it.first), style = MaterialTheme.typography.labelSmall) }
        if (a.suggestions.isNotEmpty()) {
            Text(stringResource(R.string.labels_sugg_title), style = MaterialTheme.typography.labelLarge)
            a.suggestions.forEach { s ->
                Text("• " + when (s.kind) {
                    SuggestionKind.BLOCK_TRIAL -> stringResource(R.string.labels_sugg_block)
                    SuggestionKind.APPOPS_RESTRICT -> stringResource(R.string.labels_sugg_appops, permissionText(s.meaning ?: PermissionMeaning.UNKNOWN), s.op ?: s.permission?.substringAfterLast('.').orEmpty())
                    SuggestionKind.THANOX_REDIRECT -> stringResource(R.string.labels_sugg_thanox_redirect, s.component.orEmpty())
                    SuggestionKind.THANOX_DISABLE -> stringResource(R.string.labels_sugg_thanox_disable, s.component.orEmpty())
                }, style = MaterialTheme.typography.bodySmall)
            }
            val appOps = if (a.suggestions.any { it.kind == SuggestionKind.APPOPS_RESTRICT }) remember { launcher(context, APPOPS_PACKAGES) } else null
            val thanox = if (a.suggestions.any { it.kind == SuggestionKind.THANOX_REDIRECT || it.kind == SuggestionKind.THANOX_DISABLE }) remember { launcher(context, THANOX_PACKAGES) } else null
            if (appOps != null) TextButton(onClick = appOps) { Text(stringResource(R.string.labels_open_appops)) }
            if (thanox != null) TextButton(onClick = thanox) { Text(stringResource(R.string.labels_open_thanox)) }
        }
    }
}
