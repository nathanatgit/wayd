package com.nathanhanapps.wayd.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.ai.ExternalSend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place the user is asked before analysis text goes to an external provider. Any screen that runs an analysis passes
 * [ask] to the analyzer; the dialog itself is mounted once at the app root ([ExternalAiGateDialog]). If the asking screen goes
 * away, the question disappears with it and nothing is sent.
 */
@Singleton
class ExternalAiGate @Inject constructor() {
    private val _pending = MutableStateFlow<ExternalSend?>(null)
    val pending: StateFlow<ExternalSend?> = _pending.asStateFlow()
    private var answer: CompletableDeferred<Boolean>? = null

    suspend fun ask(send: ExternalSend): Boolean {
        val mine = CompletableDeferred<Boolean>()
        synchronized(this) {
            answer?.complete(false)
            answer = mine
            _pending.value = send
        }
        try {
            return mine.await()
        } finally {
            synchronized(this) {
                if (answer === mine) { answer = null; _pending.value = null }
            }
        }
    }

    fun answer(send: Boolean) = synchronized(this) { answer?.complete(send); Unit }
}

@HiltViewModel
class ExternalAiGateHolder @Inject constructor(val gate: ExternalAiGate) : ViewModel()

@Composable
fun ExternalAiGateDialog(holder: ExternalAiGateHolder = hiltViewModel()) {
    val send by holder.gate.pending.collectAsStateWithLifecycle()
    val s = send ?: return
    var showText by remember(s) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { holder.gate.answer(false) },
        title = { Text(stringResource(R.string.xg_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.xg_to, s.destination, s.model), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.xg_body), style = MaterialTheme.typography.bodyMedium)
                if (s.hasDecrypted) Text(stringResource(R.string.xg_decrypted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.xg_items, s.items), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showText = !showText }) { Text(stringResource(if (showText) R.string.xg_hide else R.string.xg_show)) }
                // Everything in the request body: the fixed instructions and the evidence, in the order they are sent.
                if (showText) Text(
                    s.system + "\n\n----\n\n" + s.user,
                    Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .verticalScroll(rememberScrollState()).padding(10.dp),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = { TextButton(onClick = { holder.gate.answer(true) }) { Text(stringResource(R.string.xg_send)) } },
        dismissButton = { TextButton(onClick = { holder.gate.answer(false) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
