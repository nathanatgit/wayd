package com.nathanhanapps.wayd.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.BuildConfig
import com.nathanhanapps.wayd.R

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val db by vm.db.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        Section(stringResource(R.string.settings_privilege), stringResource(R.string.settings_privilege_none))

        Section(
            stringResource(R.string.settings_database),
            when (val s = db) {
                DbStatus.Checking -> stringResource(R.string.settings_database_checking)
                is DbStatus.Encrypted -> stringResource(R.string.settings_database_encrypted, s.cipherVersion)
                DbStatus.Unencrypted -> stringResource(R.string.settings_database_unencrypted)
            },
        )

        Section(
            stringResource(R.string.settings_about),
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME) +
                "\n" + stringResource(R.string.settings_local_only),
        )
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}
