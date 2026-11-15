package com.nathanhanapps.wayd.feature.export

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import com.nathanhanapps.wayd.core.db.DnsDao
import com.nathanhanapps.wayd.core.db.DnsRecord
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.db.PermEventDao
import com.nathanhanapps.wayd.core.db.Session
import com.nathanhanapps.wayd.core.db.SessionDao
import com.nathanhanapps.wayd.core.db.WaydDatabase
import androidx.room.withTransaction
import android.widget.Toast
import com.nathanhanapps.wayd.R
import com.nathanhanapps.wayd.core.model.CaptureSource
import com.nathanhanapps.wayd.core.model.Dir
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.NetType
import com.nathanhanapps.wayd.core.model.Privilege
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.core.model.Tier
import com.nathanhanapps.wayd.core.model.TierSource
import com.nathanhanapps.wayd.core.model.TrackerCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportResult {
    /** [complete] is false when the file ended early (an interrupted export): everything before that point was imported. */
    data class Done(val flows: Int, val skipped: Int, val messages: Int, val dns: Int, val permissions: Int, val complete: Boolean = true) : ImportResult
    /** Not a WAYD detail JSON (wrong format or version). */
    data object NotWayd : ImportResult
    data object Failed : ImportResult
}

/**
 * Merges a WAYD detail JSON (the export, format `wayd-export` version 2) into the normal history. Row ids in the file belong to the
 * exporting database, so they are never reused: sessions are matched by start time (or created), connections get new ids, and
 * messages follow their connection's new id. A connection that is already stored (same app, destination, protocol and start time)
 * is skipped together with its messages, so importing the same file twice adds nothing. Findings are not imported: the rule engine
 * decides them from the merged history. Nothing is marked as "imported"; the rows are ordinary history.
 */
