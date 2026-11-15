package com.nathanhanapps.wayd.feature.controls

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.BlockingRules
import com.nathanhanapps.wayd.capture.DnsRuleSource
import com.nathanhanapps.wayd.capture.DnsRuleStore
import com.nathanhanapps.wayd.feature.common.ActionRow
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.ItemRow
import com.nathanhanapps.wayd.feature.common.SwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DNS rules (D-072): rule files (hosts / dnsmasq, as Daedalus reads them) picked by the user, typed rules and record-type
 * blocks. Files are read only from the picker: there is no download, so no network destination. Nothing applies until enabled.
 */
@HiltViewModel
class DnsRulesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DnsRuleStore,
    val blocking: BlockingRules,
) : ViewModel() {
    val state = store.state
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow<Int?>(null)

    private fun run(failure: Int, block: suspend () -> Boolean) {
        if (busy.value) return
        busy.value = true
        notice.value = null
        viewModelScope.launch {
            val ok = runCatching { block() }.getOrDefault(false)
            runCatching { blocking.reloadDnsRules() }
            if (!ok) notice.value = failure
            busy.value = false
        }
    }

    fun importFile(uri: Uri) = run(R.string.dnsr_failed) {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "rules"
        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { read(it) } } ?: return@run false
        (store.import(name, text.lineSequence()) != null).also { if (it) notice.value = R.string.dnsr_imported }
    }

    fun setSourceEnabled(id: String, on: Boolean) = run(R.string.dnsr_failed) { store.setSourceEnabled(id, on); true }
    fun deleteSource(id: String) = run(R.string.dnsr_failed) { store.deleteSource(id); true }
    fun addRule(host: String, subdomains: Boolean, value: String) = run(R.string.dnsr_invalid) { store.addRule(host, subdomains, value) != null }
    fun setRuleEnabled(id: Long, on: Boolean) = run(R.string.dnsr_failed) { store.setRuleEnabled(id, on); true }
    fun deleteRule(id: Long) = run(R.string.dnsr_failed) { store.deleteRule(id); true }
    fun setTypes(types: Set<Int>, on: Boolean) = run(R.string.dnsr_failed) { store.setTypeBlocked(types, on); true }

    private fun read(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null // too large: refuse rather than apply half a file
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object { const val MAX_BYTES = 32 * 1024 * 1024 }
}

private val HTTPS_TYPES = setOf(64, 65)
private val AAAA_TYPES = setOf(28)

@Composable
fun DnsRulesSection(vm: DnsRulesViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val entries by vm.blocking.dnsEntries.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var enabling by remember { mutableStateOf<DnsRuleSource?>(null) }
    var deleting by remember { mutableStateOf<DnsRuleSource?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFile) }
    val e = entries

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            InsetGroup(
                stringResource(R.string.dnsr_title), help = stringResource(R.string.dnsr_intro),
                trailing = { if (policy.running && e != null && e >= 0) Text(stringResource(R.string.dnsr_loaded, e), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp)) },
            ) {
                if (policy.running && (e == null || e < 0)) {
                    Text(stringResource(if (e == null) R.string.dnsr_old_engine else R.string.dnsr_rejected), Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    GroupDivider()
                }
                state.rules.forEach { r ->
                    var menu by remember(r.id) { mutableStateOf(false) }
                    ItemRow(
                        (if (r.subdomains) "*." else "") + r.host,
                        subtitle = r.value.ifEmpty { stringResource(R.string.dnsr_block) },
                        onClick = { menu = true },
                        trailing = {
                            Switch(checked = r.enabled, enabled = !busy, onCheckedChange = { vm.setRuleEnabled(r.id, it) })
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ai_more)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.block_remove)) }, enabled = !busy, onClick = { menu = false; vm.deleteRule(r.id) })
                                }
                            }
                        },
                    )
                    GroupDivider()
                }
                state.sources.forEach { s ->
                    var menu by remember(s.id) { mutableStateOf(false) }
                    ItemRow(
                        s.name,
                        subtitle = stringResource(R.string.dnsr_row, s.applied, s.skipped),
                        caption = stringResource(R.string.blist_updated, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.updatedAt))),
                        onClick = { menu = true },
                        trailing = {
                            Switch(checked = s.enabled, enabled = !busy, onCheckedChange = { on -> if (on) enabling = s else vm.setSourceEnabled(s.id, false) })
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ai_more)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.block_remove)) }, enabled = !busy, onClick = { menu = false; deleting = s })
                                }
                            }
                        },
                    )
                    GroupDivider()
                }
                ActionRow(Icons.Filled.Add, stringResource(R.string.dnsr_add), enabled = !busy) { adding = true }
                GroupDivider()
                ActionRow(Icons.Filled.Add, stringResource(R.string.dnsr_import), enabled = !busy) { pick.launch(arrayOf("text/*", "application/octet-stream")) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            notice?.let {
                val error = it != R.string.dnsr_imported
                Text(stringResource(it), Modifier.padding(start = 12.dp, top = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        InsetGroup(stringResource(R.string.dnsr_types)) {
            SwitchRow(Icons.Filled.Lock, stringResource(R.string.dnsr_type_https), state.blockedTypes.containsAll(HTTPS_TYPES), enabled = !busy, help = stringResource(R.string.dnsr_type_https_help)) { vm.setTypes(HTTPS_TYPES, it) }
            GroupDivider()
            SwitchRow(Icons.Filled.Lock, stringResource(R.string.dnsr_type_aaaa), state.blockedTypes.containsAll(AAAA_TYPES), enabled = !busy, help = stringResource(R.string.dnsr_type_aaaa_help)) { vm.setTypes(AAAA_TYPES, it) }
        }
    }

    enabling?.let { s ->
        AlertDialog(
            onDismissRequest = { enabling = null },
            title = { Text(stringResource(R.string.dnsr_consent_title)) },
            text = { Text(stringResource(R.string.dnsr_consent_body, s.name, s.applied)) },
            confirmButton = { TextButton(onClick = { vm.setSourceEnabled(s.id, true); enabling = null }) { Text(stringResource(R.string.blist_enable)) } },
            dismissButton = { TextButton(onClick = { enabling = null }) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(s.name) },
            text = { Text(stringResource(R.string.dnsr_delete_body)) },
            confirmButton = { TextButton(onClick = { vm.deleteSource(s.id); deleting = null }) { Text(stringResource(R.string.block_remove)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
    if (adding) {
        var host by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        var subdomains by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.dnsr_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.take(253) }, label = { Text(stringResource(R.string.dnsr_host)) }, placeholder = { Text("example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value, { value = it.take(45) }, label = { Text(stringResource(R.string.dnsr_value)) }, placeholder = { Text("192.168.1.10") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(subdomains, { subdomains = it })
                        Text(stringResource(R.string.dnsr_subdomains))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.addRule(host, subdomains, value); adding = false }, enabled = host.contains('.')) { Text(stringResource(R.string.dnsr_add)) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
}
