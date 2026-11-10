package com.nathanhanapps.wayd.feature.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.feature.controls.RoutingSection
import com.nathanhanapps.wayd.feature.extensions.rememberClashInstalled

/** Tools tab: one place for everything that acts on traffic or inspects apps, instead of links scattered across tabs. */
@Composable
fun ToolsScreen(onOpenRules: () -> Unit, onOpenScanner: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tools_title), style = MaterialTheme.typography.headlineMedium)
        ToolRow(Icons.Filled.Build, stringResource(R.string.control_title), stringResource(R.string.tools_rules_desc), onOpenRules)
        ToolRow(Icons.Filled.Search, stringResource(R.string.apk_title), stringResource(R.string.tools_scanner_desc), onOpenScanner)
        Text(stringResource(R.string.tools_routing_header), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (rememberClashInstalled()) {
            RoutingSection()
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.tools_clash_missing_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.tools_clash_missing_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ToolRow(Icons.Filled.Settings, stringResource(R.string.settings_title), stringResource(R.string.tools_settings_desc), onOpenSettings)
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
