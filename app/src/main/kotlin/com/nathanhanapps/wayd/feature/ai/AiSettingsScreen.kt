package com.nathanhanapps.wayd.feature.ai

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.ai.AiProvider
import com.nathanhanapps.wayd.ai.ExternalAiUrl
import com.nathanhanapps.wayd.ai.ModelCatalog
import com.nathanhanapps.wayd.ai.ModelDownloads
import com.nathanhanapps.wayd.ai.ModelSpec
import com.nathanhanapps.wayd.ai.MismatchKind
import com.nathanhanapps.wayd.ai.ModelState
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.NavRow
import com.nathanhanapps.wayd.feature.common.SubPage
import com.nathanhanapps.wayd.feature.common.SwitchRow

@Composable
fun externalErrorLabel(code: String): String = stringResource(
    when (code) {
        "ext_url" -> R.string.xai_err_url
        "ext_auth" -> R.string.xai_err_auth
        "ext_not_found" -> R.string.xai_err_not_found
        "ext_rate" -> R.string.xai_err_rate
        "ext_timeout" -> R.string.xai_err_timeout
        "ext_dns" -> R.string.xai_err_dns
        "ext_connect" -> R.string.xai_err_connect
        "ext_tls" -> R.string.xai_err_tls
        "ext_redirect" -> R.string.xai_err_redirect
        "ext_bad_reply" -> R.string.xai_err_bad_reply
        "ext_empty" -> R.string.xai_err_empty
        "ext_too_large" -> R.string.xai_err_too_large
        "ext_not_configured" -> R.string.xai_err_not_configured
        else -> if (code.startsWith("ext_http_")) return stringResource(R.string.xai_err_http, code.removePrefix("ext_http_")) else R.string.xai_err_network
    },
)

