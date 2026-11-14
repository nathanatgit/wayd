package com.nathanhanapps.wayd.feature.ai

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.ai.AiMedia
import com.nathanhanapps.wayd.ai.ChatAnalyzer
import com.nathanhanapps.wayd.ai.ChatOutcome
import com.nathanhanapps.wayd.ai.ChatRequest
import com.nathanhanapps.wayd.ai.ChatTurn
import com.nathanhanapps.wayd.ai.EvidenceItem
import com.nathanhanapps.wayd.ai.MediaKind
import com.nathanhanapps.wayd.analysis.FindingText
import com.nathanhanapps.wayd.feature.analysis.AnalysisResult
import com.nathanhanapps.wayd.feature.analysis.AnalysisService
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One attachment as the user sees it; a video expands into several frames. */
class ChatAttachment(val key: Long, val title: String, val kind: MediaKind, val media: List<AiMedia>)

/** One exchange. [outcome] is null while the model works. Lives in memory only. */
data class ChatMessage(val id: Long, val window: String, val question: String, val attachments: List<String>, val outcome: ChatOutcome?)

/**
 * The Insights evidence the chat may cite: the same report rows the tab shows (overview, top apps, companies, screen-off apps,
 * regions) plus rule findings. IDs are stable within one window so answers can be checked against them.
 */
object InsightsEvidence {
    fun build(result: AnalysisResult, window: String, label: (String) -> String): Pair<List<EvidenceItem>, List<String>> {
        val r = result.report
        val c = r.coverage
        val items = ArrayList<EvidenceItem>()
        items += EvidenceItem("r:0", "window=$window apps=${r.appCount} domains=${r.domainCount} companies=${r.companyCount} trackerContacts=${r.trackerContacts}; " +
            "coverage: records=${c.flows} withApp=${c.withPackagePct}% withHostname=${c.withHostnamePct}% of ${c.measurableTcp} measurable TCP; not complete coverage")
        val findings = (r.alerts + r.others).take(10)
        findings.forEach { f ->
            items += EvidenceItem("x:${f.id}", "rule=${f.ruleId} severity=${f.severity} app=${label(f.pkg)} (${f.pkg}) ${FindingText.parse(f.title).args.joinToString(" ")}; decided by rules")
        }
        r.appTraffic.take(8).forEachIndexed { i, a ->
            items += EvidenceItem("t:$i", "top app #${i + 1} ${label(a.pkg)} (${a.pkg}) records=${a.flows} up=${a.bytesUp}B down=${a.bytesDown}B screenOff=${a.screenOffUp + a.screenOffDown}B")
        }
        r.companies.take(6).forEachIndexed { i, k ->
            items += EvidenceItem("k:$i", "company ${k.company} list=${k.category} records=${k.flows} up=${k.bytesUp}B apps=${k.apps.take(3).joinToString { label(it.first) }}")
        }
        r.background.take(5).forEachIndexed { i, b ->
            items += EvidenceItem("b:$i", "screen-off ${label(b.pkg)} (${b.pkg}) up=${b.screenOffUp}B down=${b.screenOffDown}B records=${b.screenOffFlows}")
        }
        r.countries.take(4).forEachIndexed { i, g -> items += EvidenceItem("g:$i", "region ${g.code} records=${g.flows} bytes=${g.bytes}") }
        val counts = findings.groupingBy { it.severity }.eachCount().entries.joinToString { "${it.key}=${it.value}" }
        return items to listOfNotNull(counts.takeIf { it.isNotEmpty() }?.let { "finding counts by rule severity: $it" })
    }
}

