package com.nathanhanapps.wayd.feature.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Base64
import android.util.JsonWriter
import com.nathanhanapps.wayd.BuildConfig
import com.nathanhanapps.wayd.capture.pcap.PcapDroidCsv
import com.nathanhanapps.wayd.capture.pcap.PcapSynth
import com.nathanhanapps.wayd.capture.pcap.PcapWriter
import com.nathanhanapps.wayd.capture.pcap.SynthFlow
import com.nathanhanapps.wayd.capture.pcap.SynthPayload
import com.nathanhanapps.wayd.core.db.ApkScanDao
import com.nathanhanapps.wayd.core.db.DnsDao
import com.nathanhanapps.wayd.core.db.DnsRecord
import com.nathanhanapps.wayd.core.db.FindingDao
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.db.PermEventDao
import com.nathanhanapps.wayd.core.db.SessionDao
import com.nathanhanapps.wayd.core.model.Dir
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.feature.analysis.AppLabels
import com.nathanhanapps.wayd.feature.analysis.Windows
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ExportResult {
    /** [name] is the file the user chose; [flows] and [messages] are the record counts written. */
    data class Saved(val name: String, val flows: Int, val messages: Int) : ExportResult
    data object Empty : ExportResult
    data object Failed : ExportResult
}

/**
 * - [PCAP]: a reconstructed libpcap file (PCAPdroid/Wireshark-readable). WAYD keeps metadata, not packets, so endpoints, times and
 *   decrypted payload are real but the packets are rebuilt; flows without decrypted content carry handshake and close only.
 * - [PCAPDROID_CSV]: PCAPdroid's connections CSV (same 17 columns).
 * - [WAYD_JSON]: everything WAYD knows around the traffic, for analysis: flows, DNS, permission/access history, findings,
 *   per-app facts and (optionally) decrypted messages.
 */
enum class ExportFormat(val extension: String, val mime: String) {
    PCAP("pcap", "application/vnd.tcpdump.pcap"),
    PCAPDROID_CSV("csv", "text/csv"),
    WAYD_JSON("json", "application/json"),
}

enum class ExportRange { SESSION, TODAY, WEEK, ALL }

sealed interface ExportScope {
    data class Capture(val range: ExportRange) : ExportScope
    data class App(val pkg: String, val windowKey: String) : ExportScope
    data class Connection(val flowId: Long) : ExportScope
}

data class ExportRequest(val format: ExportFormat, val scope: ExportScope, val includeContent: Boolean = false)

/**
 * Writes exports to a document the user picked. Nothing is encrypted and nothing leaves the phone by itself: the UI warns, the user
 * chooses the place. Flows are read in keyset pages, so a long history does not have to fit in memory.
 */
