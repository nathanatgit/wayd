package com.nathanhanapps.wayd.feature.inspector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.nathanhanapps.wayd.ai.AiAnalyzer
import com.nathanhanapps.wayd.ai.AiOutcome
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.model.Dir
import com.nathanhanapps.wayd.feature.ai.AiEvidenceService
import com.nathanhanapps.wayd.feature.ai.AiOutcomeView
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.Windows
import com.nathanhanapps.wayd.feature.analysis.fmtBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class InspectorViewModel @Inject constructor(
    private val messageDao: MessageDao,
    private val analyzer: AiAnalyzer,
    private val evidence: AiEvidenceService,
    val labels: AppLabels,
    savedState: SavedStateHandle,
) : ViewModel() {
    val pkg: String = savedState["pkg"] ?: ""
    val windowKey: String = savedState["win"] ?: Windows.TODAY
    private val _messages = MutableStateFlow<List<Message>?>(null)
    val messages: StateFlow<List<Message>?> = _messages.asStateFlow()
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()
    private val _ai = MutableStateFlow<AiState>(AiState.Idle)
    val ai: StateFlow<AiState> = _ai.asStateFlow()

    sealed interface AiState {
        data object Idle : AiState
        data object Running : AiState
        data class Done(val outcome: AiOutcome) : AiState
    }

    init {
        viewModelScope.launch {
            val (start, end) = Windows.resolve(windowKey)
            _messages.value = withContext(Dispatchers.IO) { messageDao.forApp(pkg, start, end).takeLast(MAX_LIST) }
        }
    }

    fun toggle(id: Long) {
        _selected.value = if (id in _selected.value) _selected.value - id else _selected.value + id
    }

    fun analyseSelected() {
        val ids = _selected.value
        if (ids.isEmpty() || _ai.value == AiState.Running) return
        _ai.value = AiState.Running
        viewModelScope.launch {
            val locale = if (Locale.getDefault().language == "zh") "zh-CN" else "en"
            val req = evidence.requestForMessages(pkg, labels.label(pkg), windowKey, locale, ids)
            _ai.value = if (req == null) AiState.Done(AiOutcome.Failed("nothing selected")) else AiState.Done(analyzer.analyse(req))
        }
    }

    private companion object {
        const val MAX_LIST = 300
    }
}

/** Decrypted messages of one app: browse request/response content, pick some and ask the AI about them. */
@Composable
fun InspectorScreen(onBack: () -> Unit, vm: InspectorViewModel = hiltViewModel()) {
    val list by vm.messages.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.inspector_title), style = MaterialTheme.typography.titleLarge)
                Text(vm.labels.label(vm.pkg) + " · " + vm.windowKey, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(stringResource(R.string.inspector_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        val l = list
        when {
            l == null -> Text("…")
            l.isEmpty() -> Text(stringResource(R.string.inspector_empty))
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::analyseSelected, enabled = selected.isNotEmpty() && ai != InspectorViewModel.AiState.Running) {
                        Text(stringResource(R.string.inspector_analyse, selected.size))
                    }
                }
                when (val a = ai) {
                    InspectorViewModel.AiState.Idle -> {}
                    InspectorViewModel.AiState.Running -> {
                        Text(stringResource(R.string.ai_running), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is InspectorViewModel.AiState.Done -> AiOutcomeView(a.outcome)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    items(l.asReversed(), key = { it.id }) { m -> MessageRow(m, m.id in selected) { vm.toggle(m.id) } }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(m: Message, selected: Boolean, onToggle: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (m.dir == Dir.UP) "→ " else "← ") + (m.method?.let { "$it " } ?: "") + (m.status?.let { "$it " } ?: "") + m.url.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (open) 6 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(m.ts)) + " · " + (m.contentType ?: "-") + " · " + fmtBytes(ctx, m.bodySize.toLong()) +
                            if (m.bodyTruncated) " · " + stringResource(R.string.inspector_truncated) else "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (open) {
                m.headersJson?.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.inspector_headers), style = MaterialTheme.typography.labelMedium)
                    Text(it.take(4000), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                val text = m.body?.let { utf8OrNull(it) }
                Text(stringResource(R.string.inspector_body), style = MaterialTheme.typography.labelMedium)
                Text(
                    when {
                        m.body == null || m.body!!.isEmpty() -> stringResource(R.string.inspector_no_body)
                        text == null -> stringResource(R.string.inspector_binary, m.body!!.size)
                        else -> text.take(6000)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun utf8OrNull(b: ByteArray): String? = runCatching {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(b.copyOf(minOf(b.size, 24_000)))).toString()
}.getOrNull()