@HiltViewModel
class InsightsChatViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val analysis: AnalysisService,
    private val analyzer: ChatAnalyzer,
    private val gate: ExternalAiGate,
    private val evidence: AiEvidenceService,
    private val labels: AppLabels,
) : ViewModel() {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val attachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val busy = MutableStateFlow(false)
    val recording = MutableStateFlow(false)
    val notice = MutableStateFlow<Int?>(null)
    val phase get() = analyzer.phase
    private val recorder = VoiceRecorder()
    private var nextKey = 1L
    private var job: Job? = null

    private suspend fun limits() = analyzer.limits().second

    private fun add(item: ChatAttachment?) {
        if (item == null || item.media.isEmpty()) { notice.value = R.string.chat_read_failed; return }
        attachments.update { it + item }
    }

    /** Respects the depth's image/audio limits up front, so the user sees what will be sent before pressing Send. */
    private suspend fun room(kind: MediaKind): Int {
        val l = limits()
        val used = attachments.value.filter { it.kind == kind }.sumOf { it.media.size }
        return ((if (kind == MediaKind.IMAGE) l.images else l.audioClips) - used).also { if (it <= 0) notice.value = R.string.chat_limit }
    }

    fun attachImages(uris: List<Uri>) = viewModelScope.launch {
        notice.value = null
        val free = room(MediaKind.IMAGE)
        uris.take(free.coerceAtLeast(0)).forEach { uri ->
            val name = displayName(uri)
            add(withContext(Dispatchers.IO) { ChatMedia.image(app, uri, "u:1", name) }?.let { ChatAttachment(nextKey++, name, MediaKind.IMAGE, listOf(it)) })
        }
        if (uris.size > free.coerceAtLeast(0)) notice.value = R.string.chat_limit
    }

    fun attachVideo(uri: Uri) = viewModelScope.launch {
        notice.value = null
        val free = room(MediaKind.IMAGE).coerceAtMost(3)
        if (free <= 0) return@launch
        val name = displayName(uri)
        add(ChatAttachment(nextKey++, name, MediaKind.IMAGE, withContext(Dispatchers.IO) { ChatMedia.videoFrames(app, uri, 1, free, name) }))
    }

    fun attachAudio(uri: Uri) = viewModelScope.launch {
        notice.value = null
        if (room(MediaKind.AUDIO) <= 0) return@launch
        val name = displayName(uri)
        add(withContext(Dispatchers.Default) { ChatMedia.audio(app, uri, "u:1", name) }?.let { ChatAttachment(nextKey++, name, MediaKind.AUDIO, listOf(it)) })
    }

    fun startVoice() = viewModelScope.launch {
        notice.value = null
        if (room(MediaKind.AUDIO) <= 0) return@launch
        recording.value = recorder.start { viewModelScope.launch { stopVoice() } }
        if (!recording.value) notice.value = R.string.chat_read_failed
    }

    fun stopVoice() {
        if (!recorder.isRecording && !recording.value) return
        val samples = recorder.stop()
        recording.value = false
        val seconds = samples.size / ChatMedia.RATE
        val title = app.getString(R.string.chat_voice_label, seconds)
        add(ChatMedia.voice(samples, "u:1", title)?.let { ChatAttachment(nextKey++, title, MediaKind.AUDIO, listOf(it)) })
    }

    fun remove(key: Long) = attachments.update { list -> list.filterNot { it.key == key } }

    fun send(window: String, text: String) {
        if (busy.value) return
        if (recording.value) stopVoice()
        val picked = attachments.value
        if (text.isBlank() && picked.none { it.kind == MediaKind.AUDIO }) return
        // Renumber so ids are u:1..n in the order shown; the same numbers appear in the prompt and the preview.
        var n = 0
        val media = picked.flatMap { a -> a.media.map { m -> AiMedia("u:${++n}", m.kind, m.mime, m.bytes, m.label) } }
        val id = System.nanoTime()
        val history = messages.value.filter { it.window == window }.mapNotNull { m ->
            (m.outcome as? ChatOutcome.Answered)?.let { ChatTurn(m.question, it.answer.answer) }
        }
        messages.update { it + ChatMessage(id, window, text.trim(), picked.map { it.title }, null) }
        attachments.value = emptyList()
        notice.value = null
        busy.value = true
        job = viewModelScope.launch {
            val outcome = try {
                val result = analysis.analyse(window)
                val (items, notes) = withContext(Dispatchers.Default) { InsightsEvidence.build(result, window) { labels.label(it) } }
                analyzer.ask(ChatRequest("Insights for window $window", evidence.uiLocale(), items, notes, text.trim(), history, media), gate::ask)
            } catch (e: kotlinx.coroutines.CancellationException) {
                messages.update { list -> list.filterNot { it.id == id } }
                throw e
            } catch (e: Exception) {
                ChatOutcome.Failed(e.message?.take(80) ?: e.javaClass.simpleName)
            } finally {
                busy.value = false
            }
            messages.update { list -> list.map { if (it.id == id) it.copy(outcome = outcome) else it } }
        }
    }

    fun cancel() { job?.cancel(); busy.value = false }

    fun clear() { cancel(); messages.value = emptyList(); attachments.value = emptyList(); notice.value = null }

    override fun onCleared() { recorder.stop() }

    private fun displayName(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.take(60) ?: app.getString(R.string.chat_attachment)
}
