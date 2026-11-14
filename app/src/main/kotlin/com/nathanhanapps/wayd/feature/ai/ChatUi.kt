package com.nathanhanapps.wayd.feature.ai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.ai.ChatOutcome
import com.nathanhanapps.wayd.ai.MediaKind

/**
 * Insights chat, in the same AI card as app analysis and labels. Answers cite the window's report rows; attachments are the user's own
 * media, kept in memory for this chat and sent only to the selected provider (external sends always show the preview first).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun InsightsChatCard(window: String, vm: InsightsChatViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { if (it.isNotEmpty()) vm.attachImages(it) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::attachVideo) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::attachAudio) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micDenied = !granted; if (granted) vm.startVoice() }

    fun send(q: String) { vm.send(window, q); text = "" }

    AiAssistCard(stringResource(R.string.chat_title)) {
        val shown = messages.filter { it.window == window }
        if (shown.isEmpty()) Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodySmall)
        shown.forEach { m -> ChatExchange(m, busy && m.outcome == null, vm) { q -> send(q) } }

        if (attachments.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            attachments.forEach { a ->
                InputChip(selected = false, onClick = { vm.remove(a.key) }, label = { Text(a.title, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_remove), Modifier.size(16.dp)) })
            }
        }
        if (attachments.any { it.kind == MediaKind.IMAGE && it.media.size > 1 }) Text(stringResource(R.string.chat_video_note), style = MaterialTheme.typography.labelSmall)
        if (recording) Text(stringResource(R.string.chat_recording), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_attach)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_photo)) }, onClick = {
                        menu = false; pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_video)) }, onClick = {
                        menu = false; pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach_audio)) }, onClick = { menu = false; pickAudio.launch(arrayOf("audio/*")) })
                }
            }
            IconButton(onClick = {
                when {
                    recording -> vm.stopVoice()
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.startVoice()
                    else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, enabled = !busy) {
                Icon(MicIcon, contentDescription = stringResource(if (recording) R.string.chat_voice_stop else R.string.chat_voice_start),
                    tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            }
            OutlinedTextField(text, { text = it.take(500) }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.chat_hint)) }, maxLines = 4, enabled = !busy)
            IconButton(onClick = { send(text) }, enabled = !busy && (text.isNotBlank() || attachments.any { it.kind == MediaKind.AUDIO } || recording)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
            }
        }
        if (busy) {
            val phase by vm.phase.collectAsStateWithLifecycle()
            PhaseNotice(phase)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancel) { Text(stringResource(R.string.ai_cancel)) }
        }
        notice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (micDenied) Text(stringResource(R.string.chat_mic_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.chat_media_note), style = MaterialTheme.typography.labelSmall)
        if (messages.isNotEmpty() && !busy) TextButton(onClick = vm::clear) { Text(stringResource(R.string.chat_clear)) }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ChatExchange(m: ChatMessage, working: Boolean, vm: InsightsChatViewModel, ask: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(scheme.surface).padding(10.dp)) {
            Text(stringResource(R.string.chat_you), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(m.question.ifBlank { stringResource(R.string.chat_audio_only) }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            if (m.attachments.isNotEmpty()) Text(m.attachments.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        when (val o = m.outcome) {
            null -> if (working) Text(stringResource(R.string.ai_running), style = MaterialTheme.typography.bodySmall)
            is ChatOutcome.Answered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.labels_ai, o.answer.answer), style = MaterialTheme.typography.bodyMedium)
                o.answer.mediaNotes.forEach { Text(stringResource(R.string.chat_media_notes, it), style = MaterialTheme.typography.bodySmall) }
                if (o.answer.evidence.isNotEmpty()) Text(stringResource(R.string.labels_citations, o.answer.evidence.joinToString(" · ")), style = MaterialTheme.typography.labelSmall)
                Text("${o.answer.model} · ${o.answer.durationMs / 1000} s", style = MaterialTheme.typography.labelSmall, color = scheme.onSecondaryContainer.copy(alpha = 0.7f))
                if (o.answer.followUps.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.answer.followUps.forEach { q -> SuggestionChip(onClick = { ask(q) }, label = { Text(q, maxLines = 2) }) }
                }
            }
            ChatOutcome.NoModel -> Text(stringResource(R.string.ai_no_model), style = MaterialTheme.typography.bodySmall)
            ChatOutcome.Declined -> Text(stringResource(R.string.xg_declined), style = MaterialTheme.typography.bodySmall)
            ChatOutcome.Unreliable -> Text(stringResource(R.string.ai_unreliable), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            is ChatOutcome.MediaUnsupported -> Text(stringResource(R.string.chat_media_unsupported, stringResource(if (o.kind == MediaKind.IMAGE) R.string.chat_kind_image else R.string.chat_kind_audio)), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            is ChatOutcome.Failed -> Text(stringResource(R.string.chat_failed, if (o.code.startsWith("ext_")) externalErrorLabel(o.code) else o.code), style = MaterialTheme.typography.bodySmall, color = scheme.error)
        }
    }
}

/** Microphone glyph (the core icon set has none); tinted by the caller. */
private val MicIcon = ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 14f); curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f); verticalLineTo(5f); curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
        curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f); verticalLineTo(11f); curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f); close()
    }
    path(fill = SolidColor(Color.Black)) {
        moveTo(17f, 11f); curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f); curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f); horizontalLineTo(5f)
        curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.92f); verticalLineTo(21f); horizontalLineTo(13f); verticalLineTo(17.92f)
        curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f); close()
    }
}.build()
