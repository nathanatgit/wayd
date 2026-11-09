package com.nathanhanapps.wayd.feature.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.JsonWriter
import com.nathanhanapps.wayd.core.db.DnsDao
import com.nathanhanapps.wayd.core.db.DnsRecord
import com.nathanhanapps.wayd.core.db.FindingDao
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.db.PermEventDao
import com.nathanhanapps.wayd.core.db.SessionDao
import com.nathanhanapps.wayd.feature.analysis.Windows
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ExportResult {
    /** [name] is the file in Downloads/WAYD; [flows] and [messages] are the record counts written. */
    data class Saved(val name: String, val flows: Int, val messages: Int) : ExportResult
    data object Empty : ExportResult
    data object Failed : ExportResult
}

/**
 * Exports the raw recorded data as one JSON file in Downloads/WAYD: a whole capture (one recording session) or one app
 * within an analysis window. The file is NOT encrypted; the UI asks the user to confirm before writing it.
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
) {
    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Default file name offered in the system file picker; the user can change it. */
    fun captureFileName() = "wayd-capture-${stamp()}.json"

    fun appFileName(pkg: String) = "wayd-app-${pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")}-${stamp()}.json"

    /** The most recent recording session with everything recorded in it, written to the document the user chose. */
    suspend fun exportLatestCapture(target: Uri): ExportResult = withContext(Dispatchers.IO) {
        val session = sessions.latest() ?: return@withContext discard(target)
        val f = flows.forSession(session.id)
        if (f.isEmpty()) return@withContext discard(target)
        val d = dns.forSession(session.id)
        val m = messages.forSession(session.id)
        write(target) { w ->
            header(w, "capture")
            w.name("session").beginObject()
                .name("id").value(session.id).name("startedAt").value(session.startedAt)
                .name("endedAt").value(session.endedAt).name("privilege").value(session.privilege.name)
                .endObject()
            flowArray(w, f)
            dnsArray(w, d)
            messageArray(w, m)
        }?.let { ExportResult.Saved(it, f.size, m.size) } ?: ExportResult.Failed
    }

    /** Everything recorded for [pkg] in the analysis window [windowKey], written to the document the user chose. */
    suspend fun exportApp(pkg: String, windowKey: String, target: Uri): ExportResult = withContext(Dispatchers.IO) {
        val (start, end) = Windows.resolve(windowKey)
        val f = flows.forApp(pkg, start, end)
        val p = perms.forApp(pkg, start, end)
        if (f.isEmpty() && p.isEmpty()) return@withContext discard(target)
        val d = dns.forUids(f.map { it.uid }.distinct().ifEmpty { listOf(-2) }, start, end)
        val m = messages.forApp(pkg, start, end)
        val found = findings.forApp(pkg, start, end)
        write(target) { w ->
            header(w, "app")
            w.name("app").beginObject().name("pkg").value(pkg).name("window").value(windowKey)
                .name("from").value(start).name("to").value(end).endObject()
            flowArray(w, f)
            dnsArray(w, d)
            messageArray(w, m)
            w.name("permissionEvents").beginArray()
            p.forEach {
                w.beginObject().name("id").value(it.id).name("op").value(it.op).name("ts").value(it.ts)
                    .name("bucketEnd").value(it.bucketEnd).name("count").value(it.count.toLong())
                    .name("background").value(it.background).name("source").value(it.source).endObject()
            }
            w.endArray()
            w.name("findings").beginArray()
            found.forEach {
                w.beginObject().name("id").value(it.id).name("ruleId").value(it.ruleId).name("severity").value(it.severity.name)
                    .name("title").value(it.title).name("dataTypes").value(it.dataTypes)
                    .name("evidence").value(it.evidenceJson).name("createdAt").value(it.createdAt).endObject()
            }
            w.endArray()
        }?.let { ExportResult.Saved(it, f.size, m.size) } ?: ExportResult.Failed
    }

    private fun header(w: JsonWriter, kind: String) {
        w.name("format").value("wayd-export").name("version").value(1L).name("kind").value(kind)
            .name("exportedAt").value(System.currentTimeMillis())
            .name("note").value("Raw local records. Contains personal data; times are epoch milliseconds.")
    }

    private fun flowArray(w: JsonWriter, list: List<Flow>) {
        w.name("flows").beginArray()
        list.forEach { f ->
            w.beginObject()
                .name("id").value(f.id).name("pkg").value(f.pkg).name("uid").value(f.uid.toLong())
                .name("proto").value(f.proto.name).name("ipVersion").value(f.ipVersion.toLong())
                .name("dstIp").value(f.dstIp).name("dstPort").value(f.dstPort.toLong())
                .name("host").value(f.host).name("hostSource").value(f.hostSource?.name)
                .name("bytesUp").value(f.bytesUp).name("bytesDown").value(f.bytesDown)
                .name("startAt").value(f.startAt).name("endAt").value(f.endAt)
                .name("screenOn").value(f.screenOn).name("netType").value(f.netType.name)
                .name("tlsVersion").value(f.tlsVersion).name("alpn").value(f.alpn)
                .name("company").value(f.company).name("category").value(f.category?.name)
                .endObject()
        }
        w.endArray()
    }

    private fun dnsArray(w: JsonWriter, list: List<DnsRecord>) {
        w.name("dns").beginArray()
        list.forEach {
            w.beginObject().name("ts").value(it.ts).name("uid").value(it.uid.toLong()).name("qname").value(it.qname)
                .name("qtype").value(it.qtype.toLong()).name("answers").value(it.answersJson).endObject()
        }
        w.endArray()
    }

    /** Decrypted messages (research build only; empty otherwise). Bodies are UTF-8 text when valid, else base64. */
    private fun messageArray(w: JsonWriter, list: List<Message>) {
        w.name("messages").beginArray()
        list.forEach { m ->
            w.beginObject().name("flowId").value(m.flowId).name("dir").value(m.dir.name).name("ts").value(m.ts)
                .name("method").value(m.method).name("url").value(m.url).name("status").value(m.status?.toLong())
                .name("headers").value(m.headersJson).name("contentType").value(m.contentType)
                .name("bodySize").value(m.bodySize.toLong()).name("bodyTruncated").value(m.bodyTruncated)
            m.body?.let { b ->
                val text = utf8OrNull(b)
                if (text != null) w.name("bodyText").value(text) else w.name("bodyBase64").value(Base64.encodeToString(b, Base64.NO_WRAP))
            }
            w.endObject()
        }
        w.endArray()
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

    /** Writes the JSON into [target] (a document the user picked). Returns the file's display name, or null on failure. */
    private fun write(target: Uri, body: (JsonWriter) -> Unit): String? = runCatching {
        val resolver = context.contentResolver
        try {
            resolver.openOutputStream(target, "wt")?.use { out ->
                JsonWriter(OutputStreamWriter(out, Charsets.UTF_8).buffered()).use { w ->
                    w.setIndent(" ")
                    w.beginObject()
                    body(w)
                    w.endObject()
                }
            } ?: error("no stream")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw t
        }
        resolver.query(target, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "export.json"
    }.getOrNull()
}
