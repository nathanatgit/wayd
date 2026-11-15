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
 * One rewrite rule for decrypted HTTP/1.1 requests (phase 1, D-066). Rules carry no app: they belong to a [RewriteSet], and
 * apply to each of its target apps (D-073). They only ever act on flows WAYD already decrypts for that app with the existing
 * decryption consent; a rule cannot switch decryption on.
 *
 * [action]: `REJECT` ([arg] `204`, `200-empty`, `200-json`, `200-array`), `HEADER_REMOVE` (header name), `HEADER_SET` (`Name: value`),
 * `QUERY_REMOVE` (parameter name), `REDIRECT` (`302 <url>` or `307 <url>`). [match]: `PREFIX`, `EXACT` or `CONTAINS` against the request path and query, or `REGEX` against the full URL (`https://host/path?query`).
 */
data class RewriteRule(
    val id: Long,
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
        return BlockTargets.host(host) == host && '.' in host &&
            (method.isEmpty() || method.matches(Regex("[A-Za-z]{1,16}"))) && match in setOf("PREFIX", "EXACT", "CONTAINS", "REGEX") && (match != "REGEX" || engineRegex(pattern)) &&
            pattern.isNotEmpty() && pattern.length <= 2048 && text(pattern) && when (action) {
                "REJECT" -> arg in setOf("204", "200-empty", "200-json", "200-array")
                "HEADER_REMOVE", "QUERY_REMOVE" -> token.matches(arg)
                "REDIRECT" -> arg.split(" ", limit = 2).let { it.size == 2 && it[0] in setOf("302", "307") && (it[1].startsWith("https://") || it[1].startsWith("http://")) && it[1].length <= 2048 && it[1].none { c -> c.code <= 0x20 || c.code == 0x7f } }
                "HEADER_SET" -> arg.split(": ", limit = 2).let { it.size == 2 && token.matches(it[0]) && it[1].length <= 1024 && it[1].none { c -> c.code < 0x20 || c.code == 0x7f } }
                else -> false
            }
    }

    /** Engine line for this rule and one app. The same id for several apps is fine: the engine adds their hits together. */
    fun line(userId: Int, pkg: String): String = listOf(id, "$userId:$pkg", (if (subdomains) "." else "") + host, method.uppercase(), match, pattern, action, arg).joinToString("\t")
}

data class RewriteApp(val pkg: String, val enabled: Boolean)

/**
 * A group of rewrite rules applied to a list of target apps (D-073): an imported rule plugin or a set the user made. One import
 * can serve many apps. [mitmHosts] and [suggested] come from the plugin (`[MITM] hostname`, `#!packages=`); they are hints only.
 */
data class RewriteSet(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val rules: List<RewriteRule>,
    val apps: List<RewriteApp>,
    val mitmHosts: List<String> = emptyList(),
    val suggested: List<String> = emptyList(),
    val createdAt: Long = 0,
) {
    /** Engine lines this set produces while enabled: one per enabled rule per enabled app. */
    val lineCount: Int get() = if (!enabled) 0 else rules.count { it.enabled } * apps.count { it.enabled }
}

/** Rewrite sets live in one JSON file under no-backup storage: no schema change, and they never leave the phone in a backup. */
@Singleton
class RewriteStore @Inject constructor(@ApplicationContext context: Context, private val db: WaydDatabase) {
    private val dir = File(context.noBackupFilesDir, "rewrite").apply { mkdirs() }
    private val file = File(dir, "sets.json")
    private val legacy = File(dir, "rules.json")
    private val mutex = Mutex()
    private val _sets = MutableStateFlow(load())
    val sets = _sets.asStateFlow()

    private fun load(): List<RewriteSet> {
        if (!file.isFile && legacy.isFile) migrate()?.let { return it }
        return runCatching { parseSets(JSONArray(file.readText())) }.getOrDefault(emptyList())
    }

    /** D-066 to D-070 stored one app per rule. Each app becomes its own set, so every rule keeps its app, id and switch. */
    private fun migrate(): List<RewriteSet>? = runCatching {
        val a = JSONArray(legacy.readText())
        val old = (0 until a.length()).map { a.getJSONObject(it) }.map { o -> o.getString("pkg") to ruleFrom(o) }.filter { BlockTargets.packageName(it.first) && it.second.valid() }
        val sets = old.groupBy({ it.first }, { it.second }).entries.mapIndexed { i, (pkg, rules) ->
            RewriteSet(i + 1L, pkg, true, rules, listOf(RewriteApp(pkg, true)), createdAt = rules.minOf { it.createdAt })
        }
        file.writeText(toJson(sets).toString())
        legacy.renameTo(File(dir, "rules.v1.json"))
        sets
    }.getOrNull()

