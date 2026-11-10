package com.nathanhanapps.wayd.feature.extensions

import android.Manifest
import android.content.Context
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.CaptureController
import com.nathanhanapps.wayd.capture.CapturePhase
import com.nathanhanapps.wayd.capture.CaptureState
import com.nathanhanapps.wayd.capture.NativeNet
import com.nathanhanapps.wayd.capture.RoutingApp
import com.nathanhanapps.wayd.capture.RoutingDownloadException
import com.nathanhanapps.wayd.capture.RoutingDownloads
import com.nathanhanapps.wayd.capture.RoutingGeoIp
import com.nathanhanapps.wayd.capture.RoutingProfile
import com.nathanhanapps.wayd.capture.RoutingResourceUrls
import com.nathanhanapps.wayd.capture.RoutingSettings
import com.nathanhanapps.wayd.core.ui.WaydTheme
import com.nathanhanapps.wayd.feature.analysis.AppIcon
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.common.GroupDivider
import com.nathanhanapps.wayd.feature.common.InsetGroup
import com.nathanhanapps.wayd.feature.common.NavRow
import com.nathanhanapps.wayd.feature.common.SpeedChart
import com.nathanhanapps.wayd.feature.common.SpeedSamples
import com.nathanhanapps.wayd.feature.common.StatTile
import com.nathanhanapps.wayd.feature.common.SwitchRow
import com.nathanhanapps.wayd.feature.common.speedSamples
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Optional native Compose extension; it shares WAYD's VPN, never owns another. */
@AndroidEntryPoint
class ClashExtensionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WaydTheme { ClashExtensionScreen(onBack = ::finish) } }
    }
}

/** One declared resource of the profile being imported, with its own download state. */
data class ImportResource(val key: String, val kind: String, val source: String, val status: Status = Status.Waiting, val error: String? = null, val bytes: Int = 0) {
    enum class Status { Waiting, Running, Done, Failed }
}

/** The profile currently being imported: reviewed, then its resources downloaded, then saved. Apps are chosen separately. */
data class ImportSession(val name: String, val nodes: Int, val groups: Int, val rules: Int, val overrides: List<String>)

/** One row of the access-control picker; [seen] marks apps WAYD has recorded traffic for. */
data class AccessApp(val pkg: String, val label: String, val system: Boolean, val seen: Boolean)

