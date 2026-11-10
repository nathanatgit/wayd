package com.nathanhanapps.wayd.feature.traffic

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.pcap.HexDump
import com.nathanhanapps.wayd.core.db.DnsDao
import com.nathanhanapps.wayd.core.db.DnsRecord
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.model.Dir
import com.nathanhanapps.wayd.core.model.NetType
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.categoryLabel
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InfoRow
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.export.ExportButton
import com.nathanhanapps.wayd.feature.export.ExportScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Date
import javax.inject.Inject

class ConnectionDetail(val flow: Flow, val messages: List<Message>, val dns: List<DnsRecord>)

@HiltViewModel
class ConnectionDetailViewModel @Inject constructor(
    private val flows: FlowDao,
    private val messageDao: MessageDao,
    private val dnsDao: DnsDao,
    val labels: AppLabels,
    saved: SavedStateHandle,
) : ViewModel() {
    val id: Long = saved["id"] ?: 0L
    private val _state = MutableStateFlow<ConnectionDetail?>(null)
    val state: StateFlow<ConnectionDetail?> = _state.asStateFlow()
    private val _missing = MutableStateFlow(false)
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    init {
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) {
                val f = flows.byId(id) ?: return@withContext null
                val host = f.host
                // DNS lookups of this name around the connection: how the app learned the address.
                val dns = if (host == null) emptyList() else dnsDao.forUids(listOf(f.uid), f.startAt - 120_000, (f.endAt ?: f.startAt) + 5_000).filter { it.qname.equals(host, true) }.takeLast(4)
                ConnectionDetail(f, messageDao.forFlow(id), dns)
            }
            if (d == null) _missing.value = true else _state.value = d
        }
    }
}