/** Settings → AI: which provider answers, plus the on-device Gemma models and the entry to the external API form. */
@Composable
fun AiSettingsScreen(onBack: () -> Unit, onOpenExternal: () -> Unit, vm: AiSettingsViewModel = hiltViewModel()) {
    val states by vm.states.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val ext by vm.ext.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val recommended = ModelCatalog.recommended(vm.ramGb)
    var customFor by remember { mutableStateOf<ModelSpec?>(null) }
    var manualFor by remember { mutableStateOf<ModelSpec?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf(vm.folderName()) }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { vm.setFolder(uri); folderName = uri.let { android.provider.DocumentsContract.getTreeDocumentId(it).substringAfter(':') } }
    }
    val sources = ModelCatalog.E2B.sources

    SubPage(stringResource(R.string.ai_page_title), onBack) {
        val providers = listOf(AiProvider.ON_DEVICE to R.string.ai_provider_device, AiProvider.EXTERNAL to R.string.ai_provider_external)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            providers.forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = ext.provider == p, onClick = { vm.setProvider(p) },
                    enabled = p == AiProvider.ON_DEVICE || ext.usable,
                    shape = SegmentedButtonDefaults.itemShape(i, providers.size),
                ) { Text(stringResource(label)) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_on_device_header, "%.0f".format(vm.ramGb)),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    TextButton(onClick = { sourceMenu = true }) { Text(sources[source.coerceAtMost(sources.lastIndex)].label + " ▾") }
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        sources.forEachIndexed { i, s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.setSource(i); sourceMenu = false }) }
                    }
                }
            }
            InsetGroup {
                ModelCatalog.all.forEachIndexed { i, spec ->
                    if (i > 0) GroupDivider()
                    ModelRow(
                        spec, states[spec.id] ?: ModelState.NotInstalled, inUse = active == spec.id, running = downloading == spec.id,
                        recommended = recommended?.id == spec.id, custom = vm.customUrl(spec) != null,
                        onDownload = { ModelDownloads.start(context, spec, vm.url(spec)) },
                        onCancel = { ModelDownloads.cancel(context) },
                        onDelete = { vm.delete(spec) }, onUse = { vm.use(spec) }, onCustom = { customFor = spec },
                        onRedownload = { vm.redownload(context, spec) }, onIgnore = { vm.ignoreMismatch(spec) }, onManual = { manualFor = spec },
                    )
                }
                GroupDivider()
                NavRow(
                    Icons.AutoMirrored.Filled.List, stringResource(R.string.ai_model_folder),
                    folderName ?: stringResource(R.string.ai_model_folder_none), onClick = { pickFolder.launch(vm.suggestedFolder()) },
                )
            }
        }

        val localDepth by vm.depths.local.collectAsStateWithLifecycle()
        AiDepthPanel(localDepth, external = false) { vm.depths.set(external = false, depth = it) }

        InsetGroup {
            NavRow(
                Icons.AutoMirrored.Filled.Send, stringResource(R.string.xai_title),
                if (ext.configured) ext.host + " · " + ext.model else stringResource(R.string.xai_not_set), onClick = onOpenExternal,
            )
        }
    }

    manualFor?.let { spec ->
        AlertDialog(
            onDismissRequest = { manualFor = null },
            title = { Text(stringResource(R.string.ai_manual_title)) },
            text = { Text(stringResource(R.string.ai_manual_text, folderName ?: stringResource(R.string.ai_model_folder_none), spec.fileName)) },
            confirmButton = { TextButton(onClick = { vm.recheck(); manualFor = null }) { Text(stringResource(R.string.ai_recheck)) } },
            dismissButton = { TextButton(onClick = { manualFor = null }) { Text(stringResource(R.string.ai_cancel)) } },
        )
    }

    customFor?.let { spec ->
        var url by remember(spec) { mutableStateOf(vm.customUrl(spec).orEmpty()) }
        AlertDialog(
            onDismissRequest = { customFor = null },
            title = { Text(stringResource(R.string.ai_custom_title)) },
            text = { OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.ai_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = url.trim().startsWith("https://"), onClick = { vm.setCustomUrl(spec, url.trim()); customFor = null }) { Text(stringResource(R.string.cx_save)) } },
            dismissButton = { TextButton(onClick = { vm.setCustomUrl(spec, null); customFor = null }) { Text(stringResource(R.string.ai_reset)) } },
        )
    }
}

/** One compact row per model: name and size, one status line, one action. */
@Composable
private fun ModelRow(
    spec: ModelSpec, state: ModelState, inUse: Boolean, running: Boolean, recommended: Boolean, custom: Boolean,
    onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, onUse: () -> Unit, onCustom: () -> Unit,
    onRedownload: () -> Unit, onIgnore: () -> Unit, onManual: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spec.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Formatter.formatShortFileSize(context, spec.sizeBytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (recommended) Text(
                    stringResource(R.string.ai_recommended), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            when (state) {
                is ModelState.Downloading -> {
                    LinearProgressIndicator(progress = { (state.doneBytes.toFloat() / state.totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        Formatter.formatShortFileSize(context, state.doneBytes) + " / " + Formatter.formatShortFileSize(context, state.totalBytes) + if (running) "" else " · " + stringResource(R.string.ai_paused),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModelState.Verifying -> Text(stringResource(R.string.ai_state_verifying), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ModelState.Unchecked -> Text(stringResource(R.string.ai_state_unchecked), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ModelState.Mismatch -> Text(
                    stringResource(if (state.kind == MismatchKind.UPDATE_AVAILABLE) R.string.ai_state_update else R.string.ai_state_corrupted),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                is ModelState.Failed -> Text(stringResource(R.string.ai_state_failed, state.reason), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                is ModelState.Ready -> Text(
                    stringResource(if (inUse) R.string.ai_state_active else R.string.ai_state_installed) + if (state.ignored) " · " + stringResource(R.string.ai_state_ignored) else "",
                    style = MaterialTheme.typography.labelMedium, color = if (inUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelState.NotInstalled -> if (custom) Text(stringResource(R.string.ai_custom_address), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (state) {
            ModelState.NotInstalled, is ModelState.Failed -> {
                FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.ai_download)) }
                Column {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ai_custom_title)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ai_custom_title)) }, onClick = { menu = false; onCustom() })
                    }
                }
            }
            is ModelState.Downloading -> if (running) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ai_cancel)) }
            } else {
                TextButton(onClick = onDownload) { Text(stringResource(R.string.ai_resume)) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.ai_delete)) }
            }
            ModelState.Verifying -> CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
            is ModelState.Mismatch -> {
                FilledTonalButton(onClick = onRedownload) { Text(stringResource(R.string.ai_redownload)) }
                Column {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ai_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.ai_manual_title)) }, onClick = { menu = false; onManual() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ai_ignore)) }, onClick = { menu = false; onIgnore() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ai_delete)) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            ModelState.Unchecked, is ModelState.Ready -> {
                if (!inUse) TextButton(onClick = onUse) { Text(stringResource(R.string.ai_use)) } else Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.ai_delete)) }
            }
        }
    }
}