    private fun ruleFrom(o: JSONObject) = RewriteRule(o.getLong("id"), o.getString("host"), o.getBoolean("subdomains"), o.getString("method"), o.getString("match"),
        o.getString("pattern"), o.getString("action"), o.getString("arg"), o.getBoolean("enabled"), o.getLong("createdAt"))

    private fun ruleJson(r: RewriteRule) = JSONObject().put("id", r.id).put("host", r.host).put("subdomains", r.subdomains).put("method", r.method).put("match", r.match)
        .put("pattern", r.pattern).put("action", r.action).put("arg", r.arg).put("enabled", r.enabled).put("createdAt", r.createdAt)

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun parseSets(a: JSONArray): List<RewriteSet> = (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
        val r = o.getJSONArray("rules")
        val p = o.getJSONArray("apps")
        RewriteSet(
            o.getLong("id"), o.getString("name"), o.getBoolean("enabled"),
            (0 until r.length()).map { ruleFrom(r.getJSONObject(it)) }.filter { it.valid() },
            (0 until p.length()).map { p.getJSONObject(it) }.map { RewriteApp(it.getString("pkg"), it.getBoolean("enabled")) }.filter { BlockTargets.packageName(it.pkg) },
            strings(o.optJSONArray("mitmHosts")), strings(o.optJSONArray("suggested")).filter(BlockTargets::packageName), o.optLong("createdAt"),
        )
    }

    private fun toJson(sets: List<RewriteSet>) = JSONArray().apply {
        sets.forEach { s ->
            put(JSONObject().put("id", s.id).put("name", s.name).put("enabled", s.enabled).put("createdAt", s.createdAt)
                .put("rules", JSONArray().apply { s.rules.forEach { put(ruleJson(it)) } })
                .put("apps", JSONArray().apply { s.apps.forEach { put(JSONObject().put("pkg", it.pkg).put("enabled", it.enabled)) } })
                .put("mitmHosts", JSONArray(s.mitmHosts)).put("suggested", JSONArray(s.suggested)))
        }
    }

    /** Engine text for every enabled rule of every enabled set, once per enabled app; the header alone clears them. */
    fun wire(userId: Int): String = (listOf("WAYDRW1") + _sets.value.filter { it.enabled }.flatMap { s ->
        val apps = s.apps.filter { it.enabled }
        s.rules.filter { it.enabled && it.valid() }.flatMap { r -> apps.map { r.line(userId, it.pkg) } }
    }).joinToString("\n")

    /** Apps whose decrypted requests a set may rewrite right now; each holds a `rewrite-v1:<pkg>` consent. */
    private fun activeApps(sets: List<RewriteSet>) = sets.filter { it.enabled }.flatMap { s -> s.apps.filter { it.enabled }.map { it.pkg } }.toSet()

    /**
     * Saves [next] unless it would exceed the engine's line limit (the engine rejects the whole set above it). Consent follows
     * the change: recorded for apps that became active, revoked for apps no set targets any more. False when refused.
     */
    private suspend fun commit(next: List<RewriteSet>): Boolean {
        if (next.sumOf { it.lineCount } > MAX_ENGINE_LINES || next.sumOf { it.rules.size } > MAX_RULES) return false
        val before = activeApps(_sets.value)
        val after = activeApps(next)
        val now = System.currentTimeMillis()
        (after - before).forEach { db.consentDao().upsert(ConsentRecord(feature(it), STATEMENT, now, null)) }
        (before - after).forEach { pkg -> db.blockRuleDao().consent(feature(pkg))?.takeIf { it.revokedAt == null }?.let { db.consentDao().upsert(it.copy(revokedAt = now)) } }
        file.writeText(toJson(next).toString())
        _sets.value = next
        return true
    }

    private fun nextRuleId() = (_sets.value.flatMap { it.rules }.maxOfOrNull { it.id } ?: 0L) + 1

