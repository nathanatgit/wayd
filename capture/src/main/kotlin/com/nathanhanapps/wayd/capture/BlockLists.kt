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
 * Adblock / hosts subset a VPN can honour without page context (D-064). Output lines are the native list format
 * (`hostlist.rs`): `=` exact, `.` host and subdomains, `@` exception, `!` important, optional `\tpkg|pkg` app scope.
 * Everything else (paths, regex, cosmetic rules, content-type and third-party modifiers) is counted as skipped, never guessed.
 */
object AdblockParser {
    data class Result(val lines: List<String>, val applied: Int, val skipped: Int)

    const val MAX_ENTRIES = 1_000_000
    private val LOCAL = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")
    private val HOSTS_IP = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0")
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    private fun host(value: String): String? = BlockTargets.host(value.trim())?.takeIf { '.' in it }

    fun parse(text: Sequence<String>): Result {
        val out = LinkedHashSet<String>()
        var skipped = 0
        fun add(line: String?) { if (line == null || out.size >= MAX_ENTRIES) skipped++ else out += line }
        for (raw in text) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[' || (line[0] == '#' && !line.startsWith("##"))) continue
            when {
                "##" in line || "#@#" in line || "#?#" in line || "#$#" in line -> skipped++ // cosmetic rules need a page
                line.startsWith("@@||") -> add(rule(line.substring(4), exception = true))
                line.startsWith("||") -> add(rule(line.substring(2), exception = false))
                line[0] == '/' || line[0] == '|' || line.startsWith("@@") -> skipped++ // regex, anchored URLs, other exceptions
                else -> {
                    // hosts file ("0.0.0.0 a.example b.example # note") or a plain domain per line.
                    val parts = line.substringBefore('#').trim().split(Regex("\\s+"))
                    when {
                        parts.size >= 2 && parts[0] in HOSTS_IP -> parts.drop(1).filter { it !in LOCAL && it !in HOSTS_IP }.forEach { h -> add(host(h)?.let { "=\t$it" }) }
                        parts.size == 1 && '$' !in parts[0] && '/' !in parts[0] && '*' !in parts[0] -> add(host(parts[0])?.let { ".\t$it" })
                        else -> skipped++
                    }
                }
            }
        }
        return Result(out.toList(), out.size, skipped)
    }

    /** `host^` plus optional `$important` / `$app=a|b`. Any other modifier needs request context a VPN does not have. */
    private fun rule(body: String, exception: Boolean): String? {
        val pattern = body.substringBefore('$')
        val options = body.substringAfter('$', "").split(',').filter { it.isNotBlank() }
        if (!pattern.endsWith("^") || '*' in pattern || '/' in pattern) return null
        val h = host(pattern.dropLast(1)) ?: return null
        var important = false
        var apps: List<String>? = null
        for (o in options) when {
            o == "important" -> important = true
            o.startsWith("app=") -> apps = o.removePrefix("app=").split('|').also { list -> if (list.isEmpty() || list.size > 64 || list.any { !PACKAGE.matches(it) }) return null }
            else -> return null
        }
        return when {
            exception -> if (options.isEmpty()) "@\t$h" else null
            apps != null -> ".\t$h\t${apps.joinToString("|")}"
            important -> "!\t$h"
            else -> ".\t$h"
        }
    }
}

/** Rule id the engine reports for a connection blocked by a list (`hostlist.rs` LIST_RULE). */
const val LIST_RULE_ID = -1_000_000_000_000_000L

data class BlockListInfo(val id: String, val name: String, val url: String?, val enabled: Boolean, val applied: Int, val skipped: Int, val updatedAt: Long)

/**
 * Imported block lists (D-064). Compiled once at import into the native format, one file per list under no-backup storage; the
 * enabled lists are concatenated into `active.list`, which the engine loads when the VPN starts or a list changes. Enabling a
 * list records one revocable global consent for it (AGENTS.md rule 9 exception): it applies to every app WAYD covers.
 */
@Singleton
class BlockListStore @Inject constructor(@ApplicationContext context: Context, private val db: WaydDatabase) {
    private val dir = File(context.noBackupFilesDir, "blocklists").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val active = File(dir, "active.list")
    private val mutex = Mutex()
    private val _lists = MutableStateFlow(read())
    val lists = _lists.asStateFlow()

    private fun read(): List<BlockListInfo> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            BlockListInfo(it.getString("id"), it.getString("name"), it.optString("url").ifEmpty { null }, it.getBoolean("enabled"), it.getInt("applied"), it.getInt("skipped"), it.getLong("updatedAt"))
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<BlockListInfo>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url.orEmpty()).put("enabled", it.enabled).put("applied", it.applied).put("skipped", it.skipped).put("updatedAt", it.updatedAt)) }
        index.writeText(a.toString())
        rebuild(list)
        _lists.value = list
    }

    private fun file(id: String) = File(dir, "$id.list")

    private fun rebuild(list: List<BlockListInfo>) {
        val enabled = list.filter { it.enabled && file(it.id).isFile }
        if (enabled.isEmpty()) { active.delete(); return }
        val tmp = File(dir, "active.tmp")
        tmp.outputStream().buffered().use { out -> enabled.forEach { l -> file(l.id).inputStream().use { it.copyTo(out) }; out.write('\n'.code) } }
        check(tmp.renameTo(active) || run { active.delete(); tmp.renameTo(active) })
    }

    /** Path for the engine, or "" when no list is enabled. */
    fun activePath(): String = if (active.isFile && _lists.value.any { it.enabled }) active.absolutePath else ""

    /** Compiles and stores a list. [id] replaces an existing list (update); new lists start disabled until the user enables them. */
    suspend fun import(name: String, text: Sequence<String>, url: String? = null, id: String? = null): BlockListInfo? = withContext(Dispatchers.IO) { mutex.withLock {
        val parsed = AdblockParser.parse(text)
        if (parsed.applied == 0) return@withLock null
        val existing = _lists.value.firstOrNull { it.id == id }
        if (existing == null && _lists.value.size >= MAX_LISTS) return@withLock null
        val info = BlockListInfo(existing?.id ?: java.util.UUID.randomUUID().toString().take(8), name.trim().take(60).ifBlank { url ?: "list" }, url ?: existing?.url,
            existing?.enabled ?: false, parsed.applied, parsed.skipped, System.currentTimeMillis())
        file(info.id).bufferedWriter().use { w -> parsed.lines.forEach { w.write(it); w.write("\n") } }
        write(_lists.value.filterNot { it.id == info.id } + info)
        info
    } }

    suspend fun setEnabled(id: String, on: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val now = System.currentTimeMillis()
        if (on) db.consentDao().upsert(ConsentRecord(feature(id), STATEMENT, now, null))
        else db.blockRuleDao().consent(feature(id))?.let { db.consentDao().upsert(it.copy(revokedAt = now)) }
        write(_lists.value.map { if (it.id == id) it.copy(enabled = on) else it })
    } }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        db.blockRuleDao().consent(feature(id))?.let { db.consentDao().upsert(it.copy(revokedAt = System.currentTimeMillis())) }
        file(id).delete()
        write(_lists.value.filterNot { it.id == id })
    } }

    private fun feature(id: String) = "blocklist-v1:$id"

    companion object {
        const val MAX_LISTS = 20
        const val STATEMENT = 1
    }
}
