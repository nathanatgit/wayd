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
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.capture.*
import com.nathanhanapps.wayd.core.ui.WaydTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

@HiltViewModel
class ClashExtensionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val routing: RoutingSettings,
    val capture: CaptureState,
    val controller: CaptureController,
    val geoIp: RoutingGeoIp,
) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow(false)
    val profiles = MutableStateFlow<List<RoutingProfile>>(emptyList())
    val overview = MutableStateFlow<JSONObject?>(null)
    val selected = MutableStateFlow<RoutingProfile?>(null)
    val importReview = MutableStateFlow<JSONObject?>(null)
    val apps = MutableStateFlow<List<RoutingApp>?>(null)
    val geoUpdated = MutableStateFlow(0L)
    val downloadProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val downloadHost = MutableStateFlow("")
    private var actionJob: Job? = null
    private var downloadId: String? = null
    // Retry cache is memory-only: provider credentials never enter plaintext files.
    private val resourceCache = LinkedHashMap<String, ByteArray>()
    private var cacheSize = 0
    private var pending: ByteArray? = null
    private var pendingName = ""
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
        check(!result.has("error"))
        result
    }
    suspend fun show(profile: RoutingProfile) {
        val data = routing.profile(profile.id)
        overview.value = call(data, JSONObject().put("action", "inspect"))
        selected.value = profile
    }
    fun open(profile: RoutingProfile) = action { show(profile) }
    fun load(uri: Uri?) {
        if (uri == null) return
        action {
            pendingName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }?.take(128) ?: "Clash.yaml"
            pending = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { RoutingDownloads.readBounded(it, 4 * 1024 * 1024) } }
            resourceCache.clear(); cacheSize = 0
            pendingId = null
            importReview.value = call(requireNotNull(pending), JSONObject().put("action", "inspect"))
        }
    }
    fun subscription(name: String, url: String) = action {
        pendingName = name.trim().take(128).ifEmpty { "Clash.yaml" }
        pending = RoutingDownloads.fetch(url, 4 * 1024 * 1024)
        resourceCache.clear(); cacheSize = 0
        pendingId = null
        importReview.value = call(requireNotNull(pending), JSONObject().put("action", "inspect"))
    }
    fun cancelDownload() {
        actionJob?.cancel()
        val id = downloadId
        if (id != null) viewModelScope.launch(Dispatchers.IO) {
            native.nativeRoutingCommand(byteArrayOf(1), JSONObject().put("action", "cancel-download").put("id", id).toString().toByteArray())
        }
    }
    fun cancelImport() {
        if (busy.value && importReview.value != null) { cancelDownload(); return }
        pending = null; pendingId = null; importReview.value = null; apps.value = null
        resourceCache.clear(); cacheSize = 0; downloadProgress.value = null
    }
    fun downloadAndPrepare(viaProxy: Boolean = false, geoSource: String? = null, mirror: Boolean = false) = action {
        withTimeout(240_000) {
            val bytes = requireNotNull(pending)
            val requests = requireNotNull(importReview.value).getJSONArray("resources")
            val resources = JSONObject()
            var total = 0
            for (index in 0 until requests.length()) {
                currentCoroutineContext().ensureActive()
                val request = requests.getJSONObject(index)
                val source = if (request.getString("kind") == "geoip" && !geoSource.isNullOrBlank()) geoSource.trim() else request.getString("url")
                val url = RoutingResourceUrls.forDownload(source, mirror)
                downloadProgress.value = (index + 1) to requests.length()
                downloadHost.value = Uri.parse(url).host.orEmpty().take(128)
                suspend fun fetch(limit: Int): ByteArray {
                    resourceCache[url]?.let { return it }
                    val id = java.util.UUID.randomUUID().toString()
                    downloadId = id
                    val content = try {
                        Base64.decode(call(bytes, JSONObject().put("action", "download").put("id", id).put("url", url).put("viaProxy", viaProxy).put("limit", limit)).getString("body"), Base64.NO_WRAP)
                    } finally { downloadId = null }
                    currentCoroutineContext().ensureActive()
                    if (content.size <= 28 * 1024 * 1024 - cacheSize) {
                        resourceCache[url] = content; cacheSize += content.size
                    }
                    return content
                }
                if (request.getString("kind") == "geoip") {
                    geoIp.load()
                    if (geoIp.status().optLong("updated") == 0L || geoIp.status().optString("source") != url) geoIp.install(fetch(32 * 1024 * 1024), url)
                } else {
                    val content = fetch(8 * 1024 * 1024)
                    total += content.size
                    require(total <= 28 * 1024 * 1024)
                    resources.put(request.getString("key"), Base64.encodeToString(content, Base64.NO_WRAP))
                }
            }
            downloadProgress.value = null
            pending = Base64.decode(call(bytes, JSONObject().put("action", "prepare").put("resources", resources)).getString("bundle"), Base64.NO_WRAP)
            importReview.value = null
            apps.value = routing.apps()
            geoUpdated.value = geoIp.status().optLong("updated")
        }
    }
    fun activate(profile: RoutingProfile) = action {
        pending = routing.profile(profile.id); pendingName = profile.name; pendingId = profile.id
        apps.value = routing.apps()
    }
    fun approve(packages: Set<String>) = action {
        val bytes = requireNotNull(pending)
        val id = routing.archive(pendingName, bytes, pendingId)
        routing.save(bytes, packages, id)
        cancelImport()
        profiles.value = routing.profiles()
        profiles.value.firstOrNull { it.id == id }?.let { show(it) }
    }
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
    fun updateGeoIp(url: String) = action {
        geoIp.download(url.trim())
        geoUpdated.value = geoIp.status().optLong("updated")
    }
    private fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = false
        actionJob = viewModelScope.launch { try { block() } catch (_: TimeoutCancellationException) { error.value = true } catch (e: CancellationException) { throw e } catch (_: Exception) { error.value = true } finally { busy.value = false } }
    }
}

