package com.nathanhanapps.wayd.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
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
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InfoRow
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.NavRow
import com.nathanhanapps.wayd.feature.common.SubPage
import com.nathanhanapps.wayd.privilege.ShizukuState
import com.nathanhanapps.wayd.research.ResearchFeatures

/** Settings: a short list of destinations. Each topic has its own page instead of one long scroll. */
@Composable
fun SettingsScreen(onOpenAi: () -> Unit, onOpenAccess: () -> Unit, onOpenResearch: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val db by vm.db.collectAsStateWithLifecycle()
    val root by vm.root.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val resettingWarnings by vm.resettingWarnings.collectAsStateWithLifecycle()
    val warningsReset by vm.warningsReset.collectAsStateWithLifecycle()
    val aiValue = vm.aiSummary(ai, stringResource(R.string.settings_ai_none), stringResource(R.string.settings_ai_device), stringResource(R.string.settings_ai_external))
    val access = when {
        root == RootState.AVAILABLE -> stringResource(R.string.settings_privilege_root)
        shizuku == ShizukuState.READY -> stringResource(R.string.settings_val_shizuku)
        else -> stringResource(R.string.settings_val_none)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        InsetGroup {
            NavRow(Icons.Filled.Star, stringResource(R.string.settings_ai), aiValue, onClick = onOpenAi)
            GroupDivider()
            NavRow(Icons.Filled.Lock, stringResource(R.string.settings_privilege), access, onClick = onOpenAccess)
            if (ResearchFeatures.AVAILABLE) {
                GroupDivider()
                NavRow(Icons.Filled.Warning, stringResource(R.string.settings_research), onClick = onOpenResearch)
            }
        }
        InsetGroup {
            InfoRow(
                Icons.Filled.Info, stringResource(R.string.settings_database),
                when (val s = db) {
                    DbStatus.Checking -> stringResource(R.string.settings_database_checking)
                    is DbStatus.Encrypted -> stringResource(R.string.settings_database_encrypted, s.cipherVersion)
                    DbStatus.Unencrypted -> stringResource(R.string.settings_database_unencrypted)
                },
            )
            GroupDivider()
            InfoRow(Icons.Filled.Info, stringResource(R.string.settings_about), BuildConfig.VERSION_NAME)
        }
        Text(stringResource(R.string.settings_local_only), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = vm::reenableAllWarnings, enabled = !resettingWarnings) {
            Text(stringResource(R.string.settings_reenable_warnings))
        }
        Text(
            stringResource(if (warningsReset) R.string.settings_warnings_reset else R.string.settings_warnings_detail),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Settings → Privileges: what WAYD can reach beyond a normal app (root, Shizuku). */
@Composable
fun AccessSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val root by vm.root.collectAsStateWithLifecycle()
    val shizuku by vm.shizuku.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    SubPage(stringResource(R.string.settings_privilege), onBack) {
        InsetGroup {
            InfoRow(
                Icons.Filled.Lock, stringResource(R.string.settings_privilege_root),
                stringResource(when (root) { RootState.AVAILABLE -> R.string.val_available; RootState.UNAVAILABLE -> R.string.val_unavailable; RootState.UNKNOWN -> R.string.val_unchecked }),
            )
            GroupDivider()
            InfoRow(
                Icons.Filled.Lock, "Shizuku",
                stringResource(when (shizuku) {
                    ShizukuState.NOT_INSTALLED -> R.string.val_sz_not_installed
                    ShizukuState.NOT_RUNNING -> R.string.val_sz_not_running
                    ShizukuState.NEEDS_PERMISSION -> R.string.val_sz_needs_permission
                    ShizukuState.READY -> R.string.val_sz_ready
                }),
            )
        }
        FilledTonalButton(onClick = vm::checkRoot, enabled = !checking) {
            Text(stringResource(if (checking) R.string.settings_root_checking else R.string.settings_root_check))
        }
    }
}

/** Settings → HTTPS decryption (research build). Its own confirmation steps are unchanged. */
@Composable
fun ResearchSettingsScreen(onBack: () -> Unit) {
    SubPage(stringResource(R.string.settings_research), onBack) { ResearchFeatures.Settings() }
}