@HiltViewModel
class ClashExtensionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val routing: RoutingSettings,
    val capture: CaptureState,
    val controller: CaptureController,
    val geoIp: RoutingGeoIp,
    val labels: AppLabels,
    private val flows: com.nathanhanapps.wayd.core.db.FlowDao,
) : ViewModel() {
    /** WAYD's app list: installed single-package apps, apps seen in traffic first. Loaded when the access page opens. */
    val accessApps = MutableStateFlow<List<AccessApp>?>(null)

    fun loadAccessApps() = viewModelScope.launch {
        val seen = withContext(Dispatchers.IO) { runCatching { flows.distinctPackages().toSet() }.getOrDefault(emptySet()) }
        accessApps.value = routing.apps().map { AccessApp(it.pkg, it.label, it.system, it.pkg in seen) }
            .sortedWith(compareByDescending<AccessApp> { it.seen }.thenBy { it.label.lowercase() })
    }

    fun saveAccess(mode: com.nathanhanapps.wayd.capture.RoutingMode, packages: Set<String>, done: () -> Unit) = action {
        routing.setAccess(mode, packages)
        done()
    }

    val busy = MutableStateFlow(false)
    /** Stable failure code of the last action (see [errorLabel]); null when it succeeded. */
    val error = MutableStateFlow<String?>(null)
    val profiles = MutableStateFlow<List<RoutingProfile>>(emptyList())
    val overview = MutableStateFlow<JSONObject?>(null)
    val selected = MutableStateFlow<RoutingProfile?>(null)
    val session = MutableStateFlow<ImportSession?>(null)
    val resources = MutableStateFlow<List<ImportResource>>(emptyList())
    val downloading = MutableStateFlow(false)
    val geoUpdated = MutableStateFlow(0L)
    val viaProxy = MutableStateFlow(false)
    val mirror = MutableStateFlow(false)
    val geoSource = MutableStateFlow(DEFAULT_GEOIP)
    val speed: StateFlow<SpeedSamples> = speedSamples(capture).stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), SpeedSamples())

    private var actionJob: Job? = null
    private var downloadJob: Job? = null
    private val activeIds = java.util.Collections.synchronizedSet(HashSet<String>())
    // Downloaded content is memory-only: provider credentials never enter plaintext files.
    private val downloaded = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var pending: ByteArray? = null
    private var pendingId: String? = null
    private val native = NativeNet()

    init { refresh() }

    fun refresh() = action {
        routing.refresh(); profiles.value = routing.profiles(); geoIp.load(); geoUpdated.value = geoIp.status().optLong("updated")
        val active = profiles.value.firstOrNull { it.selected } ?: profiles.value.firstOrNull()
        if (active != null) show(active)
    }

    private suspend fun call(profile: ByteArray, request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject(String(native.nativeRoutingCommand(profile, request.toString().toByteArray()), Charsets.UTF_8))
        result.optString("error").takeIf { it.isNotEmpty() }?.let { throw RoutingDownloadException(it) }
        result
    }

    private suspend fun show(profile: RoutingProfile) {
        overview.value = call(routing.profile(profile.id), JSONObject().put("action", "inspect"))
        selected.value = profile
    }

    fun open(profile: RoutingProfile) = action { show(profile) }

    // ---- Import ----

    fun load(uri: Uri?) {
        if (uri == null) return
        action {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.take(128) ?: "Clash.yaml"
            val bytes = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { RoutingDownloads.readBounded(it, 4 * 1024 * 1024) } }
            begin(name.substringBeforeLast('.'), bytes, null)
        }
    }

    fun subscription(name: String, url: String) = action {
        begin(name.trim().take(128).ifEmpty { Uri.parse(url).host ?: "Clash" }, RoutingDownloads.fetch(url.trim(), 4 * 1024 * 1024), null)
    }

    /** Makes a saved profile the one routing uses. App access is unchanged. */
    fun activate(profile: RoutingProfile) = action {
        routing.select(profile.id)
        profiles.value = routing.profiles()
        profiles.value.firstOrNull { it.id == profile.id }?.let { show(it) }
    }

    private suspend fun begin(name: String, bytes: ByteArray, id: String?) {
        val review = call(bytes, JSONObject().put("action", "inspect"))
        pending = bytes; pendingId = id
        downloaded.clear()
        geoIp.load()
        val geo = geoIp.status()
        val list = review.optJSONArray("resources")
        resources.value = (0 until (list?.length() ?: 0)).map { i ->
            val r = list!!.getJSONObject(i)
            val item = ImportResource(r.getString("key"), r.getString("kind"), r.getString("url"))
            // The shared GeoIP database is reused when it is already installed.
            if (item.kind == "geoip" && geo.optLong("updated") > 0) item.copy(status = ImportResource.Status.Done) else item
        }
        val overrides = review.optJSONArray("overrides")
        session.value = ImportSession(
            name, review.optJSONArray("proxies")?.length() ?: 0, review.optJSONArray("groups")?.length() ?: 0,
            review.optJSONArray("rules")?.length() ?: 0, (0 until (overrides?.length() ?: 0)).map { overrides!!.getString(it) },
        )
    }

    fun downloadUrl(r: ImportResource): String {
        val source = if (r.kind == "geoip") geoSource.value.trim().ifEmpty { r.source } else r.source
        return RoutingResourceUrls.forDownload(source, mirror.value)
    }

    /** Downloads every resource that is not done yet, three at a time. One failure does not stop the others. */
    fun download() {
        if (downloading.value) return
        downloading.value = true; error.value = null
        downloadJob = viewModelScope.launch {
            try {
                val gate = Semaphore(3)
                coroutineScope {
                    resources.value.filter { it.status != ImportResource.Status.Done }.map { r ->
                        async { gate.withPermit { fetchOne(r) } }
                    }.awaitAll()
                }
            } finally { downloading.value = false }
        }
    }

    private suspend fun fetchOne(r: ImportResource) {
        setStatus(r.key) { it.copy(status = ImportResource.Status.Running, error = null) }
        val url = downloadUrl(r)
        val limit = if (r.kind == "geoip") 32 * 1024 * 1024 else 8 * 1024 * 1024
        try {
            val body = if (!viaProxy.value) RoutingDownloads.fetch(url, limit) else nativeFetch(url, limit)
            if (r.kind == "geoip") {
                runCatching { geoIp.install(body, url) }.getOrElse { throw RoutingDownloadException("resource_geoip_invalid") }
                geoUpdated.value = geoIp.status().optLong("updated")
            } else {
                if (downloaded.values.sumOf { it.size } + body.size > 28 * 1024 * 1024) throw RoutingDownloadException("resource_too_large")
                downloaded[r.key] = body
            }
            setStatus(r.key) { it.copy(status = ImportResource.Status.Done, bytes = body.size) }
        } catch (e: CancellationException) {
            setStatus(r.key) { it.copy(status = ImportResource.Status.Failed, error = "resource_canceled") }
            throw e
        } catch (e: RoutingDownloadException) {
            setStatus(r.key) { it.copy(status = ImportResource.Status.Failed, error = e.code) }
        } catch (_: Exception) {
            setStatus(r.key) { it.copy(status = ImportResource.Status.Failed, error = "resource_network") }
        }
    }

    private suspend fun nativeFetch(url: String, limit: Int): ByteArray {
        val id = java.util.UUID.randomUUID().toString()
        activeIds += id
        try {
            val result = call(requireNotNull(pending), JSONObject().put("action", "download").put("id", id).put("url", url).put("viaProxy", true).put("limit", limit))
            return Base64.decode(result.getString("body"), Base64.NO_WRAP)
        } finally { activeIds -= id }
    }

    private fun setStatus(key: String, f: (ImportResource) -> ImportResource) = resources.update { list -> list.map { if (it.key == key) f(it) else it } }

    fun cancelDownloads() {
        downloadJob?.cancel()
        val ids = synchronized(activeIds) { activeIds.toList() }
        if (ids.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { native.nativeRoutingCommand(byteArrayOf(1), JSONObject().put("action", "cancel-download").put("id", it).toString().toByteArray()) }
        }
    }

    /** All resources are in memory: build the validated bundle and save it as a profile (routing stays as it was). */
    fun prepare() = action {
        val bytes = requireNotNull(pending)
        val content = JSONObject()
        resources.value.filter { it.kind != "geoip" }.forEach { r -> content.put(r.key, Base64.encodeToString(requireNotNull(downloaded[r.key]), Base64.NO_WRAP)) }
        val bundle = Base64.decode(call(bytes, JSONObject().put("action", "prepare").put("resources", content)).getString("bundle"), Base64.NO_WRAP)
        val id = routing.archive(requireNotNull(session.value).name, bundle, pendingId)
        closeImport()
        profiles.value = routing.profiles()
        profiles.value.firstOrNull { it.id == id }?.let { show(it) }
    }

    fun closeImport() {
        cancelDownloads()
        pending = null; pendingId = null; session.value = null; resources.value = emptyList(); downloaded.clear()
    }

    // ---- Profile settings ----

    fun configure(group: String? = null, member: String? = null, mode: String? = null) = action {
        val profile = requireNotNull(selected.value)
        val q = JSONObject().put("action", "configure")
        if (group != null) q.put("selections", JSONObject().put(group, member))
        if (mode != null) q.put("mode", mode)
        val result = call(routing.profile(profile.id), q)
        routing.archive(profile.name, Base64.decode(result.getString("bundle"), Base64.NO_WRAP), profile.id)
        profiles.value = routing.profiles()
        show(profile)
    }

    fun remove(profile: RoutingProfile) = action {
        routing.removeProfile(profile.id); profiles.value = routing.profiles(); selected.value = null; overview.value = null
        profiles.value.firstOrNull()?.let { show(it) }
    }

    fun enable(value: Boolean) = action { routing.setEnabled(value) }

    fun updateGeoIp() = action {
        val url = RoutingResourceUrls.forDownload(geoSource.value.trim(), mirror.value)
        val body = RoutingDownloads.fetch(url, 32 * 1024 * 1024)
        runCatching { geoIp.install(body, url) }.getOrElse { throw RoutingDownloadException("resource_geoip_invalid") }
        geoUpdated.value = geoIp.status().optLong("updated")
    }

    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        actionJob = viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: RoutingDownloadException) { error.value = e.code } catch (_: Exception) { error.value = "failed" } finally { busy.value = false }
        }
    }

    companion object {
        const val DEFAULT_GEOIP = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/country.mmdb"
    }
}

