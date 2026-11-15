package com.nathanhanapps.wayd.feature.record

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.CapturePhase
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.NavRow
import com.nathanhanapps.wayd.feature.common.SpeedChart
import com.nathanhanapps.wayd.feature.common.StatTile
import com.nathanhanapps.wayd.feature.common.SwitchRow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

/**
 * Home, compact: the VPN switch lives in the header, then live speed, a few numbers, and one inset list of switches and
 * places. It observes no database table, so it costs nothing while the VPN records.
 */
@Composable
fun RecordScreen(
    onOpenRules: () -> Unit,
    onOpenTraffic: () -> Unit,
    onOpenInsights: () -> Unit,
    vm: RecordViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val recording by vm.recording.enabled.collectAsStateWithLifecycle()
    val policy by vm.blocking.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val running = ui.phase == CapturePhase.RUNNING
    val starting = ui.phase == CapturePhase.STARTING
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshRules(); vm.refreshAttention() }
    val attention by vm.attention.collectAsStateWithLifecycle()
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.start()
    }
    fun startWithConsent() {
        val intent = VpnService.prepare(context)
        if (intent != null) vpnConsent.launch(intent) else vm.start()
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startWithConsent() }
    val setVpn: (Boolean) -> Unit = { on ->
        when {
            !on -> vm.stop()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> startWithConsent()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header: name, a status dot + word, and the VPN switch.
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val dot = when (ui.phase) {
                        CapturePhase.RUNNING -> MaterialTheme.colorScheme.primary
                        CapturePhase.ERROR -> MaterialTheme.colorScheme.error
                        CapturePhase.STARTING -> MaterialTheme.colorScheme.tertiary
                        CapturePhase.IDLE -> MaterialTheme.colorScheme.outline
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Text(
                        stringResource(when (ui.phase) {
                            CapturePhase.RUNNING -> R.string.home_on
                            CapturePhase.STARTING -> R.string.record_status_starting
                            CapturePhase.ERROR -> R.string.record_status_error
                            CapturePhase.IDLE -> R.string.home_off
                        }),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val vpnLabel = stringResource(R.string.dashboard_engine)
            Switch(running || starting, setVpn, enabled = !starting, modifier = Modifier.semantics { contentDescription = vpnLabel })
        }

        SpeedChart(speed.up, speed.down, stringResource(R.string.stat_up), stringResource(R.string.stat_down))

        val activeRules = policy.rules.count { it.id in policy.activeIds }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(ui.stats.flows.toString(), stringResource(R.string.stat_flows), Modifier.weight(1f))
            StatTile(ui.stats.apps.toString(), stringResource(R.string.stat_apps), Modifier.weight(1f))
            StatTile(activeRules.toString(), stringResource(R.string.tab_rules), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Formatter.formatShortFileSize(context, ui.stats.bytesUp), stringResource(R.string.stat_up), Modifier.weight(1f))
            StatTile(Formatter.formatShortFileSize(context, ui.stats.bytesDown), stringResource(R.string.stat_down), Modifier.weight(1f))
        }

        InsetGroup {
            SwitchRow(Icons.Filled.Edit, stringResource(R.string.dashboard_record), recording, enabled = !starting, onChange = vm.recording::setEnabled)
            GroupDivider()
            NavRow(ImageVector.vectorResource(R.drawable.sync_alt_24), stringResource(R.string.tab_traffic), stringResource(R.string.home_traffic_value, ui.stats.flows, ui.stats.apps), onClick = onOpenTraffic)
            GroupDivider()
            NavRow(ImageVector.vectorResource(R.drawable.rule_24), stringResource(R.string.tab_rules), stringResource(R.string.home_rules_value, policy.rules.count { it.id in policy.activeIds }, policy.rules.size), onClick = onOpenRules)
            GroupDivider()
            NavRow(ImageVector.vectorResource(R.drawable.analytics_24), stringResource(R.string.tab_insights), attention?.let { stringResource(R.string.home_attention_value, it) }, onClick = onOpenInsights)
        }
    }
}
