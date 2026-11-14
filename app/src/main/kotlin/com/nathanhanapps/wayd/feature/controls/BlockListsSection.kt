package com.nathanhanapps.wayd.feature.controls

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.BlockListInfo
import com.nathanhanapps.wayd.capture.BlockListStore
import com.nathanhanapps.wayd.capture.BlockingRules
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imported Adblock/hosts lists (D-064). Text comes only from a file the user picks or an HTTPS address the user types, and only
 * when they press Import/Update: no schedule, no bundled list. A list does nothing until it is enabled with its own consent.
 */
@HiltViewModel
class BlockListsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BlockListStore,
    val blocking: BlockingRules,
) : ViewModel() {
    val lists = store.lists
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow<Int?>(null)

    private fun run(block: suspend () -> Boolean) {
        if (busy.value) return
        busy.value = true
        notice.value = null
        viewModelScope.launch {
            val ok = runCatching { block() }.getOrDefault(false)
            runCatching { blocking.reloadLists() }
            notice.value = if (ok) null else R.string.blist_failed
            busy.value = false
        }
    }

    fun importFile(uri: Uri) = run {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "list"
        val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { read(it) } } ?: return@run false
        (store.import(name, text.lineSequence()) != null).also { if (it) notice.value = R.string.blist_imported }
    }

    fun importUrl(name: String, url: String, id: String? = null) = run {
        val text = withContext(Dispatchers.IO) { download(url) } ?: return@run false
        store.import(name.ifBlank { URL(url).host }, text.lineSequence(), url, id) != null
    }

    fun update(list: BlockListInfo) { list.url?.let { importUrl(list.name, it, list.id) } }

    fun setEnabled(id: String, on: Boolean) = run { store.setEnabled(id, on); true }

    fun delete(id: String) = run { store.delete(id); true }

    private fun read(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null // too large: refuse rather than apply half a list
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** HTTPS only, no redirects to another scheme, bounded size and time. WAYD's own traffic is outside its VPN. */
    private fun download(address: String): String? {
        val url = runCatching { URL(address.trim()) }.getOrNull()?.takeIf { it.protocol == "https" && it.host.isNotBlank() } ?: return null
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WAYD-blocklist")
        }
        return try {
            if (c.responseCode != 200 || c.url.protocol != "https") null else c.inputStream.use { read(it) }
        } finally {
            c.disconnect()
        }
    }

    private companion object { const val MAX_BYTES = 32 * 1024 * 1024 }
}

@Composable
fun BlockListsSection(vm: BlockListsViewModel = hiltViewModel()) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val entries by vm.blocking.listEntries.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var enabling by remember { mutableStateOf<BlockListInfo?>(null) }
    var deleting by remember { mutableStateOf<BlockListInfo?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFile) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.blist_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.blist_intro), style = MaterialTheme.typography.bodySmall)
            val e = entries
            Text(
                when {
                    !policy.running -> stringResource(R.string.blist_vpn_off)
                    e == null -> stringResource(R.string.blist_old_engine)
                    e < 0 -> stringResource(R.string.blist_rejected)
                    else -> stringResource(R.string.blist_loaded, e)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (policy.running && (e == null || e < 0)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pick.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = !busy) { Text(stringResource(R.string.blist_import_file)) }
                TextButton(onClick = { adding = true }, enabled = !busy) { Text(stringResource(R.string.blist_add_url)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            lists.forEach { l ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(l.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(stringResource(R.string.blist_row, l.applied, l.skipped), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.blist_updated, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(l.updatedAt))), style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = l.enabled, enabled = !busy, onCheckedChange = { on -> if (on) enabling = l else vm.setEnabled(l.id, false) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (l.url != null) TextButton(onClick = { vm.update(l) }, enabled = !busy) { Text(stringResource(R.string.blist_update)) }
                    TextButton(onClick = { deleting = l }, enabled = !busy) { Text(stringResource(R.string.block_remove)) }
                }
            }
        }
    }
    enabling?.let { l ->
        AlertDialog(
            onDismissRequest = { enabling = null },
            title = { Text(stringResource(R.string.blist_consent_title)) },
            text = { Text(stringResource(R.string.blist_consent_body, l.name, l.applied)) },
            confirmButton = { TextButton(onClick = { vm.setEnabled(l.id, true); enabling = null }) { Text(stringResource(R.string.blist_enable)) } },
            dismissButton = { TextButton(onClick = { enabling = null }) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
    deleting?.let { l ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(l.name) },
            text = { Text(stringResource(R.string.blist_delete_body)) },
            confirmButton = { TextButton(onClick = { vm.delete(l.id); deleting = null }) { Text(stringResource(R.string.block_remove)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.blist_add_url)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.blist_url_note), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.blist_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it.take(500) }, label = { Text(stringResource(R.string.blist_url)) }, placeholder = { Text("https://") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { vm.importUrl(name, url); adding = false }, enabled = url.trim().startsWith("https://")) { Text(stringResource(R.string.blist_import)) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.block_cancel)) } },
        )
    }
}
