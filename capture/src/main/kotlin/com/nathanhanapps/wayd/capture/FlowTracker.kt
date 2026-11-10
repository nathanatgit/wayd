package com.nathanhanapps.wayd.capture

import com.nathanhanapps.wayd.capture.parse.DnsParser
import com.nathanhanapps.wayd.capture.parse.HttpParser
import com.nathanhanapps.wayd.capture.parse.TlsSniParser
import com.nathanhanapps.wayd.core.db.DnsRecord
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.model.Dir
import com.nathanhanapps.wayd.core.model.Tier
import com.nathanhanapps.wayd.core.model.TierSource
import com.nathanhanapps.wayd.core.model.CaptureSource
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.NetType
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.enrich.TrackerDb
import java.net.InetAddress
import java.net.InetSocketAddress
import java.io.ByteArrayOutputStream

/** Who owns a connection, as resolved by the OS. */
class Owner(val uid: Int, val pkg: String, val packages: List<String> = listOf(pkg))

fun interface UidResolver {
    /** [proto] is the IP protocol number (6 or 17). Returns null if the owner cannot be determined. */
    fun resolve(proto: Int, src: InetSocketAddress, dst: InetSocketAddress): Owner?
}

interface CaptureSink {
    fun flow(f: Flow)

    /** A flow that carried decrypted messages; the sink assigns the stored flow id to each message. */
    fun flowWithMessages(f: Flow, messages: List<Message>) = flow(f)
    fun dns(d: DnsRecord)
}

/** Environment facts sampled per flow. Kept as an interface so [FlowTracker] stays pure-JVM testable. */
interface CaptureEnv {
    fun now(): Long
    fun screenOn(): Boolean
    fun netType(): NetType
}

/** Snapshot for the UI; cheap to compute. */
data class CaptureStats(val flows: Int, val apps: Int, val bytesUp: Long, val bytesDown: Long)

/**
 * Turns native flow events into [Flow] / [DnsRecord] rows. Not thread-safe: feed it from one thread.
 * Treats all payload as hostile: parsers are bounded and nothing is logged.
 */
