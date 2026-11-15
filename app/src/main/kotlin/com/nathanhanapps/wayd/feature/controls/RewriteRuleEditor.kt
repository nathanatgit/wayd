package com.nathanhanapps.wayd.feature.controls

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.capture.RewriteRule
import com.nathanhanapps.wayd.capture.RewriteRuleText
import com.nathanhanapps.wayd.capture.RewriteStore
import com.nathanhanapps.wayd.feature.common.HelpHint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class RewriteRuleEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val store: RewriteStore,
    private val blocking: BlockingRules,
) : ViewModel() {
    val setId: Long = savedState.get<Long>("id") ?: -1L
    val ruleId: Long? = savedState.get<Long>("rule")?.takeIf { it > 0 }
    /** The rule being edited, or null for a new one. */
    val existing: RewriteRule? = ruleId?.let { r -> store.sets.value.firstOrNull { it.id == setId }?.rules?.firstOrNull { it.id == r } }
    val saving = MutableStateFlow(false)
    val failed = MutableStateFlow(false)

    fun save(rule: RewriteRule, onDone: () -> Unit) {
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            val ok = runCatching {
                if (ruleId == null) store.addRule(setId, rule.host, rule.subdomains, rule.method, rule.match, rule.pattern, rule.action, rule.arg)
                else store.updateRule(setId, ruleId, rule)
            }.getOrDefault(false)
            runCatching { blocking.reloadRewrite() }
            saving.value = false
            failed.value = !ok
            if (ok) onDone()
        }
    }
}

/**
 * Add or edit one rewrite rule (D-073) on its own page: a form, and next to it the same rule as raw text. Portrait docks the
 * text below the form, landscape beside it. Editing either side updates the other: the text is regenerated from the form
 * unless the text field has focus, and text that reads as a valid rule fills the form.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun RewriteRuleEditorScreen(onBack: () -> Unit, vm: RewriteRuleEditorViewModel = hiltViewModel()) {
    val start = vm.existing
    var host by rememberSaveable { mutableStateOf(start?.host ?: "") }
    var subdomains by rememberSaveable { mutableStateOf(start?.subdomains ?: false) }
    var method by rememberSaveable { mutableStateOf(start?.method ?: "") }
    var match by rememberSaveable { mutableStateOf(start?.match ?: "PREFIX") }
    var pattern by rememberSaveable { mutableStateOf(start?.pattern ?: "/") }
    var action by rememberSaveable { mutableStateOf(start?.action ?: "REJECT") }
    var arg by rememberSaveable { mutableStateOf(start?.arg ?: "204") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var raw by rememberSaveable { mutableStateOf("") }
    var rawFocused by rememberSaveable { mutableStateOf(false) }
    val saving by vm.saving.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()

    val draft = RewriteRule(0, host.trim().lowercase(), subdomains, method.trim().uppercase(), match, pattern.trim(), action, arg.trim(), true, 0)
    val valid = draft.valid()
    fun generate() = if (tab == 0) RewriteRuleText.toText(draft) else RewriteRuleText.toPlugin(draft).orEmpty()
    // Form → text, unless the user is typing in the text.
    LaunchedEffect(draft, tab) { if (!rawFocused) raw = generate() }
    fun apply(r: RewriteRule) { host = r.host; subdomains = r.subdomains; method = r.method; match = r.match; pattern = r.pattern; action = r.action; arg = r.arg }
    // Text → form, when the text reads as a valid rule. The plugin line has no method, so the form keeps its own.
    val status: String = if (tab == 0) {
        val p = RewriteRuleText.fromText(raw)
        val bad = p.errorLine
        when {
            p.rule != null -> stringResource(R.string.rw_editor_ok)
            bad != null && bad > 0 -> stringResource(R.string.rw_editor_bad_line, bad)
            else -> stringResource(R.string.rw_editor_invalid)
        }
    } else {
        if (RewriteRuleText.fromPlugin(raw) == null) stringResource(R.string.rw_editor_invalid)
        else if (method.isNotBlank()) stringResource(R.string.rw_editor_plugin_method) else stringResource(R.string.rw_editor_ok)
    }
    fun onRaw(text: String) {
        raw = text
        if (tab == 0) RewriteRuleText.fromText(text).rule?.let(::apply)
        else RewriteRuleText.fromPlugin(text)?.let { apply(it.copy(method = method.trim().uppercase())) }
    }

    val form: @Composable ColumnScope.() -> Unit = {
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
                FilterChip(action == k, { if (action != k) { action = k; arg = if (k == "REJECT") "204" else "" } }, { Text(stringResource(label)) })
            }
        }
        if (action == "REJECT") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("204", "200-empty", "200-json", "200-array").forEach { k -> FilterChip(arg == k, { arg = k }, { Text(k) }) }
        } else OutlinedTextField(arg, { arg = it.take(1200) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(when (action) { "HEADER_SET" -> R.string.rw_arg_header_set; "REDIRECT" -> R.string.rw_arg_redirect; else -> R.string.rw_arg_name })) })
        if (failed) Text(stringResource(R.string.rw_limit, RewriteStore.MAX_ENGINE_LINES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    val editor: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(R.string.rw_editor_text, R.string.rw_editor_plugin).forEachIndexed { i, label ->
                    SegmentedButton(selected = tab == i, onClick = { tab = i; rawFocused = false; raw = if (i == 0) RewriteRuleText.toText(draft) else RewriteRuleText.toPlugin(draft).orEmpty() },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                }
            }
            OutlinedTextField(
                raw, ::onRaw, Modifier.fillMaxWidth().weight(1f).onFocusChanged { rawFocused = it.isFocused },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize),
            )
            Text(status, style = MaterialTheme.typography.labelMedium, color = if (status == stringResource(R.string.rw_editor_ok)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(if (start == null) R.string.rw_add else R.string.rw_edit), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            HelpHint(stringResource(R.string.rw_consent))
            TextButton(onClick = { vm.save(draft, onBack) }, enabled = valid && !saving) { Text(stringResource(R.string.cx_save)) }
        }
        val scroll = rememberScrollState()
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = form)
                VerticalDivider()
                editor(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.weight(1.2f).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = form)
            HorizontalDivider()
            editor(Modifier.weight(1f))
        }
    }
}