/** Settings → AI → OpenAI-compatible API: server, model, key, and the acknowledgement that data leaves the phone. */
@Composable
fun ExternalAiScreen(onBack: () -> Unit, vm: ExternalAiViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(ui.baseUrl) }
    var model by rememberSaveable { mutableStateOf(ui.model) }
    var key by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val validUrl = ExternalAiUrl.normalise(url) != null
    val dirty = url.trim() != ui.baseUrl || model.trim() != ui.model || key.isNotBlank()
    // Load the model names once the address is valid and typing has paused; nothing to press.
    androidx.compose.runtime.LaunchedEffect(url) {
        if (ExternalAiUrl.normalise(url) == null) vm.autoLoad(url) else {
            kotlinx.coroutines.delay(700)
            vm.autoLoad(url)
        }
    }

    SubPage(stringResource(R.string.xai_title), onBack) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.xai_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }

        // Saved profiles: tap to switch server + model; each server keeps its own acknowledgement.
        val profiles by vm.profiles.collectAsStateWithLifecycle()
        var naming by remember { mutableStateOf(false) }
        // A switch replaces the stored server, so the form follows it.
        androidx.compose.runtime.LaunchedEffect(ui.baseUrl, ui.model) { url = ui.baseUrl; model = ui.model; key = "" }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.xai_profiles), style = MaterialTheme.typography.labelLarge)
            if (profiles.isEmpty()) Text(stringResource(R.string.xai_profiles_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(profiles, key = { it.id }) { p ->
                    androidx.compose.material3.InputChip(
                        selected = p.baseUrl == ui.baseUrl && p.model == ui.model, onClick = { vm.useProfile(p.id) }, label = { Text(p.name, maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.xai_profile_delete), Modifier.size(18.dp).clickable { vm.deleteProfile(p.id) })
                        },
                    )
                }
            }
            TextButton(onClick = { naming = true }, enabled = ui.configured && !dirty) { Text(stringResource(R.string.xai_profile_save)) }
        }
        if (naming) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { naming = false },
                title = { Text(stringResource(R.string.xai_profile_save)) },
                text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.xai_profile_name)) }, placeholder = { Text(ui.host + " · " + ui.model.take(24)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = { vm.saveProfile(name) { naming = false } }) { Text(stringResource(R.string.cx_save)) } },
                dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.ai_cancel)) } },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                url, { url = it; failed = false }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.xai_url)) },
                placeholder = { Text("http://192.168.1.5:11434/v1") }, isError = url.isNotBlank() && !validUrl,
                supportingText = { if (url.isNotBlank() && !validUrl) Text(stringResource(R.string.xai_url_invalid)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.xai_model)) })
            (test as? ExternalTest.Ok)?.models?.takeIf { it.isNotEmpty() }?.let { names ->
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(names.take(40), key = { it }) { name -> FilterChip(selected = model == name, onClick = { model = name }, label = { Text(name) }) }
                }
            }
            OutlinedTextField(
                key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.xai_key)) },
                placeholder = { if (ui.hasKey) Text(stringResource(R.string.xai_key_saved)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { vm.testConnection(url, model, key) }, enabled = validUrl && test != ExternalTest.Running) {
                if (test == ExternalTest.Running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.xai_test))
            }
            when (val t = test) {
                is ExternalTest.Ok -> Text(stringResource(R.string.xai_test_ok, t.models.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                is ExternalTest.Failed -> Text(externalErrorLabel(t.code), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
        }

        InsetGroup {
            SwitchRow(Icons.Filled.Warning, stringResource(R.string.xai_ack), ui.acknowledged, enabled = ui.configured && !dirty) { vm.acknowledge(it) }
        }

        // Same panel as on-device, separate values: an external server is often a larger model that benefits from more context.
        val externalDepth by vm.depths.external.collectAsStateWithLifecycle()
        AiDepthPanel(externalDepth, external = true) { vm.depths.set(external = true, depth = it) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.save(url, model, key) { ok -> failed = !ok; if (ok) key = "" } }, enabled = validUrl && model.isNotBlank() && dirty) { Text(stringResource(R.string.cx_save)) }
            if (ui.configured) TextButton(onClick = { vm.remove(); url = ""; model = ""; key = "" }) { Text(stringResource(R.string.xai_remove), color = MaterialTheme.colorScheme.error) }
            if (failed) Text(stringResource(R.string.xai_err_url), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Context size and effort for one provider. Shared by the on-device and external pages so both read the same way. */
@Composable
internal fun AiDepthPanel(depth: com.nathanhanapps.wayd.ai.AiDepth, external: Boolean, onChange: (com.nathanhanapps.wayd.ai.AiDepth) -> Unit) {
    // On-device Gemma is scaled on its own (D-063): two real sizes, the compact prompts written for it, and no effort levels.
    val sizes = listOfNotNull(
        com.nathanhanapps.wayd.ai.ContextSize.AUTO to R.string.ai_depth_auto, com.nathanhanapps.wayd.ai.ContextSize.SMALL to R.string.ai_depth_small,
        com.nathanhanapps.wayd.ai.ContextSize.MEDIUM to R.string.ai_depth_medium,
        if (external) com.nathanhanapps.wayd.ai.ContextSize.LARGE to R.string.ai_depth_large else null,
    )
    val efforts = listOf(
        com.nathanhanapps.wayd.ai.AiEffort.LOW to R.string.ai_effort_low, com.nathanhanapps.wayd.ai.AiEffort.STANDARD to R.string.ai_effort_standard,
        com.nathanhanapps.wayd.ai.AiEffort.HIGH to R.string.ai_effort_high,
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_depth_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ai_depth_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ai_depth_context), style = MaterialTheme.typography.labelLarge)
        val current = if (!external && depth.context == com.nathanhanapps.wayd.ai.ContextSize.LARGE) com.nathanhanapps.wayd.ai.ContextSize.MEDIUM else depth.context
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sizes.forEachIndexed { i, (size, label) ->
                SegmentedButton(selected = current == size, onClick = { onChange(depth.copy(context = size)) }, shape = SegmentedButtonDefaults.itemShape(i, sizes.size)) {
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }
        Text(stringResource(if (external) R.string.ai_depth_auto_note_external else R.string.ai_depth_auto_note_local), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!external) return@Column
        Text(stringResource(R.string.ai_depth_effort), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            efforts.forEachIndexed { i, (effort, label) ->
                SegmentedButton(selected = depth.effort == effort, onClick = { onChange(depth.copy(effort = effort)) }, shape = SegmentedButtonDefaults.itemShape(i, efforts.size)) {
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }
        Text(stringResource(R.string.ai_effort_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