@Singleton
class ExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionDao,
    private val flows: FlowDao,
    private val dns: DnsDao,
    private val messages: MessageDao,
    private val perms: PermEventDao,
    private val findings: FindingDao,
    private val apps: ApkScanDao,
    private val labels: AppLabels,
) {
    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Default file name offered in the system file picker; the user can change it. */
    fun fileName(r: ExportRequest): String {
        val what = when (val s = r.scope) {
            is ExportScope.Capture -> "capture"
            is ExportScope.App -> "app-" + s.pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")
            is ExportScope.Connection -> "connection-${s.flowId}"
        }
        return "wayd-$what-${stamp()}.${r.format.extension}"
    }

    private class Source(val label: String, val from: Long, val to: Long, val session: Long, val fixed: List<Flow>?)

    private suspend fun resolve(scope: ExportScope): Source? = when (scope) {
        is ExportScope.Capture -> when (scope.range) {
            ExportRange.SESSION -> sessions.latest()?.let { Source("session ${it.id}", it.startedAt, (it.endedAt ?: System.currentTimeMillis()) + 1, it.id, null) }
            ExportRange.TODAY -> Windows.resolve(Windows.TODAY).let { Source("today", it.first, it.second, -1, null) }
            ExportRange.WEEK -> Windows.resolve(Windows.WEEK).let { Source("last 7 days", it.first, it.second, -1, null) }
            ExportRange.ALL -> Source("everything", 0, Long.MAX_VALUE, -1, null)
        }
        is ExportScope.App -> Windows.resolve(scope.windowKey).let { (s, e) -> Source("${scope.pkg} ${scope.windowKey}", s, e, -1, flows.forApp(scope.pkg, s, e)) }
        is ExportScope.Connection -> flows.byId(scope.flowId)?.let { f -> Source("connection ${f.id}", f.startAt - 300_000, (f.endAt ?: f.startAt) + 300_000, -1, listOf(f)) }
    }

    /** Calls [block] with flows in time order, a page at a time. */
    private suspend fun forEachFlows(src: Source, block: suspend (List<Flow>) -> Unit) {
        src.fixed?.let { if (it.isNotEmpty()) block(it); return }
        var afterStart = Long.MIN_VALUE
        var afterId = Long.MIN_VALUE
        while (true) {
            val page = flows.exportChunk(afterStart, afterId, src.from, src.to, src.session, PAGE)
            if (page.isEmpty()) return
            block(page)
            afterStart = page.last().startAt; afterId = page.last().id
            if (page.size < PAGE) return
        }
    }

    suspend fun export(target: Uri, req: ExportRequest): ExportResult = withContext(Dispatchers.IO) {
        val src = resolve(req.scope) ?: return@withContext discard(target)
        var flowCount = 0
        var messageCount = 0
        val name = runCatching {
            writeDocument(target) { out ->
                when (req.format) {
                    ExportFormat.PCAPDROID_CSV -> flowCount = writeCsv(out, src)
                    ExportFormat.PCAP -> writePcap(out, src, req.includeContent).also { flowCount = it.first; messageCount = it.second }
                    ExportFormat.WAYD_JSON -> writeJson(out, src, req).also { flowCount = it.first; messageCount = it.second }
                }
            }
        }.getOrNull()
        when {
            name == null -> { runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }; ExportResult.Failed }
            flowCount == 0 && req.format != ExportFormat.WAYD_JSON -> discard(target)
            else -> ExportResult.Saved(name, flowCount, messageCount)
        }
    }

    // ---------------- PCAPdroid connections CSV ----------------

    private suspend fun writeCsv(out: OutputStream, src: Source): Int {
        var n = 0
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write(PcapDroidCsv.HEADER); w.write("\n")
        forEachFlows(src) { page ->
            for (f in page) {
                val tcp = f.proto == Proto.TCP
                w.write(
                    PcapDroidCsv.row(
                        tcp, if (':' in f.dstIp) "fd00:111:222::1" else "10.111.222.1", 0, f.dstIp, f.dstPort, f.uid, labels.label(f.pkg), f.pkg,
                        PcapDroidCsv.l7(tcp, f.dstPort, f.tlsVersion != null || f.hostSource == HostSource.SNI, f.hostSource == HostSource.HTTP_HOST),
                        when { f.policyOutcome == "BLOCKED" -> "Blocked"; f.endAt == null -> "Open"; else -> "Closed" },
                        f.host.orEmpty(), f.bytesUp, f.bytesDown, f.startAt, f.endAt ?: f.startAt,
                    ),
                )
                w.write("\n"); n++
            }
        }
        w.flush()
        return n
    }

    // ---------------- Reconstructed PCAP ----------------

    private suspend fun writePcap(out: OutputStream, src: Source, withContent: Boolean): Pair<Int, Int> {
        val buffered = out.buffered(256 * 1024)
        val writer = PcapWriter(buffered)
        val synth = PcapSynth(writer)
        var n = 0
        var m = 0
        forEachFlows(src) { page ->
            val byFlow = if (withContent) page.map { it.id }.chunked(SQL_IN).flatMap { messages.forFlowIds(it) }.groupBy { it.flowId } else emptyMap()
            for (f in page) {
                val payloads = byFlow[f.id].orEmpty().mapNotNull { msg -> httpBytes(msg)?.let { SynthPayload(msg.dir == Dir.UP, msg.ts.coerceIn(f.startAt, f.endAt ?: Long.MAX_VALUE), it) } }
                m += payloads.size
                synth.add(SynthFlow(f.id, f.proto == Proto.TCP, f.dstIp, f.dstPort, f.startAt, f.endAt, f.policyOutcome == "BLOCKED", f.bytesUp, f.bytesDown, payloads))
                n++
            }
        }
        synth.finish()
        writer.header() // a valid, empty capture is still a valid file
        buffered.flush()
        return n to m
    }

    /** HTTP/1.1 text of one decrypted message. Stored bodies are already decoded and may be truncated, so framing headers are rebuilt. */
    private fun httpBytes(m: Message): ByteArray? {
        val body = m.body ?: ByteArray(0)
        val dropped = setOf("content-encoding", "transfer-encoding", "content-length", "connection")
        val headers = m.headersJson.orEmpty().lineSequence().map { it.trim() }
            .filter { it.contains(':') && it.substringBefore(':').trim().lowercase() !in dropped }
            .joinToString("\r\n")
        val start = if (m.dir == Dir.UP) {
            val path = runCatching { Uri.parse(m.url.orEmpty()).let { u -> (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "") } }.getOrDefault("/")
            "${m.method ?: "GET"} $path HTTP/1.1"
        } else "HTTP/1.1 ${m.status ?: 200} "
        val head = buildString {
            append(start).append("\r\n")
            if (headers.isNotEmpty()) append(headers).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    // ---------------- WAYD detail JSON ----------------

    private class AppStats(var flows: Int = 0, var up: Long = 0, var down: Long = 0, var first: Long = Long.MAX_VALUE, var last: Long = 0)

    private suspend fun writeJson(out: OutputStream, src: Source, req: ExportRequest): Pair<Int, Int> {
        val w = JsonWriter(OutputStreamWriter(out, Charsets.UTF_8).buffered(256 * 1024)).apply { setIndent(" ") }
        val kind = when (req.scope) { is ExportScope.Capture -> "capture"; is ExportScope.App -> "app"; is ExportScope.Connection -> "connection" }
        w.beginObject()
        w.name("format").value("wayd-export").name("version").value(2L).name("kind").value(kind)
            .name("exportedAt").value(System.currentTimeMillis()).name("timeZone").value(TimeZone.getDefault().id)
        w.name("generator").beginObject().name("app").value("WAYD").name("version").value(BuildConfig.VERSION_NAME)
            .name("android").value(Build.VERSION.SDK_INT.toLong()).endObject()
        w.name("range").beginObject().name("label").value(src.label).name("from").value(src.from).name("to").value(if (src.to == Long.MAX_VALUE) null else src.to).endObject()
        w.name("includes").beginObject().name("decryptedContent").value(req.includeContent).endObject()
        w.name("notes").beginArray()
        listOf(
            "Local records exported by the user. Contains personal data; do not share with a service you do not trust.",
            "Times are epoch milliseconds. bytesUp = sent by the app, bytesDown = received.",
            "Evidence ids: f:<id> flows, p:<id> permission events, m:<id> decrypted messages; findings cite them in evidence.",
            "Findings are decided by WAYD's rule engine (not an AI). Coverage gaps are not proof of absence: see coverage.",
            "hostSource says how the hostname was learned (DNS, SNI, HTTP_HOST, QUIC_SNI); null host means it was not visible.",
            "permissionEvents come from the system's app-ops history (Shizuku/root), only for periods and apps where it was available.",
        ).forEach { w.value(it) }
        w.endArray()

        w.name("sessions").beginArray()
        sessions.all().filter { (src.session < 0 && it.startedAt < src.to && (it.endedAt ?: Long.MAX_VALUE) >= src.from) || it.id == src.session }.forEach {
            w.beginObject().name("id").value(it.id).name("startedAt").value(it.startedAt).name("endedAt").value(it.endedAt)
                .name("privilege").value(it.privilege.name).name("coverage").value(it.coverageJson).endObject()
        }
        w.endArray()

        val stats = LinkedHashMap<String, AppStats>()
        val uids = HashSet<Int>()
        val ids = ArrayList<Long>()
        var withPkg = 0
        var tcpWithHost = 0
        var tcp = 0
        w.name("flows").beginArray()
        forEachFlows(src) { page ->
            for (f in page) {
                ids += f.id; uids += f.uid
                if (f.pkg != "?") withPkg++
                if (f.proto == Proto.TCP) { tcp++; if (f.host != null) tcpWithHost++ }
                stats.getOrPut(f.pkg) { AppStats() }.also { it.flows++; it.up += f.bytesUp; it.down += f.bytesDown; it.first = minOf(it.first, f.startAt); it.last = maxOf(it.last, f.startAt) }
                flow(w, f)
            }
        }
        w.endArray()
        w.name("coverage").beginObject().name("flows").value(ids.size.toLong()).name("flowsWithApp").value(withPkg.toLong())
            .name("tcpFlows").value(tcp.toLong()).name("tcpFlowsWithHostname").value(tcpWithHost.toLong()).endObject()

        w.name("apps").beginArray()
        for ((pkg, s) in stats) {
            val info = apps.app(pkg)
            w.beginObject().name("pkg").value(pkg).name("label").value(labels.label(pkg)).name("system").bool(info?.isSystem)
                .name("versionName").value(info?.versionName).name("installer").value(info?.installer)
                .name("signerSha256").value(info?.signerSha256).name("firstSeen").value(info?.firstSeen).name("lastScanned").value(info?.lastScanned)
                .name("flows").value(s.flows.toLong()).name("bytesUp").value(s.up).name("bytesDown").value(s.down)
            w.name("apkFacts").beginArray()
            if (info != null) apps.facts(pkg).take(MAX_FACTS).forEach {
                w.beginObject().name("kind").value(it.kind.name).name("key").value(it.key).name("value").value(it.value).endObject()
            }
            w.endArray().endObject()
        }
        w.endArray()

        val dnsRows: List<DnsRecord> = when (val s = req.scope) {
            is ExportScope.Capture -> if (src.session >= 0) dns.forSession(src.session) else dns.between(src.from, src.to)
            else -> if (uids.isEmpty()) emptyList() else dns.forUids(uids.toList(), src.from, src.to)
        }
        w.name("dns").beginArray()
        dnsRows.forEach {
            w.beginObject().name("ts").value(it.ts).name("uid").value(it.uid.toLong()).name("qname").value(it.qname)
                .name("qtype").value(it.qtype.toLong()).name("answers").value(it.answersJson).name("viaDoh").value(it.viaDoh).endObject()
        }
        w.endArray()

        var messageCount = 0
        w.name("messages").beginArray()
        if (req.includeContent) ids.chunked(SQL_IN).forEach { chunk ->
            messages.forFlowIds(chunk).forEach { m -> message(w, m); messageCount++ }
        }
        w.endArray()

        val permRows = when (val s = req.scope) {
            is ExportScope.App -> perms.forApp(s.pkg, src.from, src.to)
            is ExportScope.Connection -> flows.byId(s.flowId)?.let { perms.forApp(it.pkg, src.from, src.to) }.orEmpty()
            is ExportScope.Capture -> perms.between(src.from, src.to)
        }
        w.name("permissionEvents").beginArray()
        permRows.forEach {
            w.beginObject().name("id").value(it.id).name("evidence").value("p:${it.id}").name("pkg").value(it.pkg).name("op").value(it.op).name("ts").value(it.ts)
                .name("bucketEnd").value(it.bucketEnd).name("count").value(it.count.toLong()).name("background").value(it.background).name("source").value(it.source).endObject()
        }
        w.endArray()

        val found = when (val s = req.scope) {
            is ExportScope.App -> findings.forApp(s.pkg, src.from, src.to)
            is ExportScope.Connection -> findings.between(src.from - DAY, src.to + DAY).filter { "\"f:${s.flowId}\"" in it.evidenceJson }
            is ExportScope.Capture -> findings.between(src.from, src.to)
        }
        w.name("findings").beginArray()
        found.forEach {
            w.beginObject().name("id").value(it.id).name("pkg").value(it.pkg).name("ruleId").value(it.ruleId).name("severity").value(it.severity.name)
                .name("title").value(it.title).name("dataTypes").value(it.dataTypes).name("evidence").value(it.evidenceJson).name("createdAt").value(it.createdAt).endObject()
        }
        w.endArray()
        w.endObject()
        w.flush()
        return ids.size to messageCount
    }

    private fun flow(w: JsonWriter, f: Flow) {
        w.beginObject().name("id").value(f.id).name("evidence").value("f:${f.id}").name("sessionId").value(f.sessionId)
            .name("pkg").value(f.pkg).name("uid").value(f.uid.toLong()).name("proto").value(f.proto.name).name("ipVersion").value(f.ipVersion.toLong())
            .name("dstIp").value(f.dstIp).name("dstPort").value(f.dstPort.toLong()).name("host").value(f.host).name("hostSource").value(f.hostSource?.name)
            .name("bytesUp").value(f.bytesUp).name("bytesDown").value(f.bytesDown).name("startAt").value(f.startAt).name("endAt").value(f.endAt)
            .name("screenOn").value(f.screenOn).name("appForeground").value(f.appForeground).name("netType").value(f.netType.name)
            .name("tlsVersion").value(f.tlsVersion).name("alpn").value(f.alpn).name("ja4").value(f.ja4).name("country").value(f.country).name("asn").value(f.asn)
            .name("company").value(f.company).name("category").value(f.category?.name).name("source").value(f.source.name)
            .name("policy").beginObject().name("outcome").value(f.policyOutcome).name("revision").value(f.policyRevision).name("ruleId").value(f.blockRuleId).endObject()
            .endObject()
    }

    /** Decrypted messages (research build only; empty otherwise). Bodies are UTF-8 text when valid, else base64. */
    private fun message(w: JsonWriter, m: Message) {
        w.beginObject().name("id").value(m.id).name("evidence").value("m:${m.id}").name("flowId").value(m.flowId).name("dir").value(m.dir.name).name("ts").value(m.ts)
            .name("method").value(m.method).name("url").value(m.url).name("status").value(m.status?.toLong())
            .name("headers").value(m.headersJson).name("contentType").value(m.contentType)
            .name("bodySize").value(m.bodySize.toLong()).name("bodyTruncated").value(m.bodyTruncated)
        m.body?.let { b ->
            val text = utf8OrNull(b)
            if (text != null) w.name("bodyText").value(text) else w.name("bodyBase64").value(Base64.encodeToString(b, Base64.NO_WRAP))
        }
        w.endObject()
    }

    private fun utf8OrNull(b: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    }.getOrNull()

    /** Nothing to export: remove the empty document the picker just created. */
    private fun discard(target: Uri): ExportResult {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
        return ExportResult.Empty
    }

    /** Runs [body] against the stream of the document the user picked; returns its display name. */
    private suspend fun writeDocument(target: Uri, body: suspend (OutputStream) -> Unit): String {
        val resolver = context.contentResolver
        requireNotNull(resolver.openOutputStream(target, "wt")) { "no stream" }.use { body(it) }
        return resolver.query(target, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "export"
    }

    private companion object {
        const val PAGE = 2000
        const val SQL_IN = 500
        const val MAX_FACTS = 400
        const val DAY = 86_400_000L
    }
}

private fun JsonWriter.bool(v: Boolean?): JsonWriter = if (v == null) nullValue() else value(v)