@Composable
private fun errorLabel(code: String): String = when {
    code == "resource_dns" -> stringResource(R.string.cx_err_dns)
    code == "resource_connect" -> stringResource(R.string.cx_err_connect)
    code == "resource_tls" -> stringResource(R.string.cx_err_tls)
    code == "resource_timeout" -> stringResource(R.string.cx_err_timeout)
    code == "resource_stalled" -> stringResource(R.string.cx_err_stalled)
    code == "resource_proxy" || code == "bootstrap_node_required" -> stringResource(R.string.cx_err_proxy)
    code == "resource_too_large" -> stringResource(R.string.cx_err_too_large)
    code == "resource_empty" -> stringResource(R.string.cx_err_empty)
    code == "resource_html" -> stringResource(R.string.cx_err_html)
    code == "resource_geoip_invalid" -> stringResource(R.string.cx_err_geoip)
    code == "resource_canceled" -> stringResource(R.string.cx_err_canceled)
    code.startsWith("resource_http_") -> stringResource(R.string.cx_err_http, code.removePrefix("resource_http_"))
    code == "resource_network" -> stringResource(R.string.cx_err_network)
    else -> stringResource(R.string.cx_err_generic)
}

private enum class ClashPage(val label: Int, val icon: ImageVector) {
    Dashboard(R.string.clash_dashboard, Icons.Filled.Home),
    Proxies(R.string.clash_proxies, Icons.AutoMirrored.Filled.Send),
    Profiles(R.string.clash_profiles, Icons.AutoMirrored.Filled.List),
    Tools(R.string.cx_tools, Icons.Filled.Build),
}

