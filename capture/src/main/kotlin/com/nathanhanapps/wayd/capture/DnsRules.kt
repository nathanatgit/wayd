package com.nathanhanapps.wayd.capture

import android.content.Context
import com.nathanhanapps.wayd.core.db.ConsentRecord
import com.nathanhanapps.wayd.core.db.WaydDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads DNS rule files into the native line format (D-072). Accepts the two formats Daedalus loads, in one file or mixed:
 *
 * - **hosts:** `<ip> <name> [<name>…]` answers each name exactly (Daedalus reads only the first name; aliases are accepted).
 * - **dnsmasq:** `address=/<domain>[/<domain>…]/<ip>` answers the domains and their subdomains; an empty value or `#` blocks;
 *   `local=/<domain>/` blocks. A leading `.` on a domain is ignored, as in Daedalus.
 *
 * `0.0.0.0` and `::` mean block (local `0.0.0.0` / `::` for A/AAAA and an empty answer otherwise), the same as a block list.
 * Skipped and counted: `server=` and other dnsmasq options (they need an upstream or a feature WAYD does not have), the
 * all-domains `address=/#/`, localhost entries, single-label names and malformed lines.
 */
object DnsRuleParser {
    data class Result(val lines: List<String>, val applied: Int, val skipped: Int)

    const val MAX_ENTRIES = 1_000_000
    private val LOCAL_NAMES = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback",
        "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "ip6-allhosts")

    fun parse(text: Sequence<String>): Result {
        val out = ArrayList<String>()
        var skipped = 0
        for (raw in text) {
            val line = raw.substringBefore(" #").substringBefore("\t#").trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith('!')) continue
            val parsed = when {
                line.startsWith("address=") -> dnsmasq(line.removePrefix("address="), local = false)
                line.startsWith("local=") -> dnsmasq(line.removePrefix("local="), local = true)
                line.contains('=') -> null // other dnsmasq options, e.g. server=/…/ip (needs an upstream)
                else -> hosts(line)
            }
            if (parsed.isNullOrEmpty() || out.size + parsed.size > MAX_ENTRIES) skipped++ else out += parsed
        }
        return Result(out, out.size, skipped)
    }

    /** Native line for one rule; null when the host or value is not valid. [subdomains] selects `.` over `=`. */
    fun line(host: String, subdomains: Boolean, value: String): String? {
        val h = name(host) ?: return null
        val flag = if (subdomains) "." else "="
        val v = value.trim()
        if (v.isEmpty() || v == "#") return "$flag\t$h\tBLOCK"
        val ip = BlockTargets.ip(v) ?: return null
        return when (ip) {
            "0.0.0.0", "::", "0:0:0:0:0:0:0:0" -> "$flag\t$h\tBLOCK"
            else -> if (':' in ip) "$flag\t$h\tAAAA\t$ip" else "$flag\t$h\tA\t$ip"
        }
    }

    private fun name(value: String): String? =
        BlockTargets.host(value.trim().removePrefix("."))?.takeIf { '.' in it && it !in LOCAL_NAMES }

    private fun dnsmasq(body: String, local: Boolean): List<String>? {
        if (!body.startsWith('/')) return null
        val parts = body.removePrefix("/").split('/')
        if (parts.size < 2) return null
        val domains = parts.dropLast(1)
        if (local && parts.last().isNotEmpty()) return null
        val value = if (local) "" else parts.last()
        if (domains.isEmpty() || domains.any { it == "#" || it.isBlank() }) return null
        return domains.map { line(it, subdomains = true, value) ?: return null }
    }

    private fun hosts(line: String): List<String>? {
        val f = line.split(Regex("\\s+"))
        if (f.size < 2 || BlockTargets.ip(f[0]) == null) return null
        return f.drop(1).mapNotNull { n -> line(n, subdomains = false, f[0]) }.takeIf { it.isNotEmpty() }
    }
}