private enum class ClashPage(val label: Int) {
    Dashboard(R.string.clash_dashboard), Profiles(R.string.clash_profiles), Proxies(R.string.clash_proxies),
    Connections(R.string.clash_connections), Rules(R.string.clash_rules), Events(R.string.clash_events), Settings(R.string.clash_settings),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClashExtensionScreen(onBack: () -> Unit, vm: ClashExtensionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by vm.capture.ui.collectAsStateWithLifecycle()
    val routing by vm.routing.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val progress by vm.downloadProgress.collectAsStateWithLifecycle()
    val downloadHost by vm.downloadHost.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val review by vm.importReview.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val geoUpdated by vm.geoUpdated.collectAsStateWithLifecycle()
    val connections by vm.capture.connections.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var subscription by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RoutingProfile?>(null) }
    var geoSource by rememberSaveable { mutableStateOf("https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/country.mmdb") }
    var viaProxy by rememberSaveable { mutableStateOf(false) }
    var mirror by rememberSaveable { mutableStateOf(false) }
    var chosen by remember(apps) { mutableStateOf(emptySet<String>()) }
    val stopped = state.phase in setOf(CapturePhase.IDLE, CapturePhase.ERROR)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::load)
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == android.app.Activity.RESULT_OK) vm.controller.start() }
    val start: () -> Unit = { val intent = VpnService.prepare(context); if (intent != null) vpn.launch(intent) else vm.controller.start() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.clash_back)) }
                Text(stringResource(R.string.clash_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = vm::refresh, enabled = !busy) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.clash_refresh)) }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            ScrollableTabRow(selectedTabIndex = page, edgePadding = 8.dp) {
                ClashPage.entries.forEachIndexed { index, tab -> Tab(selected = page == index, onClick = { page = index }, text = { Text(stringResource(tab.label)) }) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) Text(stringResource(R.string.clash_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (ClashPage.entries[page]) {
                    ClashPage.Dashboard -> {
                        item {
                            val running = state.phase == CapturePhase.RUNNING
                            val dot = when (state.phase) { CapturePhase.RUNNING -> MaterialTheme.colorScheme.primary; CapturePhase.ERROR -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline }
                            ExtensionCard {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
                                    Text(stringResource(when (state.phase) { CapturePhase.RUNNING -> R.string.dashboard_running; CapturePhase.STARTING -> R.string.record_status_starting; CapturePhase.ERROR -> R.string.record_status_error; else -> R.string.dashboard_idle }), style = MaterialTheme.typography.titleLarge)
                                }
                                selected?.let { Text(it.name + " · " + (overview?.optString("mode").orEmpty()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Button(modifier = Modifier.fillMaxWidth(), enabled = state.phase != CapturePhase.STARTING, onClick = { if (!stopped) vm.controller.stop() else if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else start() }) { Text(stringResource(if (stopped) R.string.dashboard_start else R.string.dashboard_stop)) }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.routing_enable), style = MaterialTheme.typography.titleSmall)
                                        Text(stringResource(R.string.routing_apps_count, routing.apps.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(routing.enabled, vm::enable, enabled = stopped && routing.available && !busy)
                                }
                                if (running) Text(stringResource(R.string.clash_traffic, state.stats.bytesUp, state.stats.bytesDown), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item { ExtensionCard {
                            Text(stringResource(R.string.clash_outcomes_title), style = MaterialTheme.typography.titleMedium)
                            RouteOutcomes(state.routes.direct.toLong(), state.routes.proxied.toLong(), state.routes.blocked.toLong(), state.routes.errors.toLong())
                            Text(stringResource(R.string.clash_boundary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    }
                    ClashPage.Profiles -> {
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(enabled = stopped && !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.routing_import)) }; OutlinedButton(enabled = stopped && !busy, onClick = { subscription = true }) { Text(stringResource(R.string.clash_subscription)) } } }
                        items(profiles, key = { it.id }) { profile -> ExtensionCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(profile.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (profile.selected) AssistChip(onClick = {}, label = { Text(stringResource(R.string.clash_active)) })
                            }
                            Text(DateFormat.getDateTimeInstance().format(Date(profile.updated)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { vm.open(profile); page = ClashPage.Proxies.ordinal }) { Text(stringResource(R.string.clash_open)) }
                                TextButton(enabled = stopped && !busy, onClick = { vm.activate(profile) }) { Text(stringResource(R.string.clash_use)) }
                                TextButton(enabled = stopped && !busy, onClick = { deleting = profile }) { Text(stringResource(R.string.routing_delete), color = MaterialTheme.colorScheme.error) }
                            }
                        } }
                        if (profiles.isEmpty()) item { Text(stringResource(R.string.clash_import_hint), style = MaterialTheme.typography.bodyMedium) }
                    }
                    ClashPage.Proxies -> {
                        item { Text(selected?.name ?: stringResource(R.string.clash_import_hint), style = MaterialTheme.typography.titleMedium) }
                        val groups = overview?.optJSONArray("groups")
                        if (groups != null) items(groups.length()) { index ->
                            val group = groups.getJSONObject(index)
                            ExtensionCard {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(group.getString("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    AssistChip(onClick = {}, label = { Text(group.getString("type")) })
                                }
                                val members = group.optJSONArray("members")
                                if (members != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (i in 0 until members.length()) {
                                        val name = members.getString(i)
                                        FilterChip(selected = name == group.optString("selected"), enabled = stopped && !busy && group.optString("type") == "select", onClick = { vm.configure(group.getString("name"), name) }, label = { Text(name) })
                                    }
                                }
                            }
                        }
                        val proxies = overview?.optJSONArray("proxies")
                        if (proxies != null) item { ExtensionCard {
                            Text(stringResource(R.string.clash_nodes, proxies.length()), style = MaterialTheme.typography.titleMedium)
                            for (index in 0 until proxies.length()) {
                                val proxy = proxies.getJSONObject(index)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(proxy.optString("name"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(proxy.optString("type"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } }
                        item { Text(stringResource(R.string.routing_stop_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    ClashPage.Connections -> {
                        item { Text(stringResource(R.string.clash_live_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(connections, key = { it.id }) { connection -> ExtensionCard {
                            Text("${connection.destination}:${connection.port}", style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.clash_connection_bytes, connection.bytesUp, connection.bytesDown), style = MaterialTheme.typography.bodySmall)
                        } }
                        if (connections.isEmpty()) item { Text(stringResource(R.string.clash_no_connections)) }
                    }
                    ClashPage.Rules -> {
                        item { Text(stringResource(R.string.clash_rules_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val rules = overview?.optJSONArray("rules")
                        if (rules != null) item { ExtensionCard {
                            for (i in 0 until rules.length()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("${i + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                Text(rules.getString(i), style = MaterialTheme.typography.bodyMedium)
                            }
                        } }
                    }
                    ClashPage.Events -> {
                        item { Text(stringResource(R.string.clash_events_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        item { ExtensionCard { RouteOutcomes(state.routes.direct.toLong(), state.routes.proxied.toLong(), state.routes.blocked.toLong(), state.routes.errors.toLong()) } }
                    }
                    ClashPage.Settings -> {
                        item { ExtensionCard {
                            Text(stringResource(R.string.clash_mode), style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("rule" to R.string.clash_rule_mode, "global" to R.string.clash_global_mode, "direct" to R.string.clash_direct_mode).forEach { (mode, label) -> FilterChip(selected = overview?.optString("mode") == mode, enabled = stopped && !busy && selected != null, onClick = { vm.configure(mode = mode) }, label = { Text(stringResource(label)) }) }
                            }
                        } }
                        item { ExtensionCard {
                            Text(stringResource(R.string.clash_geoip), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.clash_geoip_shared), style = MaterialTheme.typography.bodySmall)
                            Text(if (geoUpdated == 0L) stringResource(R.string.clash_geoip_missing) else DateFormat.getDateTimeInstance().format(Date(geoUpdated)), style = MaterialTheme.typography.labelLarge)
                            OutlinedTextField(geoSource, { geoSource = it }, label = { Text(stringResource(R.string.clash_geoip_source)) }, modifier = Modifier.fillMaxWidth())
                            TextButton(enabled = stopped && !busy, onClick = { vm.updateGeoIp(geoSource) }) { Text(stringResource(R.string.clash_download_resources)) }
                        } }
                        item { Text(stringResource(R.string.clash_boundary), style = MaterialTheme.typography.bodySmall); Text(stringResource(R.string.clash_dns_override), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
    if (subscription) {
        var name by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { subscription = false }, title = { Text(stringResource(R.string.clash_subscription)) }, text = { Column {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.clash_profile_name)) })
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.clash_subscription_url)) })
        } }, confirmButton = { TextButton(enabled = url.startsWith("https://"), onClick = { subscription = false; vm.subscription(name, url) }) { Text(stringResource(R.string.clash_download)) } }, dismissButton = { TextButton(onClick = { subscription = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
    review?.let { value -> AlertDialog(onDismissRequest = vm::cancelImport, title = { Text(stringResource(R.string.clash_import_review)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            progress?.let { Text(stringResource(R.string.clash_download_progress, it.first, it.second, downloadHost)) }
        }
        if (error) Text(stringResource(R.string.clash_download_retry), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.clash_resource_count, value.getJSONArray("resources").length()))
        Text(stringResource(R.string.clash_dns_override))
        Text(value.getJSONArray("overrides").join(", "))
        Text(stringResource(R.string.clash_download_notice))
        val resources = value.getJSONArray("resources")
        for (index in 0 until resources.length()) Text(Uri.parse(RoutingResourceUrls.forDownload(resources.getJSONObject(index).getString("url"), mirror)).host.orEmpty())
        Row { Checkbox(mirror, { mirror = it }, enabled = !busy); Text(stringResource(R.string.clash_download_mirror)) }
        Row { Checkbox(viaProxy, { viaProxy = it }, enabled = !busy); Text(stringResource(R.string.clash_download_proxy)) }
        if ((0 until resources.length()).any { resources.getJSONObject(it).optString("kind") == "geoip" }) OutlinedTextField(geoSource, { geoSource = it }, label = { Text(stringResource(R.string.clash_geoip_source)) })
    } }, confirmButton = { TextButton(enabled = !busy && stopped, onClick = { vm.downloadAndPrepare(viaProxy, geoSource, mirror) }) { Text(stringResource(R.string.clash_download_resources)) } }, dismissButton = { TextButton(onClick = vm::cancelImport) { Text(stringResource(android.R.string.cancel)) } }) }
    apps?.let { available -> AlertDialog(onDismissRequest = { if (!busy) vm.cancelImport() }, title = { Text(stringResource(R.string.routing_review)) }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.routing_consent))
        available.forEach { app -> Row { Checkbox(app.pkg in chosen, { checked -> chosen = if (checked) chosen + app.pkg else chosen - app.pkg }, enabled = !busy); Text(app.label) } }
    } }, confirmButton = { TextButton(enabled = !busy && chosen.isNotEmpty() && stopped, onClick = { vm.approve(chosen) }) { Text(stringResource(R.string.routing_save)) } }, dismissButton = { TextButton(enabled = !busy, onClick = vm::cancelImport) { Text(stringResource(android.R.string.cancel)) } }) }
    deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.routing_delete)) }, text = { Text(profile.name) }, confirmButton = { TextButton(onClick = { vm.remove(profile); deleting = null }) { Text(stringResource(R.string.routing_delete)) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } }) }
}

@Composable
private fun ExtensionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

/** Direct / proxied / blocked / error counts as one proportional bar with a labelled legend (counts are transport attempts). */
@Composable
private fun RouteOutcomes(direct: Long, proxied: Long, blocked: Long, errors: Long) {
    val items = listOf(
        Triple(R.string.clash_out_direct, direct, MaterialTheme.colorScheme.outline),
        Triple(R.string.clash_out_proxied, proxied, MaterialTheme.colorScheme.primary),
        Triple(R.string.clash_out_blocked, blocked, MaterialTheme.colorScheme.tertiary),
        Triple(R.string.clash_out_errors, errors, MaterialTheme.colorScheme.error),
    )
    val total = items.sumOf { it.second }
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (total > 0) items.forEach { (_, n, color) -> if (n > 0) Box(Modifier.weight(n.toFloat()).height(12.dp).background(color)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (label, n, color) ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                }
                Text(n.toString(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