@Singleton
class ImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: WaydDatabase,
    private val sessions: SessionDao,
    private val flows: FlowDao,
    private val dns: DnsDao,
    private val messages: MessageDao,
    private val perms: PermEventDao,
) {
    private class Counts { var flows = 0; var skipped = 0; var messages = 0; var dns = 0; var perms = 0 }

    /** Runs in this service's own scope (leaving the screen used to cancel a half-done import) and reports with a toast. */
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun start(source: Uri) {
        if (_running.value) return
        _running.value = true
        jobs.launch {
            val r = try { import(source) } finally { _running.value = false }
            val msg = when (r) {
                is ImportResult.Done -> if (r.complete) context.getString(R.string.import_done, r.flows, r.skipped, r.messages)
                    else context.getString(R.string.import_partial, r.flows, r.messages)
                ImportResult.NotWayd -> context.getString(R.string.import_not_wayd)
                ImportResult.Failed -> context.getString(R.string.import_failed)
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
        }
    }

    suspend fun import(source: Uri): ImportResult = withContext(Dispatchers.IO) {
        val c = Counts()
        runCatching {
            val input = context.contentResolver.openInputStream(source) ?: return@runCatching ImportResult.Failed
            JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { r -> read(r, c) }
        }.getOrElse {
            when {
                it is NotWaydFile -> ImportResult.NotWayd
                it is kotlinx.coroutines.CancellationException -> throw it
                // A cut-off export (the process died mid-write): keep what was read, and say the rest is missing.
                c.flows + c.skipped + c.messages + c.dns + c.perms > 0 -> ImportResult.Done(c.flows, c.skipped, c.messages, c.dns, c.perms, complete = false)
                else -> ImportResult.Failed
            }
        }
    }

    private class NotWaydFile : Exception()

    private companion object { const val FLOW_BATCH = 500 }

    private suspend fun read(r: JsonReader, c: Counts): ImportResult {
        val sessionMap = HashMap<Long, Long>()   // exported session id -> id in this database
        val flowMap = HashMap<Long, Long>()      // exported flow id -> new flow id (only flows inserted now)
        var fallbackSession: Long? = null
        var format: String? = null
        var version = 0L
        if (r.peek() != JsonToken.BEGIN_OBJECT) throw NotWaydFile()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = r.str()
                "version" -> version = r.nextLong()
                "sessions" -> {
                    if (format != "wayd-export" || version != 2L) throw NotWaydFile()
                    sessionsArray(r, sessionMap)
                }
                "flows" -> {
                    if (format != "wayd-export" || version != 2L) throw NotWaydFile()
                    // One transaction per FLOW_BATCH rows: one per row made a 14k-flow file take minutes (the 2026-11-15 import
                    // stopped after 5,381). Rows parsed before an early end of file are still inserted by the finally block.
                    suspend fun insertFlow(o: Obj) {
                        val oldId = o.long("id") ?: return
                        val startAt = o.long("startAt") ?: return
                        val pkg = o.string("pkg") ?: return
                        val dstIp = o.string("dstIp") ?: return
                        val port = (o.long("dstPort") ?: 0L).toInt()
                        val proto = o.enum<Proto>("proto") ?: return
                        if (flows.findSame(startAt, pkg, dstIp, port, proto) != null) { c.skipped++; return }
                        val sid = sessionMap[o.long("sessionId")] ?: (fallbackSession ?: sessions.insert(
                            Session(startedAt = startAt, endedAt = startAt, privilege = Privilege.NONE, coverageJson = "{}"),
                        ).also { fallbackSession = it })
                        val uid = (o.long("uid") ?: 0L).toInt()
                        @Suppress("UNCHECKED_CAST") val policy = o.map["policy"] as? Map<String, Any?>
                        val id = flows.insert(
                            Flow(
                                sessionId = sid, uid = uid, pkg = pkg, userId = uid / 100_000, proto = proto,
                                ipVersion = (o.long("ipVersion") ?: if (':' in dstIp) 6L else 4L).toInt(), dstIp = dstIp, dstPort = port,
                                host = o.string("host"), hostSource = o.enum<HostSource>("hostSource"),
                                bytesUp = o.long("bytesUp") ?: 0, bytesDown = o.long("bytesDown") ?: 0, pktsUp = 0, pktsDown = 0,
                                startAt = startAt, endAt = o.long("endAt"),
                                screenOn = o.bool("screenOn") ?: false, appForeground = o.bool("appForeground") ?: false,
                                netType = o.enum<NetType>("netType") ?: NetType.OTHER,
                                tlsVersion = o.string("tlsVersion"), alpn = o.string("alpn"), ja4 = o.string("ja4"),
                                country = o.string("country"), asn = o.string("asn"), company = o.string("company"),
                                category = o.enum<TrackerCategory>("category"), source = o.enum<CaptureSource>("source") ?: CaptureSource.VPN,
                                policyOutcome = policy?.get("outcome") as? String,
                                policyRevision = (policy?.get("revision") as? Number)?.toLong(),
                                blockRuleId = null, // rule ids belong to the exporting phone
                            ),
                        )
                        flowMap[oldId] = id
                        c.flows++
                    }
                    val pending = ArrayList<Obj>(FLOW_BATCH)
                    suspend fun flush() {
                        if (pending.isEmpty()) return
                        db.withTransaction { pending.forEach { insertFlow(it) } }
                        pending.clear()
                    }
                    r.beginArray()
                    try {
                        while (r.hasNext()) {
                            pending += readObject(r)
                            if (pending.size >= FLOW_BATCH) flush()
                        }
                    } finally { flush() }
                    r.endArray()
                }
                "dns" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        val o = readObject(r)
                        val ts = o.long("ts") ?: continue
                        val q = o.string("qname") ?: continue
                        val uid = (o.long("uid") ?: 0L).toInt()
                        val qtype = (o.long("qtype") ?: 0L).toInt()
                        if (dns.countSame(ts, q, uid, qtype) > 0) continue
                        val sid = sessionMap.values.firstOrNull() ?: fallbackSession ?: sessions.insert(
                            Session(startedAt = ts, endedAt = ts, privilege = Privilege.NONE, coverageJson = "{}"),
                        ).also { fallbackSession = it }
                        dns.insertAll(listOf(DnsRecord(sessionId = sid, uid = uid, ts = ts, qname = q, qtype = qtype, answersJson = o.string("answers") ?: "[]", viaDoh = o.bool("viaDoh") ?: false)))
                        c.dns++
                    }
                    r.endArray()
                }
                "messages" -> {
                    r.beginArray()
                    val batch = ArrayList<Message>()
                    while (r.hasNext()) {
                        val o = readObject(r)
                        val flowId = flowMap[o.long("flowId")] ?: continue // its connection was already stored (or is missing)
                        val body = o.string("bodyText")?.toByteArray(Charsets.UTF_8) ?: o.string("bodyBase64")?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
                        batch += Message(
                            flowId = flowId, dir = o.enum<Dir>("dir") ?: Dir.UP, ts = o.long("ts") ?: 0L,
                            tier = Tier.DECRYPTED, tierSource = TierSource.MITM, method = o.string("method"), url = o.string("url"),
                            status = o.long("status")?.toInt(), headersJson = o.string("headers"), bodyBlobId = null,
                            bodySize = (o.long("bodySize") ?: (body?.size ?: 0).toLong()).toInt(), contentType = o.string("contentType"),
                            body = body, bodyTruncated = o.bool("bodyTruncated") ?: false,
                        )
                        if (batch.size >= 50) { messages.insertAll(batch); c.messages += batch.size; batch.clear() }
                    }
                    if (batch.isNotEmpty()) { messages.insertAll(batch); c.messages += batch.size }
                    r.endArray()
                }
                "permissionEvents" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        val o = readObject(r)
                        val pkg = o.string("pkg") ?: continue
                        val op = o.string("op") ?: continue
                        val ts = o.long("ts") ?: continue
                        val source = o.string("source") ?: ""
                        if (perms.countSame(pkg, op, ts, source) > 0) continue
                        perms.insertAll(listOf(PermEvent(pkg = pkg, op = op, ts = ts, durationMs = null, background = o.bool("background") ?: false, source = source,
                            count = (o.long("count") ?: 1L).toInt(), bucketEnd = o.long("bucketEnd") ?: 0L)))
                        c.perms++
                    }
                    r.endArray()
                }
                else -> r.skipValue() // apps, coverage, findings, notes, range, includes, generator ...
            }
        }
        r.endObject()
        if (format != "wayd-export" || version != 2L) throw NotWaydFile()
        return ImportResult.Done(c.flows, c.skipped, c.messages, c.dns, c.perms)
    }

    private suspend fun sessionsArray(r: JsonReader, map: HashMap<Long, Long>) {
        val existing = sessions.all().associateBy { it.startedAt }
        r.beginArray()
        while (r.hasNext()) {
            val o = readObject(r)
            val oldId = o.long("id") ?: continue
            val start = o.long("startedAt") ?: continue
            map[oldId] = existing[start]?.id ?: sessions.insert(
                Session(startedAt = start, endedAt = o.long("endedAt"), privilege = o.enum<Privilege>("privilege") ?: Privilege.NONE, coverageJson = o.string("coverage") ?: "{}"),
            )
        }
        r.endArray()
    }

    // ---- tiny JSON object model (one record at a time; bodies are the largest values) ----

    private class Obj(val map: Map<String, Any?>) {
        fun string(k: String) = map[k] as? String
        fun long(k: String) = (map[k] as? Number)?.toLong()
        fun bool(k: String) = map[k] as? Boolean
        inline fun <reified E : Enum<E>> enum(k: String): E? = string(k)?.let { s -> enumValues<E>().firstOrNull { it.name == s } }
    }

    private fun readObject(r: JsonReader): Obj = Obj(readMap(r))

    private fun readMap(r: JsonReader): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        r.beginObject()
        while (r.hasNext()) m[r.nextName()] = readValue(r)
        r.endObject()
        return m
    }

    private fun readValue(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.STRING -> r.nextString()
        JsonToken.NUMBER -> r.nextString().let { it.toLongOrNull() ?: it.toDoubleOrNull() }
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.NULL -> { r.nextNull(); null }
        JsonToken.BEGIN_OBJECT -> readMap(r)
        else -> { r.skipValue(); null }
    }

    private fun JsonReader.str(): String? = if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
}
