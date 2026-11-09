package com.nathanhanapps.wayd.feature.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.collectors.PermissionCategory
import com.nathanhanapps.wayd.collectors.PermissionUse
import com.nathanhanapps.wayd.feature.analysis.AppDetailViewModel
import com.nathanhanapps.wayd.feature.analysis.Badge
import com.nathanhanapps.wayd.feature.analysis.PermissionHistory
import com.nathanhanapps.wayd.privilege.ShizukuState

@Composable
private fun categoryName(c: PermissionCategory): String = stringResource(
    when (c) {
        PermissionCategory.LOCATION -> R.string.permcat_location
        PermissionCategory.CAMERA -> R.string.permcat_camera
        PermissionCategory.MICROPHONE -> R.string.permcat_microphone
        PermissionCategory.CLIPBOARD -> R.string.permcat_clipboard
        PermissionCategory.WIFI_SCAN -> R.string.permcat_wifi_scan
        PermissionCategory.BLUETOOTH -> R.string.permcat_bluetooth
        PermissionCategory.CONTACTS_CALENDAR -> R.string.permcat_contacts_calendar
        PermissionCategory.SMS_CALLS -> R.string.permcat_sms_calls
        PermissionCategory.STORAGE_MEDIA -> R.string.permcat_storage_media
        PermissionCategory.APP_USAGE -> R.string.permcat_app_usage
        PermissionCategory.ACCESSIBILITY -> R.string.permcat_accessibility
        PermissionCategory.SCREEN_CAPTURE -> R.string.permcat_screen_capture
        PermissionCategory.OVERLAY -> R.string.permcat_overlay
    },
)

/** "Permission use": when this app touched location, camera, microphone... and whether anyone could see it happen. */
@Composable
fun PermissionSection(vm: AppDetailViewModel) {
    val state by vm.permissions.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.section_permissions), style = MaterialTheme.typography.titleMedium)
        when (val s = state) {
            PermissionHistory.Loading -> Text(stringResource(R.string.perm_loading), style = MaterialTheme.typography.bodySmall)
            is PermissionHistory.Unavailable -> Unavailable(s.state, vm)
            PermissionHistory.Failed -> Failed(vm)
            is PermissionHistory.Ready -> if (s.uses.isEmpty()) {
                Text(stringResource(R.string.perm_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                Legend()
                s.uses.forEach { PermissionRow(it) }
                if (s.uses.any { it.coarse }) {
                    Text(stringResource(R.string.perm_coarse), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun Failed(vm: AppDetailViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.perm_failed), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::retryPermissions) { Text(stringResource(R.string.perm_retry)) }
        }
    }
}

@Composable
private fun Unavailable(state: ShizukuState, vm: AppDetailViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    when (state) {
                        ShizukuState.NOT_INSTALLED -> R.string.perm_not_installed
                        ShizukuState.NOT_RUNNING -> R.string.perm_not_running
                        else -> R.string.perm_needs_permission
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state == ShizukuState.NEEDS_PERMISSION) {
                Button(onClick = vm::requestShizuku) { Text(stringResource(R.string.perm_allow)) }
            } else {
                OutlinedButton(onClick = vm::retryPermissions) { Text(stringResource(R.string.perm_retry)) }
            }
        }
    }
}

@Composable
private fun PermissionRow(u: PermissionUse) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(categoryName(u.category), style = MaterialTheme.typography.bodyLarge)
            if (u.backgroundAccesses > 0) Badge("BG ${u.backgroundAccesses}", MaterialTheme.colorScheme.error)
            if (u.rejects > 0) Badge(stringResource(R.string.perm_rejected, u.rejects), MaterialTheme.colorScheme.outline)
        }
        Text(
            when {
                u.accesses == 0 -> stringResource(R.string.perm_only_blocked, u.rejects)
                u.backgroundAccesses > 0 -> stringResource(R.string.perm_counts, u.accesses, u.backgroundAccesses)
                else -> stringResource(R.string.perm_counts_visible, u.accesses)
            },
            style = MaterialTheme.typography.labelMedium,
        )
        if (u.accesses > 0) Lane(u.hourly, u.hourlyBackground)
    }
}

@Composable
private fun Legend() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Swatch(MaterialTheme.colorScheme.primary, stringResource(R.string.perm_legend_visible))
            Swatch(MaterialTheme.colorScheme.error, stringResource(R.string.perm_legend_background))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(10.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3f)) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** 24 cells, one per hour. Red marks hours with background use; blue hours with only visible use. Intensity = count. */
@Composable
private fun Lane(total: IntArray, background: IntArray) {
    val visible = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.error
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val max = (total.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val slot = size.width / 24f
        for (h in 0 until 24) {
            val color: Color = when {
                total[h] == 0 -> empty
                background[h] > 0 -> bg.copy(alpha = 0.35f + 0.65f * background[h] / max)
                else -> visible.copy(alpha = 0.35f + 0.65f * total[h] / max)
            }
            drawRoundRect(color, Offset(h * slot + 1f, 0f), Size(slot - 2f, size.height), CornerRadius(3f))
        }
    }
}
