package com.nathanhanapps.wayd.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.NavRow
import com.nathanhanapps.wayd.feature.extensions.ClashExtension
import com.nathanhanapps.wayd.feature.extensions.rememberClashInstalled

/** Tools tab: one flat list of everything that acts on traffic or inspects apps. */
@Composable
fun ToolsScreen(onOpenRules: () -> Unit, onOpenScanner: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.tab_tools), style = MaterialTheme.typography.headlineMedium)
        InsetGroup {
            NavRow(Icons.Filled.Build, stringResource(R.string.control_title), onClick = onOpenRules)
            GroupDivider()
            NavRow(Icons.Filled.Search, stringResource(R.string.apk_title), onClick = onOpenScanner)
            if (rememberClashInstalled()) {
                GroupDivider()
                NavRow(Icons.AutoMirrored.Filled.Send, stringResource(R.string.dashboard_clash)) { ClashExtension.intent(context)?.let(context::startActivity) }
            }
        }
        InsetGroup {
            NavRow(Icons.Filled.Settings, stringResource(R.string.settings_title), onClick = onOpenSettings)
        }
    }
}
