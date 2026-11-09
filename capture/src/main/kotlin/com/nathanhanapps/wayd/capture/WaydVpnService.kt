package com.nathanhanapps.wayd.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.nathanhanapps.wayd.core.db.DnsDao
import com.nathanhanapps.wayd.core.db.DnsRecord
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.db.Session
import com.nathanhanapps.wayd.core.db.SessionDao
import com.nathanhanapps.wayd.core.model.Privilege
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * The recording VPN. Routes all traffic into the Rust netstack, which relays it to the real network.
 * The app excludes itself from the VPN so the relay's own sockets bypass the tunnel (no `protect()` needed).
 */
@AndroidEntryPoint
class WaydVpnService : VpnService() {
    @Inject lateinit var state: CaptureState
    @Inject lateinit var sessions: SessionDao
    @Inject lateinit var flows: FlowDao
    @Inject lateinit var dns: DnsDao
    @Inject lateinit var messageDao: MessageDao
    @Inject lateinit var interceptHook: InterceptHook

    private var pfd: ParcelFileDescriptor? = null
    private var native: NativeNet? = null
    private var handle = 0L
    private var worker: Thread? = null
    @Volatile private var running = false
    private var sessionId = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopCapture() // the user turned the VPN off in system settings
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent?) {
        if (running) return
        state.reset(CapturePhase.STARTING)
        promote(0, 0)

        val tun = Builder()
            .setSession("WAYD")
            .setMtu(MTU)
            .addAddress("10.111.222.1", 24)
            .addRoute("0.0.0.0", 0)
            .apply {
                // Only offer IPv6 when the real network has it. Otherwise dual-stack apps try IPv6 first, our
                // stack accepts the connection, the relay then fails, and the app never falls back to IPv4.
                if (underlyingHasIpv6()) {
                    addAddress("fd00:111:222::1", 64)
                    addRoute("::", 0)
                }
            }
            .apply { runCatching { addDisallowedApplication(packageName) } }
            .apply { applyDebugExclusions(this, intent) }
            .establish()
        if (tun == null) {
            fail()
            return
        }
        val net = NativeNet()
        val h = runCatching { net.nativeStart(tun.fd, MTU) }.getOrDefault(0L)
        if (h == 0L) {
            tun.close()
            fail()
            return
        }
        pfd = tun
        native = net
        handle = h
        sessionId = runBlocking { sessions.insert(Session(startedAt = System.currentTimeMillis(), endedAt = null, privilege = Privilege.NONE, coverageJson = "{}")) }
        running = true
        state.reset(CapturePhase.RUNNING)

        worker = Thread({ pollLoop(net, h) }, "wayd-capture").also { it.start() }
    }

    /** True if the network the phone's traffic currently uses (Wi-Fi preferred, else any non-VPN) has an IPv6 default route. */
    private fun underlyingHasIpv6(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
        val candidates = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) ->
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        val chosen = candidates.firstOrNull { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: candidates.firstOrNull()
        val lp = chosen?.let { cm.getLinkProperties(it.first) }
        lp != null && lp.routes.any { it.isDefaultRoute && it.destination.address is java.net.Inet6Address }
    }.getOrDefault(false)

    /**
     * Debug builds only: `--es exclude 192.168.1.5/32,...` keeps those prefixes out of the tunnel. Needed so a
     * wireless-adb session is not reset by the VPN capturing its established connection. Ignored in release builds.
     */
    private fun applyDebugExclusions(b: Builder, intent: Intent?) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        intent?.getStringExtra(EXTRA_EXCLUDE)?.split(',')?.forEach { cidr ->
            runCatching {
                val (addr, len) = cidr.trim().split('/')
                b.excludeRoute(IpPrefix(java.net.InetAddress.getByName(addr), len.toInt()))
            }
        }
    }

    private inner class BatchSink : CaptureSink {
        val flowBuf = ArrayList<Flow>()
        val dnsBuf = ArrayList<DnsRecord>()
        val withMessages = ArrayList<Pair<Flow, List<Message>>>()
        override fun flow(f: Flow) { flowBuf += f }
        override fun flowWithMessages(f: Flow, messages: List<Message>) { withMessages += f to messages }
        override fun dns(d: DnsRecord) { dnsBuf += d }

        fun flush() {
            if (flowBuf.isEmpty() && dnsBuf.isEmpty() && withMessages.isEmpty()) return
            val f = ArrayList(flowBuf)
            val d = ArrayList(dnsBuf)
            val m = ArrayList(withMessages)
            flowBuf.clear()
            dnsBuf.clear()
            withMessages.clear()
            runBlocking {
                if (f.isNotEmpty()) flows.insertAll(f)
                for ((flow, msgs) in m) {
                    val id = flows.insert(flow)
                    messageDao.insertAll(msgs.map { it.copy(flowId = id) })
                }
                if (d.isNotEmpty()) dns.insertAll(d)
            }
        }
    }

    private fun pollLoop(net: NativeNet, h: Long) {
        val sink = BatchSink()
        // Research flavor only: switch TLS interception on if the user consented and chose apps.
        val provider = interceptHook.provider
        val mitm = runCatching { provider?.config() }.getOrNull()
        val active = mitm != null && runCatching {
            net.nativeConfigureMitm(h, mitm.certPem, mitm.keyPem, mitm.excludedHosts.joinToString(","))
        }.getOrDefault(false)
        val t = FlowTracker(
            sessionId, AndroidUidResolver(this), sink, AndroidCaptureEnv(this),
            gate = if (active) provider?.gate() else null,
            reply = { id, allow -> net.nativeDecide(h, id, allow) },
        )
        val buf = ByteArray(POLL_BUF)
        var lastFlush = SystemClock.elapsedRealtime()
        var lastNotify = 0L
        try {
            while (running) {
                val n = net.nativePoll(h, buf, 250)
                if (n < 0) break
                if (n > 0) {
                    for (ev in NativeEvents.decode(buf, n)) {
                        // One bad flow must not stop recording; nothing about the flow is logged.
                        runCatching { t.onEvent(ev) }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastFlush >= FLUSH_MS) {
                    sink.flush()
                    state.set(t.stats())
                    lastFlush = now
                    if (now - lastNotify >= NOTIFY_MS) {
                        promote(t.stats().flows, t.stats().apps)
                        lastNotify = now
                    }
                }
            }
        } finally {
            t.finishAll()
            sink.flush()
            state.set(t.stats())
        }
    }

    private fun stopCapture() {
        val wasRunning = running
        running = false
        worker?.join(2_000)
        worker = null
        if (handle != 0L) {
            native?.nativeStop(handle)
            handle = 0L
        }
        native = null
        pfd?.close()
        pfd = null
        if (wasRunning && sessionId != 0L) {
            runBlocking { sessions.end(sessionId, System.currentTimeMillis()) }
            sessionId = 0L
        }
        state.set(CapturePhase.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail() {
        state.set(CapturePhase.ERROR)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** (Re)posts the foreground notification. Falls back gracefully if the OS refuses the service type. */
    private fun promote(flowCount: Int, appCount: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WaydVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notif_title))
            .setContentText(getString(R.string.capture_notif_text, flowCount, appCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.capture_notif_stop), stop)
            .build()
        runCatching {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        }.onFailure {
            runCatching { nm.notify(NOTIF_ID, n) }
        }
    }

    companion object {
        const val ACTION_START = "com.nathanhanapps.wayd.capture.START"
        const val ACTION_STOP = "com.nathanhanapps.wayd.capture.STOP"
        const val EXTRA_EXCLUDE = "exclude"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val MTU = 1500
        private const val POLL_BUF = 512 * 1024
        private const val FLUSH_MS = 500L
        private const val NOTIFY_MS = 2_000L
    }
}