    private suspend fun update(setId: Long, change: (RewriteSet) -> RewriteSet?): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val current = _sets.value.firstOrNull { it.id == setId } ?: return@withLock false
        val changed = change(current) ?: return@withLock false
        commit(_sets.value.map { if (it.id == setId) changed else it })
    } }

    /** A new, empty set. Null when full. */
    suspend fun createSet(name: String): RewriteSet? = withContext(Dispatchers.IO) { mutex.withLock {
        if (_sets.value.size >= MAX_SETS) return@withLock null
        val set = RewriteSet((_sets.value.maxOfOrNull { it.id } ?: 0L) + 1, name.trim().take(60).ifBlank { "Rules" }, true, emptyList(), emptyList(), createdAt = System.currentTimeMillis())
        set.takeIf { commit(_sets.value + it) }
    } }

    /** A set made from a rule plugin, without apps yet: the user picks them on the set's page. Duplicate rules are dropped. */
    suspend fun importPlugin(plugin: RewritePluginParser.Result, fallbackName: String): RewriteSet? = withContext(Dispatchers.IO) { mutex.withLock {
        if (_sets.value.size >= MAX_SETS) return@withLock null
        val now = System.currentTimeMillis()
        var next = nextRuleId()
        val rules = plugin.rules.distinct().map { RewriteRule(next++, it.host, it.subdomains, it.method, it.match, it.pattern, it.action, it.arg, true, now) }
            .filter { it.valid() }.take(MAX_RULES_PER_SET)
        if (rules.isEmpty()) return@withLock null
        val set = RewriteSet((_sets.value.maxOfOrNull { it.id } ?: 0L) + 1, (plugin.name ?: fallbackName).trim().take(60).ifBlank { "Plugin" }, true, rules, emptyList(),
            plugin.mitmHosts, plugin.packages, now)
        set.takeIf { commit(_sets.value + it) }
    } }

    suspend fun rename(setId: Long, name: String) = update(setId) { s -> name.trim().take(60).takeIf { it.isNotEmpty() }?.let { s.copy(name = it) } }
    suspend fun setEnabled(setId: Long, on: Boolean) = update(setId) { it.copy(enabled = on) }

    suspend fun deleteSet(setId: Long) = withContext(Dispatchers.IO) { mutex.withLock { commit(_sets.value.filterNot { it.id == setId }) } }

    /** Adds a hand-made rule to a set. False when invalid or over a limit. */
    suspend fun addRule(setId: Long, host: String, subdomains: Boolean, method: String, match: String, pattern: String, action: String, arg: String): Boolean {
        val draft = RewriteRule(0, BlockTargets.host(host.trim()).orEmpty(), subdomains, method.trim().uppercase(), match, pattern.trim(), action, arg.trim(), true, System.currentTimeMillis())
        if (!draft.valid()) return false
        // The id is taken inside the store lock, so two quick adds never share one.
        return update(setId) { s -> s.takeIf { it.rules.size < MAX_RULES_PER_SET }?.copy(rules = s.rules + draft.copy(id = nextRuleId())) }
    }

    /** Replaces a rule's fields from the editor, keeping its id (and so its hit count) and its switch. False when invalid. */
    suspend fun updateRule(setId: Long, ruleId: Long, edited: RewriteRule): Boolean = update(setId) { s ->
        val old = s.rules.firstOrNull { it.id == ruleId } ?: return@update null
        val next = edited.copy(id = old.id, enabled = old.enabled, createdAt = old.createdAt)
        next.takeIf { it.valid() }?.let { n -> s.copy(rules = s.rules.map { if (it.id == ruleId) n else it }) }
    }

    suspend fun setRuleEnabled(setId: Long, ruleId: Long, on: Boolean) = update(setId) { s -> s.copy(rules = s.rules.map { if (it.id == ruleId) it.copy(enabled = on) else it }) }
    suspend fun deleteRule(setId: Long, ruleId: Long) = update(setId) { s -> s.copy(rules = s.rules.filterNot { it.id == ruleId }) }

    /** Adds a target app (enabled), which records its rewrite consent. False when the name is invalid, already there, or over a limit. */
    suspend fun addApp(setId: Long, pkg: String) = update(setId) { s ->
        val p = pkg.trim()
        if (!BlockTargets.packageName(p) || s.apps.any { it.pkg == p } || s.apps.size >= MAX_APPS_PER_SET) null else s.copy(apps = s.apps + RewriteApp(p, true))
    }

    suspend fun setAppEnabled(setId: Long, pkg: String, on: Boolean) = update(setId) { s -> s.copy(apps = s.apps.map { if (it.pkg == pkg) it.copy(enabled = on) else it }) }
    suspend fun removeApp(setId: Long, pkg: String) = update(setId) { s -> s.copy(apps = s.apps.filterNot { it.pkg == pkg }) }

    private fun feature(pkg: String) = "rewrite-v1:$pkg"

    companion object {
        const val MAX_SETS = 50
        const val MAX_RULES_PER_SET = 500
        const val MAX_RULES = 2_000
        const val MAX_APPS_PER_SET = 100
        /** The engine's limit (`mitm/rewrite.rs` MAX_RULES): rules × apps over all sets. */
        const val MAX_ENGINE_LINES = 2_000
        const val STATEMENT = 1
    }
}

/**
 * Best-effort check that the engine's linear-time regex library will accept a pattern: it must compile here and use no
 * look-around or back-references. The engine has the final word and disables only that rule if it disagrees.
 */
internal fun engineRegex(pattern: String): Boolean =
    runCatching { Regex(pattern) }.isSuccess && listOf("(?=", "(?!", "(?<=", "(?<!").none { it in pattern } && !Regex("""\[1-9]""").containsMatchIn(pattern)