/** A rule file the user imported. */
data class DnsRuleSource(val id: String, val name: String, val enabled: Boolean, val applied: Int, val skipped: Int, val updatedAt: Long)

/** A rule the user typed: [value] is an IPv4/IPv6 address, or empty to block. */
data class DnsRule(val id: Long, val host: String, val subdomains: Boolean, val value: String, val enabled: Boolean)

data class DnsRuleState(
    val sources: List<DnsRuleSource> = emptyList(),
    val rules: List<DnsRule> = emptyList(),
    /** Record types answered with an empty answer for every name, e.g. 65 (HTTPS), 64 (SVCB), 28 (AAAA). */
    val blockedTypes: Set<Int> = emptySet(),
)

/**
 * DNS rules (D-072), stored like block lists: each imported file compiled once into the native format under no-backup
 * storage, the enabled parts concatenated into `active.dns` for the engine. Like a block list, an enabled source applies to
 * every app WAYD covers, so it records one revocable consent; typed rules and record-type blocks share one consent.
 */
@Singleton
class DnsRuleStore @Inject constructor(@ApplicationContext context: Context, private val db: WaydDatabase) {
    private val dir = File(context.noBackupFilesDir, "dnsrules").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val active = File(dir, "active.dns")
    private val mutex = Mutex()
    private val _state = MutableStateFlow(read())
    val state = _state.asStateFlow()

    private fun read(): DnsRuleState = runCatching {
        val o = JSONObject(index.readText())
        val s = o.getJSONArray("sources")
        val r = o.getJSONArray("rules")
        val t = o.getJSONArray("types")
        DnsRuleState(
            (0 until s.length()).map { s.getJSONObject(it) }.map {
                DnsRuleSource(it.getString("id"), it.getString("name"), it.getBoolean("enabled"), it.getInt("applied"), it.getInt("skipped"), it.getLong("updatedAt"))
            },
            (0 until r.length()).map { r.getJSONObject(it) }.map {
                DnsRule(it.getLong("id"), it.getString("host"), it.getBoolean("subdomains"), it.getString("value"), it.getBoolean("enabled"))
            }.filter { DnsRuleParser.line(it.host, it.subdomains, it.value) != null },
            (0 until t.length()).map { t.getInt(it) }.filter { it in ALLOWED_TYPES }.toSet(),
        )
    }.getOrDefault(DnsRuleState())