class FlowTracker(
    private val sessionId: Long,
    private val resolver: UidResolver,
    private val sink: CaptureSink,
    private val env: CaptureEnv,
    private val dnsMap: DnsIpMap = DnsIpMap(),
    private val trackers: TrackerDb = TrackerDb(),
    private val gate: InterceptGate? = null,
    private val reply: (flowId: Long, intercept: Boolean) -> Unit = { _, _ -> },
    private val policyOwner: (Long, Owner?) -> Unit = { _, _ -> },
    private val policyHost: (Long, String) -> Unit = { _, _ -> },
    private val recording: () -> TrafficRecording.Window = { TrafficRecording.Window(true, 0) },
) {
    private class State(
        val open: NativeEvent.Open,
        var owner: Owner?,
        val startAt: Long,
        val screenOn: Boolean,
        val netType: NetType,
        val recordingWindow: TrafficRecording.Window,
    ) {
        var sni: String? = null
        var httpHost: String? = null
        var tlsVersion: String? = null
        var alpn: String? = null
        var prefix: ByteArrayOutputStream? = null
        var hostParsed = false
        var ownerAttempts = 1
        var dnsPrefix: ByteArrayOutputStream? = null
        var dnsDone = false
        var up = 0L
        var down = 0L
        var messages: ArrayList<Message>? = null
        var intercepted = false
        var pinned = false
        var policy: NativeEvent.Policy? = null
    }

    private val open = HashMap<Long, State>()
    private val apps = HashSet<String>()
    private var totalFlows = 0
    private var totalUp = 0L
    private var totalDown = 0L

    fun stats() = CaptureStats(totalFlows, apps.size, totalUp, totalDown)

    fun onEvent(e: NativeEvent) {
        when (e) {
            is NativeEvent.Open -> onOpen(e)
            is NativeEvent.Data -> onData(e)
            is NativeEvent.Message -> onMessage(e)
            is NativeEvent.Progress -> open[e.flowId]?.let { if (e.down) it.down = e.total else it.up = e.total }
            is NativeEvent.TlsNote -> open[e.flowId]?.let {
                if (e.kind == 1) it.intercepted = true
                if (e.kind == 2) it.pinned = true
            }
            is NativeEvent.Policy -> open[e.flowId]?.let { it.policy = e }
            is NativeEvent.Close -> open.remove(e.flowId)?.let { finish(it, e.bytesUp, e.bytesDown) }
        }
    }

    /** Flushes flows still open (session stop). */
    fun finishAll() {
        open.values.toList().forEach { finish(it, it.up, it.down) }
        open.clear()
    }

    private fun onOpen(e: NativeEvent.Open) {
        val owner = resolveOwner(e)
        // Enrichment must never take recording down: fall back to neutral values if the OS refuses.
        val screenOn = runCatching { env.screenOn() }.getOrDefault(false)
        val net = runCatching { env.netType() }.getOrDefault(NetType.OTHER)
        open[e.flowId] = State(e, owner, runCatching { env.now() }.getOrDefault(0L), screenOn, net, recording())
        policyOwner(e.flowId, owner)
        // The native side waits briefly for this answer; no answer means plain passthrough.
        val g = gate
        if (g != null && (e.proto == 6 || e.dstPort == 443)) {
            val allow = owner != null && runCatching { g.allow(owner.pkg, e.proto, e.dstPort) }.getOrDefault(false)
            runCatching { reply(e.flowId, allow) }
        }
    }

    private fun onMessage(e: NativeEvent.Message) {
        val s = open[e.flowId] ?: return
        if (!canRecord(s)) { s.messages = null; return }
        val list = s.messages ?: ArrayList<Message>().also { s.messages = it }
        if (list.size >= MAX_MESSAGES_PER_FLOW) return
        val ctype = e.headers.lineSequence()
            .firstOrNull { it.startsWith("content-type:", ignoreCase = true) }?.substringAfter(':')?.trim()
        list += Message(
            flowId = 0,
            dir = if (e.response) Dir.DOWN else Dir.UP,
            ts = runCatching { env.now() }.getOrDefault(0L),
            tier = Tier.DECRYPTED,
            tierSource = TierSource.MITM,
            method = e.method.ifEmpty { null },
            url = e.url,
            status = if (e.response) e.status else null,
            headersJson = e.headers,
            bodyBlobId = null,
            bodySize = e.bodyTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            contentType = ctype,
            body = e.body.takeIf { it.isNotEmpty() },
            bodyTruncated = e.truncated,
        )
    }

    private fun onData(e: NativeEvent.Data) {
        val s = open[e.flowId] ?: return
        if (!canRecord(s)) s.messages = null
        // Retry only while the socket is young and active, never after close/tuple reuse.
        if (s.owner == null && s.ownerAttempts < MAX_OWNER_ATTEMPTS &&
            runCatching { env.now() - s.startAt }.getOrDefault(-1L) in 0L..OWNER_RETRY_MS
        ) {
            s.ownerAttempts++
            s.owner = resolveOwner(s.open)
            policyOwner(e.flowId, s.owner)
        }
        when {
            s.open.proto == 17 && s.open.dstPort == 53 -> onDns(s, e)
            s.open.proto == 6 && s.open.dstPort == 53 && e.down -> onTcpDns(s, e.bytes)
            s.open.proto == 6 && !e.down && !s.hostParsed -> {
                // DATA events are stream chunks, not complete TLS records or HTTP heads.
                val prefix = s.prefix ?: ByteArrayOutputStream().also { s.prefix = it }
                prefix.write(e.bytes, 0, minOf(e.bytes.size, MAX_HOST_PREFIX - prefix.size()))
                val bytes = prefix.toByteArray()
                val hello = TlsSniParser.parse(bytes)
                val http = if (hello == null) HttpParser.parseRequest(bytes) else null
                if (hello != null) {
                    s.sni = hello.sni
                    s.alpn = hello.alpn.firstOrNull()
                    s.tlsVersion = hello.tlsVersion
                } else if (http != null) {
                    s.httpHost = http.host
                }
                if (hello != null || http != null || prefix.size() == MAX_HOST_PREFIX) {
                    s.hostParsed = true
                    s.prefix = null
                    policyHost(e.flowId, s.sni ?: s.httpHost?.let(BlockTargets::httpHost).orEmpty())
                }
            }
        }
    }

    private fun resolveOwner(e: NativeEvent.Open): Owner? = runCatching {
        resolver.resolve(e.proto, InetSocketAddress(e.srcIp, e.srcPort), InetSocketAddress(e.dstIp, e.dstPort))
    }.getOrNull()

    /** DNS over TCP is a stream of two-byte length-prefixed messages, not DATA-sized packets. */
    private fun onTcpDns(s: State, bytes: ByteArray) {
        if (s.dnsDone) return
        val prefix = s.dnsPrefix ?: ByteArrayOutputStream().also { s.dnsPrefix = it }
        prefix.write(bytes, 0, minOf(bytes.size, MAX_HOST_PREFIX - prefix.size()))
        val buffered = prefix.toByteArray()
        var p = 0
        while (p + 2 <= buffered.size) {
            val n = ((buffered[p].toInt() and 0xFF) shl 8) or (buffered[p + 1].toInt() and 0xFF)
            if (n < 12 || n > MAX_HOST_PREFIX - 2) {
                s.dnsDone = true
                break
            }
            if (p + 2 + n > buffered.size) break
            recordDns(s, buffered.copyOfRange(p + 2, p + 2 + n))
            p += 2 + n
        }
        if (prefix.size() == MAX_HOST_PREFIX) s.dnsDone = true
        if (s.dnsDone) {
            s.dnsPrefix = null
        } else if (p > 0) {
            prefix.reset()
            prefix.write(buffered, p, buffered.size - p)
        }
    }

    private fun onDns(s: State, e: NativeEvent.Data) {
        if (!e.down) return
        recordDns(s, e.bytes)
    }

    private fun recordDns(s: State, bytes: ByteArray) {
        val msg = DnsParser.parse(bytes) ?: return
        if (!msg.isResponse || msg.rcode != 0) return
        val q = msg.questions.firstOrNull() ?: return
        val addrs = msg.answers.filter { it.type == DnsParser.A || it.type == DnsParser.AAAA }
        addrs.forEach { dnsMap.put(it.data, q.name) }
        if (!canRecord(s)) return
        sink.dns(
            DnsRecord(
                sessionId = sessionId,
                uid = s.owner?.uid ?: -1,
                ts = env.now(),
                qname = q.name,
                qtype = q.type,
                answersJson = addrs.joinToString(prefix = "[", postfix = "]") { "\"${it.data}\"" },
                viaDoh = false,
            ),
        )
    }

    private fun finish(s: State, up: Long, down: Long) {
        val o = s.open
        val dstIp = o.dstIp.hostAddress ?: return
        val (host, source) = when {
            s.sni != null -> s.sni to HostSource.SNI
            s.httpHost != null -> s.httpHost to HostSource.HTTP_HOST
            else -> dnsMap.get(dstIp).let { if (it != null) it to HostSource.DNS else null to null }
        }
        val pkg = s.owner?.pkg ?: UNKNOWN_PKG
        val info = trackers.lookup(host)
        apps += pkg
        totalFlows++
        totalUp += up
        totalDown += down
        if (!canRecord(s)) return
        val flowRow =
            Flow(
                sessionId = sessionId,
                uid = s.owner?.uid ?: -1,
                pkg = pkg,
                userId = (s.owner?.uid ?: 0) / 100_000,
                proto = if (o.proto == 6) Proto.TCP else Proto.UDP,
                ipVersion = o.ipVersion,
                dstIp = dstIp,
                dstPort = o.dstPort,
                host = host,
                hostSource = source,
                bytesUp = up,
                bytesDown = down,
                pktsUp = 0, // packet counts are not tracked by the userspace relay
                pktsDown = 0,
                startAt = s.startAt,
                endAt = env.now(),
                screenOn = s.screenOn,
                appForeground = false, // unknown until the usage collector (M3) exists; never guessed
                netType = s.netType,
                tlsVersion = s.tlsVersion,
                alpn = s.alpn,
                ja4 = null,
                country = null,
                asn = null,
                company = info?.company,
                category = info?.category,
                source = CaptureSource.VPN,
                policyOutcome = s.policy?.let { when (it.kind) {
                    1 -> "BLOCKED"
                    2 -> "GATE_FAILED"
                    3 -> "UNKNOWN_OWNER"
                    4 -> "UNKNOWN_HOST"
                    else -> "PASSED_POLICY"
                } },
                policyRevision = s.policy?.revision,
                blockRuleId = s.policy?.ruleId?.takeIf { it != 0L },
            )
        val msgs = s.messages
        if (msgs.isNullOrEmpty()) sink.flow(flowRow) else sink.flowWithMessages(flowRow, msgs)
    }

    private fun canRecord(s: State): Boolean = s.recordingWindow.enabled && s.recordingWindow == recording()

    companion object {
        const val UNKNOWN_PKG = "?"
        private const val MAX_MESSAGES_PER_FLOW = 400
        private const val MAX_HOST_PREFIX = 16 * 1024
        private const val MAX_OWNER_ATTEMPTS = 3
        private const val OWNER_RETRY_MS = 1_000L
    }
}

/** Decides, per new flow, whether its TLS may be decrypted. Only the research flavor ever provides one. */
fun interface InterceptGate {
    fun allow(pkg: String, proto: Int, dstPort: Int): Boolean
}

/** Small LRU of IP -> most recent DNS name that resolved to it. */
class DnsIpMap(private val max: Int = 4096) {
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > max
    }

    fun put(ip: String, name: String) {
        map[ip] = name
    }

    fun get(ip: String): String? = map[ip]

    /** Normalises an address string the same way [InetAddress.getHostAddress] does. */
    fun get(addr: InetAddress): String? = addr.hostAddress?.let { map[it] }
}
