package com.nathanhanapps.wayd.feature.export

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.nathanhanapps.wayd.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ImportViewModel @Inject constructor(private val importer: ImportService) : ViewModel() {
    val running = importer.running
    fun start(source: Uri) = importer.start(source)
}

/**
 * "Import": picks a WAYD detail JSON and merges it into the normal history (see [ImportService]). The import runs in the service's
 * own scope, so leaving this screen does not stop it halfway; the service shows the result.
 */
@Composable
fun ImportButton(modifier: Modifier = Modifier, vm: ImportViewModel = hiltViewModel()) {
    val busy by vm.running.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.start(uri) }
    OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy, modifier = modifier) {
        Text(stringResource(R.string.import_button))
    }
}
