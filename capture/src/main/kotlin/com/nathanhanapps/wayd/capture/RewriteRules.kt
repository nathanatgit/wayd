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
 * One rewrite rule for decrypted HTTP/1.1 requests of one app (phase 1, D-066). It only ever applies to flows WAYD already
 * decrypts for that app with the existing decryption consent; a rule cannot switch decryption on.
 *
 * [action]: `REJECT` ([arg] `204`, `200-empty`, `200-json`), `HEADER_REMOVE` (header name), `HEADER_SET` (`Name: value`),
 * `QUERY_REMOVE` (parameter name), `REDIRECT` (`302 <url>` or `307 <url>`). [match]: `PREFIX`, `EXACT` or `CONTAINS` against the request path and query, or `REGEX` against the full URL (`https://host/path?query`).
 */
data class RewriteRule(
    val id: Long,
    val pkg: String,
    val host: String,
    val subdomains: Boolean,
    val method: String,
    val match: String,
    val pattern: String,
    val action: String,
    val arg: String,
    val enabled: Boolean,
    val createdAt: Long,
) {
    /** Mirrors the engine's validation (`mitm/rewrite.rs`), so a rule the UI accepts is never the line that rejects the whole set. */
    fun valid(): Boolean {
        val token = Regex("[A-Za-z0-9_.-]{1,128}")
        val text = { s: String -> s.none { it == '\t' || it == '\n' || it == '\r' } }
        return BlockTargets.packageName(pkg) && BlockTargets.host(host) == host && '.' in host &&
            (method.isEmpty() || method.matches(Regex("[A-Za-z]{1,16}"))) && match in setOf("PREFIX", "EXACT", "CONTAINS", "REGEX") && (match != "REGEX" || engineRegex(pattern)) &&
            pattern.isNotEmpty() && pattern.length <= 2048 && text(pattern) && when (action) {
                "REJECT" -> arg in setOf("204", "200-empty", "200-json")
                "HEADER_REMOVE", "QUERY_REMOVE" -> token.matches(arg)
                "REDIRECT" -> arg.split(" ", limit = 2).let { it.size == 2 && it[0] in setOf("302", "307") && (it[1].startsWith("https://") || it[1].startsWith("http://")) && it[1].length <= 2048 && it[1].none { c -> c.code <= 0x20 || c.code == 0x7f } }
                "HEADER_SET" -> arg.split(": ", limit = 2).let { it.size == 2 && token.matches(it[0]) && it[1].length <= 1024 && it[1].none { c -> c.code < 0x20 || c.code == 0x7f } }
                else -> false
            }
    }

    fun line(userId: Int): String = listOf(id, "$userId:$pkg", (if (subdomains) "." else "") + host, method.uppercase(), match, pattern, action, arg).joinToString("\t")
}

/** Rewrite rules live in one JSON file under no-backup storage: no schema change, and they never leave the phone in a backup. */
@Singleton
class RewriteStore @Inject constructor(@ApplicationContext context: Context, private val db: WaydDatabase) {
    private val file = File(File(context.noBackupFilesDir, "rewrite").apply { mkdirs() }, "rules.json")
    private val mutex = Mutex()
    private val _rules = MutableStateFlow(read())
    val rules = _rules.asStateFlow()

    private fun read(): List<RewriteRule> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            RewriteRule(it.getLong("id"), it.getString("pkg"), it.getString("host"), it.getBoolean("subdomains"), it.getString("method"), it.getString("match"),
                it.getString("pattern"), it.getString("action"), it.getString("arg"), it.getBoolean("enabled"), it.getLong("createdAt"))
        }.filter { it.valid() }
    }.getOrDefault(emptyList())

    private fun write(list: List<RewriteRule>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("pkg", it.pkg).put("host", it.host).put("subdomains", it.subdomains).put("method", it.method).put("match", it.match)
                .put("pattern", it.pattern).put("action", it.action).put("arg", it.arg).put("enabled", it.enabled).put("createdAt", it.createdAt))
        }
        file.writeText(a.toString())
        _rules.value = list
    }

    /** Engine text for the enabled rules; the header alone clears them. */
    fun wire(userId: Int): String = (listOf("WAYDRW1") + _rules.value.filter { it.enabled && it.valid() }.map { it.line(userId) }).joinToString("\n")

    /** Adds a rule and records the per-app consent for rewriting that app's decrypted requests. Null when invalid or full. */
    suspend fun add(pkg: String, host: String, subdomains: Boolean, method: String, match: String, pattern: String, action: String, arg: String): RewriteRule? =
        withContext(Dispatchers.IO) { mutex.withLock {
            val now = System.currentTimeMillis()
            val rule = RewriteRule((_rules.value.maxOfOrNull { it.id } ?: 0L) + 1, pkg.trim(), BlockTargets.host(host.trim()).orEmpty(), subdomains, method.trim().uppercase(),
                match, pattern.trim(), action, arg.trim(), true, now)
            if (!rule.valid() || _rules.value.size >= MAX_RULES) return@withLock null
            db.consentDao().upsert(ConsentRecord(feature(rule.pkg), STATEMENT, now, null))
            write(_rules.value + rule)
            rule
        } }

    suspend fun setEnabled(id: Long, on: Boolean) = withContext(Dispatchers.IO) { mutex.withLock { write(_rules.value.map { if (it.id == id) it.copy(enabled = on) else it }) } }

    /** Removing an app's last rule revokes its rewrite consent. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { mutex.withLock {
        val rule = _rules.value.firstOrNull { it.id == id } ?: return@withLock
        val rest = _rules.value - rule
        if (rest.none { it.pkg == rule.pkg }) db.blockRuleDao().consent(feature(rule.pkg))?.let { db.consentDao().upsert(it.copy(revokedAt = System.currentTimeMillis())) }
        write(rest)
    } }

    private fun feature(pkg: String) = "rewrite-v1:$pkg"

    companion object {
        const val MAX_RULES = 500
        const val STATEMENT = 1
    }
}

/**
 * Best-effort check that the engine's linear-time regex library will accept a pattern: it must compile here and use no
 * look-around or back-references. The engine has the final word and disables only that rule if it disagrees.
 */
internal fun engineRegex(pattern: String): Boolean =
    runCatching { Regex(pattern) }.isSuccess && listOf("(?=", "(?!", "(?<=", "(?<!").none { it in pattern } && !Regex("""\[1-9]""").containsMatchIn(pattern)