    private fun write(state: DnsRuleState) {
        val o = JSONObject()
        o.put("sources", JSONArray().apply { state.sources.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("enabled", it.enabled).put("applied", it.applied).put("skipped", it.skipped).put("updatedAt", it.updatedAt)) } })
        o.put("rules", JSONArray().apply { state.rules.forEach { put(JSONObject().put("id", it.id).put("host", it.host).put("subdomains", it.subdomains).put("value", it.value).put("enabled", it.enabled)) } })
        o.put("types", JSONArray().apply { state.blockedTypes.sorted().forEach { put(it) } })
        index.writeText(o.toString())
        rebuild(state)
        _state.value = state
    }

    private fun file(id: String) = File(dir, "$id.dns")

    /** Typed rules and type blocks first, so with duplicate names a typed rule's addresses come before a file's. */
    private fun rebuild(state: DnsRuleState) {
        val local = state.blockedTypes.sorted().map { "T\t$it" } +
            state.rules.filter { it.enabled }.mapNotNull { DnsRuleParser.line(it.host, it.subdomains, it.value) }
        val files = state.sources.filter { it.enabled && file(it.id).isFile }
        if (local.isEmpty() && files.isEmpty()) { active.delete(); return }
        val tmp = File(dir, "active.tmp")
        tmp.outputStream().buffered().use { out ->
            local.forEach { out.write(it.toByteArray()); out.write('\n'.code) }
            files.forEach { s -> file(s.id).inputStream().use { it.copyTo(out) } }
        }
        check(tmp.renameTo(active) || run { active.delete(); tmp.renameTo(active) })
    }

    /** Path for the engine, or "" when nothing is enabled. */
    fun activePath(): String = if (active.isFile) active.absolutePath else ""

    /** Compiles and stores a rule file. New sources start disabled until the user enables them. Null when nothing applies or full. */
    suspend fun import(name: String, text: Sequence<String>): DnsRuleSource? = withContext(Dispatchers.IO) { mutex.withLock {
        val parsed = DnsRuleParser.parse(text)
        val s = _state.value
        if (parsed.applied == 0 || s.sources.size >= MAX_SOURCES) return@withLock null
        val info = DnsRuleSource(java.util.UUID.randomUUID().toString().take(8), name.trim().take(60).ifBlank { "rules" }, false, parsed.applied, parsed.skipped, System.currentTimeMillis())
        file(info.id).bufferedWriter().use { w -> parsed.lines.forEach { w.write(it); w.write("\n") } }
        write(s.copy(sources = s.sources + info))
        info
    } }

    suspend fun setSourceEnabled(id: String, on: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        consent(feature(id), on)
        val s = _state.value
        write(s.copy(sources = s.sources.map { if (it.id == id) it.copy(enabled = on) else it }))
    } }

    suspend fun deleteSource(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        consent(feature(id), false)
        file(id).delete()
        val s = _state.value
        write(s.copy(sources = s.sources.filterNot { it.id == id }))
    } }

    /** Adds a typed rule (enabled). Null when the host or address is not valid or the list is full. */
    suspend fun addRule(host: String, subdomains: Boolean, value: String): DnsRule? = withContext(Dispatchers.IO) { mutex.withLock {
        if (DnsRuleParser.line(host, subdomains, value) == null) return@withLock null
        val s = _state.value
        if (s.rules.size >= MAX_RULES) return@withLock null
        val ip = value.trim().takeIf { it.isNotEmpty() && it != "#" }?.let { BlockTargets.ip(it) }.orEmpty()
        val rule = DnsRule((s.rules.maxOfOrNull { it.id } ?: 0L) + 1, BlockTargets.host(host.trim().removePrefix("."))!!, subdomains, ip, true)
        commitLocal(s.copy(rules = s.rules + rule))
        rule
    } }

    suspend fun setRuleEnabled(id: Long, on: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val s = _state.value
        commitLocal(s.copy(rules = s.rules.map { if (it.id == id) it.copy(enabled = on) else it }))
    } }

    suspend fun deleteRule(id: Long) = withContext(Dispatchers.IO) { mutex.withLock {
        val s = _state.value
        commitLocal(s.copy(rules = s.rules.filterNot { it.id == id }))
    } }

    suspend fun setTypeBlocked(types: Set<Int>, on: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val s = _state.value
        val t = types.filter { it in ALLOWED_TYPES }
        commitLocal(s.copy(blockedTypes = if (on) s.blockedTypes + t else s.blockedTypes - t.toSet()))
    } }

    /** Typed rules and type blocks share one consent: held while any of them is on. */
    private suspend fun commitLocal(next: DnsRuleState) {
        consent(LOCAL_FEATURE, next.rules.any { it.enabled } || next.blockedTypes.isNotEmpty())
        write(next)
    }

    private suspend fun consent(feature: String, on: Boolean) {
        val current = db.blockRuleDao().consent(feature)
        if (on) { if (current == null || current.revokedAt != null) db.consentDao().upsert(ConsentRecord(feature, STATEMENT, System.currentTimeMillis(), null)) }
        else if (current != null && current.revokedAt == null) db.consentDao().upsert(current.copy(revokedAt = System.currentTimeMillis()))
    }

    private fun feature(id: String) = "dnsrule-v1:$id"

    companion object {
        const val MAX_SOURCES = 20
        const val MAX_RULES = 500
        const val STATEMENT = 1
        private const val LOCAL_FEATURE = "dnsrule-v1:local"
        /** Types the UI offers: AAAA, SVCB, HTTPS. */
        val ALLOWED_TYPES = setOf(28, 64, 65)
    }
}