private enum class ClashTool { Access, Connections, Rules, GeoIp, Events }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClashExtensionScreen(onBack: () -> Unit, vm: ClashExtensionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by vm.capture.ui.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var tool by rememberSaveable { mutableStateOf<ClashTool?>(null) }
    var adding by remember { mutableStateOf(false) }
    var subscription by remember { mutableStateOf(false) }
    val stopped = state.phase in setOf(CapturePhase.IDLE, CapturePhase.ERROR)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::load)
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == android.app.Activity.RESULT_OK) vm.controller.start() }
    val start: () -> Unit = { val intent = VpnService.prepare(context); if (intent != null) vpn.launch(intent) else vm.controller.start() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val setVpn: (Boolean) -> Unit = { on -> if (!on) vm.controller.stop() else if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start() }
    val current = ClashPage.entries[page]
    BackHandler(tool != null) { tool = null }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (tool != null) tool = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.clash_back)) }
                Text(
                    stringResource(tool?.let { toolLabel(it) } ?: if (current == ClashPage.Dashboard) R.string.cx_title else current.label),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                )
                if (current == ClashPage.Dashboard && tool == null) {
                    val label = stringResource(R.string.dashboard_engine)
                    Switch(!stopped, setVpn, enabled = state.phase != CapturePhase.STARTING, modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = label })
                } else {
                    IconButton(onClick = vm::refresh, enabled = !busy) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.clash_refresh)) }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                ClashPage.entries.forEachIndexed { i, p ->
                    NavigationBarItem(selected = page == i, onClick = { page = i; tool = null }, icon = { Icon(p.icon, contentDescription = null) }, label = { Text(stringResource(p.label)) })
                }
            }
        },
        floatingActionButton = {
            if (current == ClashPage.Profiles && stopped) FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cx_add)) }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (busy && session == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null && session == null) ErrorLine(error!!)
            when {
                tool != null -> ToolPage(tool!!, vm, stopped, busy) { tool = null }
                current == ClashPage.Dashboard -> DashboardPage(vm, stopped, busy, openAccess = { tool = ClashTool.Access }) { page = it.ordinal }
                current == ClashPage.Proxies -> ProxiesPage(vm, stopped, busy)
                current == ClashPage.Profiles -> ProfilesPage(vm, stopped, busy)
                current == ClashPage.Tools -> ToolsPage(vm) { tool = it }
            }
        }
    }

    if (adding) ModalBottomSheet(onDismissRequest = { adding = false }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            InsetGroup {
                NavRow(Icons.AutoMirrored.Filled.List, stringResource(R.string.cx_add_file)) { adding = false; picker.launch(arrayOf("*/*")) }
                GroupDivider()
                NavRow(Icons.Filled.LocationOn, stringResource(R.string.cx_add_url)) { adding = false; subscription = true }
            }
        }
    }
    if (subscription) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { subscription = false },
            title = { Text(stringResource(R.string.cx_add_url)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.clash_subscription_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.clash_profile_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = url.trim().startsWith("https://"), onClick = { subscription = false; vm.subscription(name, url) }) { Text(stringResource(R.string.clash_download)) } },
            dismissButton = { TextButton(onClick = { subscription = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    session?.let { ImportDialog(it, vm, stopped) }
}

private fun toolLabel(t: ClashTool) = when (t) {
    ClashTool.Connections -> R.string.clash_connections
    ClashTool.Rules -> R.string.clash_rules
    ClashTool.GeoIp -> R.string.clash_geoip
    ClashTool.Events -> R.string.clash_events
    ClashTool.Access -> R.string.cx_access
}

@Composable
private fun ErrorLine(code: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Text(errorLabel(code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

// ---------------- Dashboard ----------------

@Composable
private fun DashboardPage(vm: ClashExtensionViewModel, stopped: Boolean, busy: Boolean, openAccess: () -> Unit, go: (ClashPage) -> Unit) {
    val state by vm.capture.ui.collectAsStateWithLifecycle()
    val routing by vm.routing.state.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val mode = overview?.optString("mode").orEmpty()
    val firstGroup = overview?.optJSONArray("groups")?.takeIf { it.length() > 0 }?.getJSONObject(0)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SpeedChart(speed.up, speed.down, stringResource(R.string.stat_up), stringResource(R.string.stat_down)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(state.routes.proxied.toString(), stringResource(R.string.clash_out_proxied), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                StatTile(state.routes.direct.toString(), stringResource(R.string.clash_out_direct), Modifier.weight(1f))
                StatTile(state.routes.blocked.toString(), stringResource(R.string.clash_out_blocked), Modifier.weight(1f))
                StatTile(state.routes.errors.toString(), stringResource(R.string.clash_out_errors), Modifier.weight(1f), if (state.routes.errors > 0) MaterialTheme.colorScheme.error else null)
            }
        }
        item {
            ModeSelector(mode, enabled = stopped && !busy && selected != null) { vm.configure(mode = it) }
        }
        item {
            InsetGroup {
                SwitchRow(Icons.AutoMirrored.Filled.Send, stringResource(R.string.cx_routing), routing.enabled, enabled = stopped && routing.available && !busy, onChange = vm::enable)
                GroupDivider()
                NavRow(Icons.Filled.Check, stringResource(R.string.cx_access), stringResource(if (routing.mode == com.nathanhanapps.wayd.capture.RoutingMode.BLACKLIST) R.string.cx_access_black_value else R.string.cx_access_white_value, routing.apps.size), onClick = openAccess)
                GroupDivider()
                NavRow(Icons.AutoMirrored.Filled.List, stringResource(R.string.cx_profile), selected?.name ?: stringResource(R.string.cx_none)) { go(ClashPage.Profiles) }
                if (firstGroup != null) {
                    GroupDivider()
                    NavRow(Icons.Filled.LocationOn, firstGroup.optString("name"), firstGroup.optString("selected")) { go(ClashPage.Proxies) }
                }
            }
        }
        if (!stopped) item { Hint(stringResource(R.string.cx_stop_to_edit)) }
    }
}

@Composable
private fun ModeSelector(mode: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val modes = listOf("rule" to R.string.clash_rule_mode, "global" to R.string.clash_global_mode, "direct" to R.string.clash_direct_mode)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (key, label) ->
            SegmentedButton(selected = mode == key, onClick = { onSelect(key) }, enabled = enabled, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- Proxies ----------------

@Composable
private fun ProxiesPage(vm: ClashExtensionViewModel, stopped: Boolean, busy: Boolean) {
    val overview by vm.overview.collectAsStateWithLifecycle()
    val groups = overview?.optJSONArray("groups")
    val types = remember(overview) {
        val p = overview?.optJSONArray("proxies")
        (0 until (p?.length() ?: 0)).associate { p!!.getJSONObject(it).let { o -> o.optString("name") to o.optString("type") } }
    }
    var open by rememberSaveable { mutableStateOf(setOf(0)) }
    if (groups == null || groups.length() == 0) { Empty(stringResource(R.string.cx_empty_profiles)); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(groups.length()) { index ->
            val group = groups.getJSONObject(index)
            val members = group.optJSONArray("members")
            val names = (0 until (members?.length() ?: 0)).map { members!!.getString(it) }
            val selectable = group.optString("type") == "select" && stopped && !busy
            val expanded = index in open
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (expanded) open - index else open + index }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(group.optString("name"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TypeTag(group.optString("type"))
                        }
                        Text(group.optString("selected"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(names.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
                if (expanded) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { name ->
                                NodeTile(name, types[name] ?: stringResource(R.string.cx_group), name == group.optString("selected"), selectable, Modifier.weight(1f)) {
                                    vm.configure(group.getString("name"), name)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (!stopped) item { Hint(stringResource(R.string.cx_stop_to_edit)) }
    }
}

@Composable
private fun NodeTile(name: String, type: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) scheme.primary else scheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = if (selected) scheme.onPrimaryContainer else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(type, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun TypeTag(type: String) {
    Text(
        type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- Profiles ----------------

@Composable
private fun ProfilesPage(vm: ClashExtensionViewModel, stopped: Boolean, busy: Boolean) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<RoutingProfile?>(null) }
    if (profiles.isEmpty()) { Empty(stringResource(R.string.cx_empty_profiles)); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(profiles, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { vm.open(p) }.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = p.selected, onClick = { vm.activate(p) }, enabled = stopped && !busy)
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (selected?.id == p.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.updated)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { deleting = p }, enabled = stopped && !busy) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.routing_delete)) }
            }
        }
        if (!stopped) item { Hint(stringResource(R.string.cx_stop_to_edit)) }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(p.name) },
            text = { Text(stringResource(R.string.routing_delete_warning)) },
            confirmButton = { TextButton(onClick = { vm.remove(p); deleting = null }) { Text(stringResource(R.string.cx_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

// ---------------- Tools ----------------

@Composable
private fun ToolsPage(vm: ClashExtensionViewModel, open: (ClashTool) -> Unit) {
    val overview by vm.overview.collectAsStateWithLifecycle()
    val geoUpdated by vm.geoUpdated.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        InsetGroup {
            NavRow(Icons.Filled.Check, stringResource(R.string.cx_access)) { open(ClashTool.Access) }
            GroupDivider()
            NavRow(Icons.AutoMirrored.Filled.List, stringResource(R.string.clash_connections)) { open(ClashTool.Connections) }
            GroupDivider()
            NavRow(Icons.Filled.Build, stringResource(R.string.clash_rules), (overview?.optJSONArray("rules")?.length() ?: 0).toString()) { open(ClashTool.Rules) }
            GroupDivider()
            NavRow(Icons.Filled.LocationOn, stringResource(R.string.clash_geoip), if (geoUpdated == 0L) stringResource(R.string.clash_geoip_missing) else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(geoUpdated))) { open(ClashTool.GeoIp) }
            GroupDivider()
            NavRow(Icons.Filled.Info, stringResource(R.string.clash_events)) { open(ClashTool.Events) }
        }
    }
}

@Composable
private fun ToolPage(tool: ClashTool, vm: ClashExtensionViewModel, stopped: Boolean, busy: Boolean, close: () -> Unit) {
    when (tool) {
        ClashTool.Connections -> {
            // Collecting here (and only here) is what switches on the live per-connection table in CaptureState.
            val connections by vm.capture.connections.collectAsStateWithLifecycle()
            val context = LocalContext.current
            if (connections.isEmpty()) { Empty(stringResource(R.string.cx_no_live)); return }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                items(connections, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.destination}:${c.port}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("↑${com.nathanhanapps.wayd.feature.analysis.fmtBytes(context, c.bytesUp)} ↓${com.nathanhanapps.wayd.feature.analysis.fmtBytes(context, c.bytesDown)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        ClashTool.Rules -> {
            val overview by vm.overview.collectAsStateWithLifecycle()
            val rules = overview?.optJSONArray("rules")
            if (rules == null || rules.length() == 0) { Empty(stringResource(R.string.cx_empty_profiles)); return }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                items(rules.length()) { i ->
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${i + 1}", Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(rules.getString(i), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        ClashTool.GeoIp -> {
            val geoUpdated by vm.geoUpdated.collectAsStateWithLifecycle()
            val source by vm.geoSource.collectAsStateWithLifecycle()
            val mirror by vm.mirror.collectAsStateWithLifecycle()
            val error by vm.error.collectAsStateWithLifecycle()
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                InsetGroup {
                    NavRow(Icons.Filled.LocationOn, stringResource(R.string.cx_updated), if (geoUpdated == 0L) stringResource(R.string.clash_geoip_missing) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(geoUpdated)), enabled = false) {}
                    GroupDivider()
                    SwitchRow(Icons.Filled.Refresh, stringResource(R.string.cx_mirror), mirror, enabled = !busy) { vm.mirror.value = it }
                }
                OutlinedTextField(source, { vm.geoSource.value = it }, label = { Text(stringResource(R.string.clash_geoip_source)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = vm::updateGeoIp, enabled = stopped && !busy && source.startsWith("https://"), modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.cx_update))
                }
                if (error == "resource_dns" || error == "resource_connect") Hint(stringResource(R.string.cx_net_hint))
            }
        }
        ClashTool.Access -> AccessPage(vm, stopped, onDone = close)
        ClashTool.Events -> {
            val state by vm.capture.ui.collectAsStateWithLifecycle()
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(state.routes.proxied.toString(), stringResource(R.string.clash_out_proxied), Modifier.weight(1f))
                    StatTile(state.routes.direct.toString(), stringResource(R.string.clash_out_direct), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(state.routes.blocked.toString(), stringResource(R.string.clash_out_blocked), Modifier.weight(1f))
                    StatTile(state.routes.errors.toString(), stringResource(R.string.clash_out_errors), Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------- Import ----------------

@Composable
private fun ImportDialog(s: ImportSession, vm: ClashExtensionViewModel, stopped: Boolean) {
    Dialog(onDismissRequest = vm::closeImport, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::closeImport) { Icon(Icons.Filled.Close, contentDescription = stringResource(android.R.string.cancel)) }
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.cx_step_resources), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ResourceStep(s, vm, stopped)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ResourceStep(s: ImportSession, vm: ClashExtensionViewModel, stopped: Boolean) {
    val list by vm.resources.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val viaProxy by vm.viaProxy.collectAsStateWithLifecycle()
    val mirror by vm.mirror.collectAsStateWithLifecycle()
    val geoSource by vm.geoSource.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showGeo by remember { mutableStateOf(false) }
    val done = list.count { it.status == ImportResource.Status.Done }
    val failed = list.count { it.status == ImportResource.Status.Failed }
    val allDone = done == list.size
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(s.nodes.toString(), stringResource(R.string.cx_nodes), Modifier.weight(1f))
                StatTile(s.groups.toString(), stringResource(R.string.cx_groups), Modifier.weight(1f))
                StatTile(s.rules.toString(), stringResource(R.string.clash_rules), Modifier.weight(1f))
                StatTile("$done/${list.size}", stringResource(R.string.cx_resources), Modifier.weight(1f))
            }
        }
        if (list.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.cx_download_via), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false to R.string.cx_via_direct, true to R.string.cx_via_node).forEachIndexed { i, (value, label) ->
                            SegmentedButton(selected = viaProxy == value, onClick = { vm.viaProxy.value = value }, enabled = !downloading, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                        }
                    }
                }
            }
            item {
                InsetGroup {
                    SwitchRow(Icons.Filled.Refresh, stringResource(R.string.cx_mirror), mirror, enabled = !downloading) { vm.mirror.value = it }
                    if (list.any { it.kind == "geoip" }) {
                        GroupDivider()
                        NavRow(Icons.Filled.LocationOn, stringResource(R.string.clash_geoip), Uri.parse(geoSource).host.orEmpty(), enabled = !downloading) { showGeo = !showGeo }
                    }
                }
            }
            if (showGeo) item { OutlinedTextField(geoSource, { vm.geoSource.value = it }, label = { Text(stringResource(R.string.clash_geoip_source)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { Text(stringResource(R.string.cx_resources), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp)) }
            item {
                InsetGroup {
                    list.forEachIndexed { i, r ->
                        if (i > 0) GroupDivider()
                        ResourceRow(r, Uri.parse(vm.downloadUrl(r)).host.orEmpty(), context)
                    }
                }
            }
        }
        if (failed > 0 && !viaProxy && list.any { it.error == "resource_dns" || it.error == "resource_connect" }) item { Hint(stringResource(R.string.cx_net_hint)) }
        if (error != null) item { ErrorLine(error!!) }
        if (s.overrides.isNotEmpty()) item { Hint(stringResource(R.string.cx_ignored, s.overrides.joinToString(", "))) }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            downloading -> OutlinedButton(onClick = vm::cancelDownloads, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cx_cancel)) }
            !allDone -> Button(onClick = vm::download, enabled = stopped, modifier = Modifier.weight(1f).height(48.dp)) {
                Text(if (failed > 0) stringResource(R.string.cx_retry_n, failed) else stringResource(R.string.cx_download_n, list.size - done))
            }
            else -> Button(onClick = vm::prepare, enabled = stopped && !busy, modifier = Modifier.weight(1f).height(48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.cx_continue))
            }
        }
    }
}

@Composable
private fun ResourceRow(r: ImportResource, host: String, context: Context) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (r.status) {
                ImportResource.Status.Running -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                ImportResource.Status.Done -> Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                ImportResource.Status.Failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                ImportResource.Status.Waiting -> Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(r.key, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            r.status == ImportResource.Status.Failed && r.error != null -> Text(errorLabel(r.error), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            r.status == ImportResource.Status.Done && r.bytes > 0 -> Text(com.nathanhanapps.wayd.feature.analysis.fmtBytes(context, r.bytes.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.status == ImportResource.Status.Done -> Text(stringResource(R.string.cx_installed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- Access control ----------------

/**
 * FlClash-style access control. Whitelist routes only the checked apps; blacklist routes every app except the checked
 * ones (including apps installed later). The consent line shown is the one recorded on save.
 */
@Composable
private fun AccessPage(vm: ClashExtensionViewModel, stopped: Boolean, onDone: () -> Unit) {
    val routing by vm.routing.state.collectAsStateWithLifecycle()
    val apps by vm.accessApps.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(routing.mode) }
    var chosen by rememberSaveable { mutableStateOf(routing.apps) }
    var query by rememberSaveable { mutableStateOf("") }
    var system by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadAccessApps() }
    val list = apps
    if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val shown = remember(list, query, system, chosen) {
        list.filter { (system || !it.system || it.seen || it.pkg in chosen) && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
    }
    val blacklist = mode == com.nathanhanapps.wayd.capture.RoutingMode.BLACKLIST
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                val modes = listOf(com.nathanhanapps.wayd.capture.RoutingMode.WHITELIST to R.string.cx_whitelist, com.nathanhanapps.wayd.capture.RoutingMode.BLACKLIST to R.string.cx_blacklist)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (m, label) ->
                        SegmentedButton(selected = mode == m, onClick = { if (mode != m) { mode = m; chosen = if (m == routing.mode) routing.apps else emptySet() } }, enabled = stopped && !busy, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(stringResource(label)) }
                    }
                }
            }
            item {
                Text(
                    stringResource(if (blacklist) R.string.cx_blacklist_hint else R.string.cx_whitelist_hint),
                    Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 4.dp), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text(stringResource(R.string.cx_search_apps)) })
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { system = !system }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cx_show_system), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(system, { system = it })
                }
            }
            items(shown, key = { it.pkg }) { app ->
                val on = app.pkg in chosen
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = stopped && !busy) { chosen = if (on) chosen - app.pkg else chosen + app.pkg }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon(vm.labels, app.pkg, size = 36)
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (app.seen) stringResource(R.string.cx_seen_in_traffic) else app.pkg,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Checkbox(on, { chosen = if (it) chosen + app.pkg else chosen - app.pkg }, enabled = stopped && !busy)
                }
            }
            item { Text(stringResource(if (blacklist) R.string.cx_blacklist_consent else R.string.routing_consent), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (error != null) item { ErrorLine(error!!) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (blacklist) R.string.cx_excluded_n else R.string.cx_selected_n, chosen.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Button(onClick = { vm.saveAccess(mode, chosen, onDone) }, enabled = stopped && !busy && (blacklist || chosen.isNotEmpty()), modifier = Modifier.height(48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.cx_save))
            }
        }
    }
}