/** One recorded connection in detail: who, where, how much, TLS facts, rule outcome, DNS and (research build) decrypted content. */
@Composable
fun ConnectionDetailScreen(onBack: () -> Unit, onOpenApp: (pkg: String, window: String) -> Unit, vm: ConnectionDetailViewModel = hiltViewModel()) {
    val detail by vm.state.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.cd_title), style = MaterialTheme.typography.titleLarge)
        }
        val d = detail
        when {
            missing -> Text(stringResource(R.string.cd_not_found), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            d == null -> Unit
            else -> {
                val f = d.flow
                val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM) }
                val tcp = f.proto == Proto.TCP
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(f.host ?: hostPort(f.dstIp, f.dstPort), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenApp(f.pkg, java.time.Instant.ofEpochMilli(f.startAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AppIcon(vm.labels, f.pkg, size = 36)
                                Column(Modifier.weight(1f)) {
                                    Text(vm.labels.label(f.pkg), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(f.pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    item {
                        InsetGroup {
                            InfoRow(Icons.Filled.Info, stringResource(R.string.cd_destination), hostPort(f.dstIp, f.dstPort))
                            GroupDivider()
                            InfoRow(Icons.Filled.Info, stringResource(R.string.cd_protocol), f.proto.name + (f.alpn?.let { " · $it" } ?: ""))
                            GroupDivider()
                            InfoRow(Icons.Filled.Info, stringResource(R.string.stat_up), Formatter.formatFileSize(context, f.bytesUp))
                            GroupDivider()
                            InfoRow(Icons.Filled.Info, stringResource(R.string.stat_down), Formatter.formatFileSize(context, f.bytesDown))
                            GroupDivider()
                            InfoRow(Icons.Filled.Info, stringResource(R.string.cd_started), fmt.format(Date(f.startAt)))
                            GroupDivider()
                            InfoRow(Icons.Filled.Info, stringResource(R.string.cd_duration), f.endAt?.let { duration(it - f.startAt) } ?: stringResource(R.string.cd_open))
                            GroupDivider()
                            InfoRow(Icons.Filled.Info, stringResource(R.string.cd_network), stringResource(when (f.netType) { NetType.WIFI -> R.string.cd_wifi; NetType.CELLULAR -> R.string.cd_cellular; NetType.OTHER -> R.string.cd_other }))
                            GroupDivider()
                            InfoRow(Icons.Filled.Info, stringResource(R.string.cd_screen), stringResource(if (f.screenOn) R.string.cd_on else R.string.cd_off))
                            f.country?.let { GroupDivider(); InfoRow(Icons.Filled.Info, stringResource(R.string.cd_country), it) }
                            f.company?.let { GroupDivider(); InfoRow(Icons.Filled.Info, stringResource(R.string.cd_company), it + (categoryLabel(f.category)?.let { c -> " · $c" } ?: "")) }
                        }
                    }
                    if (f.tlsVersion != null || f.hostSource != null || f.ja4 != null) item {
                        InsetGroup {
                            f.hostSource?.let { InfoRow(Icons.Filled.Lock, stringResource(R.string.cd_host_from), it.name) }
                            f.tlsVersion?.let { if (f.hostSource != null) GroupDivider(); InfoRow(Icons.Filled.Lock, stringResource(R.string.cd_tls), it) }
                            f.ja4?.let { GroupDivider(); InfoRow(Icons.Filled.Lock, "JA4", it) }
                        }
                    }
                    f.policyOutcome?.let { outcome ->
                        item { InsetGroup { InfoRow(Icons.Filled.Warning, stringResource(R.string.cd_policy), stringResource(if (outcome == "BLOCKED") R.string.connections_blocked else R.string.cd_allowed)) } }
                    }
                    if (d.dns.isNotEmpty()) item {
                        InsetGroup {
                            d.dns.forEachIndexed { i, r ->
                                if (i > 0) GroupDivider()
                                InfoRow(Icons.Filled.Info, "DNS " + DateFormat.format("HH:mm:ss", Date(r.ts)), r.answersJson.replace("\"", "").removeSurrounding("[", "]").replace(",", ", ").take(120))
                            }
                        }
                    }
                    item { Text(stringResource(R.string.cd_content), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp)) }
                    if (d.messages.isEmpty()) item {
                        Text(
                            stringResource(if (tcp) R.string.cd_not_decrypted else R.string.cd_no_content), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    items(d.messages, key = { it.id }) { MessageItem(it) }
                    item { ExportButton(ExportScope.Connection(f.id), stringResource(R.string.connections_export), Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

private fun hostPort(ip: String, port: Int) = (if (':' in ip) "[$ip]" else ip) + ":" + port

private fun duration(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> "%.1f s".format(ms / 1000.0)
    else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
}

private fun utf8OrNull(b: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
}.getOrNull()

/** One decrypted request or response: a summary line that expands to headers and the body as text or hex. */
@Composable
private fun MessageItem(m: Message) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val body = m.body ?: ByteArray(0)
    val text = remember(m.id) { utf8OrNull(body.copyOf(minOf(body.size, MAX_VIEW))) }
    var hex by remember(m.id) { mutableStateOf(text == null && body.isNotEmpty()) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (m.dir == Dir.UP) "↑" else "↓", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    if (m.dir == Dir.UP) (m.method.orEmpty() + " " + (m.url.orEmpty().substringAfter("://").substringAfter('/', "").let { "/$it" })).trim() else "HTTP ${m.status ?: ""}".trim(),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(m.contentType, Formatter.formatShortFileSize(context, m.bodySize.toLong())).joinToString(" · ") + if (m.bodyTruncated) " · " + stringResource(R.string.inspector_truncated) else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
        if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            m.url?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            m.headersJson?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.inspector_headers), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Mono(it.take(4000))
            }
            Text(stringResource(R.string.inspector_body), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (body.isEmpty()) Text(stringResource(R.string.inspector_no_body), style = MaterialTheme.typography.bodySmall) else {
                if (text != null) SingleChoiceSegmentedButtonRow {
                    listOf(false to R.string.cd_text, true to R.string.cd_hex).forEachIndexed { i, (h, label) ->
                        SegmentedButton(selected = hex == h, onClick = { hex = h }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                    }
                }
                Mono(if (hex || text == null) HexDump.format(body, MAX_VIEW) else text)
                if (body.size > MAX_VIEW) Text(stringResource(R.string.cd_shown, Formatter.formatShortFileSize(context, MAX_VIEW.toLong())), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Monospace block that scrolls sideways instead of wrapping, so columns and offsets stay readable. */
@Composable
private fun Mono(s: String) {
    Text(
        s, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).horizontalScroll(rememberScrollState()).padding(10.dp),
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, softWrap = false,
    )
}

private const val MAX_VIEW = 4096
