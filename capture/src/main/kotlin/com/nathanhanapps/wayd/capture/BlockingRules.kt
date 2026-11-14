package com.nathanhanapps.wayd.capture

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.room.withTransaction
import com.nathanhanapps.wayd.core.db.BlockRule
import com.nathanhanapps.wayd.core.db.ConsentRecord
import com.nathanhanapps.wayd.core.db.WaydDatabase
import com.nathanhanapps.wayd.core.model.BlockScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BlockingState(
    val rules: List<BlockRule> = emptyList(),
    val activeIds: Set<Long> = emptySet(),
    val running: Boolean = false,
)

/** Imported definitions cannot use an existing per-app grant before individual review. */
internal fun BlockRule.canActivateAfterReview(): Boolean = !needsImportReview

internal fun PortableBlockRule.pendingRule(userId: Int, createdAt: Long) = BlockRule(
    scope = if (kind == "APP") BlockScope.APP else BlockScope.APP_HOST,
    pkg = packageName, host = target, enabled = enabled,
    createdAt = createdAt, matchKind = kind, signingSha256 = signers.joinToString(","), userId = userId,
    portableId = id, portableRevision = revision, needsImportReview = true,
)

data class PolicyImportPlan(
    val rules: List<PortableBlockRule>,
    val existing: List<BlockRule>,
    val conflicts: Int,
    val missingIdentity: Int,
    val mismatchedIdentity: Int,
) {
    val additions get() = rules.count { r -> existing.none { it.portableId == r.id } }
}

/** Owns consent, durable/session rules and the native handle lifetime. No network or privileged operations. */
@Singleton
class BlockingRules @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: WaydDatabase,
    private val lists: BlockListStore,
) {
    private val mutex = Mutex()
    private val bridge = Any()
    private var net: NativeNet? = null
    private var handle = 0L
    private var revision = 0L
    private var nextSessionId = -1L
    private var temporary = emptyList<BlockRule>()
    private var installed = emptyList<BlockRule>()
    private var listsSupported = false
    private val _listEntries = MutableStateFlow<Long?>(null)
    /** Entries the running engine loaded from the enabled block lists; null when the VPN is off or the engine predates lists. */
    val listEntries = _listEntries.asStateFlow()
    private val _state = MutableStateFlow(BlockingState())
    val state = _state.asStateFlow()
    private val dao get() = db.blockRuleDao()
    private val userId get() = Process.myUid() / 100_000

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        val all = dao.all() + temporary
        val valid = validRules(all)
        install(valid)
        publish(all, valid)
    } }

    suspend fun startEngine(engine: NativeNet, fd: Int, mtu: Int, routingProfile: ByteArray = byteArrayOf(), routingOwners: String = ""): Long = withContext(Dispatchers.IO) { mutex.withLock {
        temporary = emptyList()
        val all = dao.all()
        val valid = validRules(all)
        val h = synchronized(bridge) {
            check(handle == 0L)
            val version = engine.nativePolicyVersion()
            check(version in 2..3) { "Incompatible native policy engine" }
            listsSupported = version >= 3
            engine.nativeStart(fd, mtu, wire(valid), routingProfile, routingOwners).also {
                if (it != 0L) { net = engine; handle = it; installed = valid; pushLists() }
            }
        }
        publish(all, if (h != 0L) valid else emptyList())
        h
    } }

    /** Caller holds [bridge]. A rejected file (-1) leaves the engine without lists rather than with a partial one. */
    private fun pushLists() {
        _listEntries.value = if (handle != 0L && listsSupported) net?.nativeSetBlockList(handle, lists.activePath()) else null
    }

    /** Call after enabling, disabling, importing or deleting a block list. */
    suspend fun reloadLists() = withContext(Dispatchers.IO) { mutex.withLock { synchronized(bridge) { pushLists() } } }

    /** Called on the polling thread only after its final poll. Serialized with UI JNI calls. */
    suspend fun stopEngine() = withContext(Dispatchers.IO) { mutex.withLock {
        synchronized(bridge) {
            if (handle != 0L) net?.nativeStop(handle)
            handle = 0L
            net = null
            _listEntries.value = null
        }
        val expired = temporary
        temporary = emptyList()
        installed = emptyList()
        val persistent = dao.all()
        db.withTransaction {
            expired.mapNotNull { it.pkg }.distinct().filter { pkg -> persistent.none { it.enabled && it.pkg == pkg } }.forEach { pkg ->
                dao.consent(feature(pkg))?.let { db.consentDao().upsert(it.copy(revokedAt = System.currentTimeMillis())) }
            }
        }
        publish(dao.all(), emptyList())
    } }

    fun owner(id: Long, owner: Owner?) = synchronized(bridge) {
        // Shared/unknown UID is not a package identity. User-scoped rules never cross Android profiles.
        val value = owner?.takeIf { it.packages.size == 1 && BlockTargets.packageName(it.pkg) }
            ?.let { "${it.uid / 100_000}:${it.pkg}" }.orEmpty()
        if (handle != 0L) net?.nativeFlowInfo(handle, id, value, false)
    }

    fun host(id: Long, value: String) = synchronized(bridge) {
        if (handle != 0L) net?.nativeFlowInfo(handle, id, BlockTargets.host(value).orEmpty(), true)
    }

    suspend fun add(pkg: String, kind: String, value: String, persistent: Boolean): Long = withContext(Dispatchers.IO) { mutex.withLock {
        require(BlockTargets.packageName(pkg) && pkg != context.packageName)
        val signer = identity(pkg) ?: error("Package identity unavailable or shared UID")
        val target = when (kind) {
            "APP" -> "*"
            "HOST" -> BlockTargets.host(value) ?: error("Invalid host")
            "IP" -> BlockTargets.ip(value) ?: error("Invalid IP")
            else -> error("Unsupported target")
        }
        val all = dao.all() + temporary
        require(all.size < 1000)
        if (!persistent) check(synchronized(bridge) { handle != 0L })
        val now = System.currentTimeMillis()
        val rule = BlockRule(
            scope = if (kind == "APP") BlockScope.APP else BlockScope.APP_HOST,
            pkg = pkg, host = target, enabled = true, createdAt = now,
            matchKind = kind, signingSha256 = signer, userId = userId,
            portableId = if (persistent) UUID.randomUUID().toString() else null,
        )
        val old = installed
        var id = 0L
        var candidate = emptyList<BlockRule>()
        try {
            db.withTransaction {
                db.consentDao().upsert(ConsentRecord(feature(pkg), 1, now, null))
                id = if (persistent) dao.insert(rule) else nextSessionId--
                candidate = all + rule.copy(id = id)
                install(validRules(candidate))
            }
        } catch (e: Exception) {
            install(old)
            throw e
        }
        if (!persistent) temporary = temporary + rule.copy(id = id)
        publish(candidate, installed)
        id
    } }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) { mutex.withLock {
        val all = dao.all() + temporary
        val removed = all.firstOrNull { it.id == id } ?: return@withLock
        val candidate = all.filterNot { it.id == id }
        val old = installed
        try {
            db.withTransaction {
                if (id > 0) dao.delete(id)
                if (candidate.none { it.enabled && it.pkg == removed.pkg && it.userId == removed.userId }) {
                    val key = feature(removed.pkg.orEmpty())
                    dao.consent(key)?.let { db.consentDao().upsert(it.copy(revokedAt = System.currentTimeMillis())) }
                }
                install(validRules(candidate))
            }
        } catch (e: Exception) { install(old); throw e }
        temporary = temporary.filterNot { it.id == id }
        publish(candidate, installed)
    } }

    suspend fun exportPolicy(ids: Set<Long>, includeTargets: Boolean): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        require(ids.isNotEmpty() && ids.all { it > 0 })
        val all = dao.all()
        val selected = all.filter { it.id in ids }
        require(selected.size == ids.size)
        val assigned = selected.map { if (it.portableId == null) it.copy(portableId = UUID.randomUUID().toString()) else it }
        val bytes = PolicyBackup.encode(assigned.map { r ->
            val redact = r.matchKind != "APP" && (!includeTargets || r.host == null)
            PortableBlockRule(r.portableId!!, r.portableRevision, r.enabled, r.pkg.orEmpty(),
                r.signingSha256.split(',').filter { it.isNotEmpty() }.sorted(), r.matchKind,
                if (redact) null else r.host, redact)
        })
        db.withTransaction { assigned.filter { r -> selected.first { it.id == r.id }.portableId == null }.forEach { dao.update(it) } }
        publish(dao.all() + temporary, installed)
        bytes
    } }

    suspend fun previewImport(bytes: ByteArray): PolicyImportPlan = withContext(Dispatchers.IO) { mutex.withLock {
        val rules = PolicyBackup.decode(bytes)
        require(rules.none { it.packageName == context.packageName })
        val existing = dao.all() + temporary
        val identities = rules.map { it.packageName }.distinct().associateWith { identity(it) }
        PolicyImportPlan(rules, existing, rules.count { r -> existing.any { it.portableId == r.id } },
            rules.count { identities[it.packageName] == null },
            rules.count { identities[it.packageName] != null && identities[it.packageName] != it.signers.joinToString(",") })
    } }

    /** Merge only: existing UUIDs are kept. No grant is imported or created here. */
    suspend fun importPolicy(plan: PolicyImportPlan): List<Long> = withContext(Dispatchers.IO) { mutex.withLock {
        val all = dao.all() + temporary
        check(all == plan.existing) { "Preview expired" }
        val incoming = plan.rules.filter { r -> all.none { it.portableId == r.id } }
        require(all.size + incoming.size <= PolicyBackup.MAX_RULES)
        val ids = ArrayList<Long>()
        db.withTransaction {
            incoming.forEach { r ->
                require(r.packageName != context.packageName)
                ids += dao.insert(r.pendingRule(userId, System.currentTimeMillis()))
            }
        }
        publish(dao.all() + temporary, installed)
        ids.toList()
    } }

    suspend fun undoImport(ids: List<Long>) = withContext(Dispatchers.IO) { mutex.withLock {
        val all = dao.all()
        require(ids.isNotEmpty() && ids.all { id -> all.any { it.id == id && it.needsImportReview } })
        db.withTransaction { ids.forEach { dao.delete(it) } }
        publish(dao.all() + temporary, installed)
    } }

    /** User confirms this exact definition; a prior app grant never clears the review state. */
    suspend fun approveImport(id: Long) = withContext(Dispatchers.IO) { mutex.withLock {
        val all = dao.all() + temporary
        val rule = all.single { it.id == id }
        require(rule.needsImportReview && rule.userId == userId && rule.pkg != context.packageName)
        val pkg = requireNotNull(rule.pkg)
        require(rule.signingSha256.isNotEmpty() && identity(pkg) == rule.signingSha256)
        val target = requireNotNull(rule.host)
        require(when (rule.matchKind) {
            "APP" -> target == "*"
            "HOST" -> BlockTargets.host(target) == target
            "IP" -> BlockTargets.ip(target) != null
            else -> false
        })
        val reviewed = rule.copy(needsImportReview = false)
        val candidate = all.map { if (it.id == id) reviewed else it }
        val old = installed
        try {
            db.withTransaction {
                dao.update(reviewed)
                db.consentDao().upsert(ConsentRecord(feature(pkg), 1, System.currentTimeMillis(), null))
                install(validRules(candidate))
            }
        } catch (e: Exception) { install(old); throw e }
        publish(candidate, installed)
    } }

    private suspend fun validRules(all: List<BlockRule>): List<BlockRule> {
        val identities = HashMap<String, String?>()
        val consents = HashMap<String, Boolean>()
        return all.filter { r ->
            val pkg = r.pkg ?: return@filter false
            r.enabled && r.canActivateAfterReview() && r.userId == userId && pkg != context.packageName &&
                r.signingSha256.isNotEmpty() && identities.getOrPut(pkg) { identity(pkg) } == r.signingSha256 &&
                consents.getOrPut(pkg) { dao.consent(feature(pkg))?.let { it.statementVersion == 1 && it.revokedAt == null } == true } &&
                when (r.matchKind) { "APP" -> r.host == "*"; "HOST" -> BlockTargets.host(r.host.orEmpty()) == r.host;
                    "IP" -> BlockTargets.ip(r.host.orEmpty()) != null; else -> false }
        }
    }

    private fun install(rules: List<BlockRule>) = synchronized(bridge) {
        if (handle != 0L) check(net?.nativeSetPolicy(handle, wire(rules)) == true) { "Policy activation failed" }
        installed = rules
    }

    private fun wire(rules: List<BlockRule>): String = buildString {
        append("WAYD1\t${++revision}")
        rules.forEach { append("\n${it.id}\t${it.userId}:${it.pkg}\t${it.matchKind}\t${it.host}") }
    }

    private fun publish(all: List<BlockRule>, valid: List<BlockRule>) {
        val running = synchronized(bridge) { handle != 0L }
        _state.value = BlockingState(all, if (running) valid.map { it.id }.toSet() else emptySet(), running)
    }

    private fun feature(pkg: String) = "BLOCK_V1:$userId:$pkg"

    @Suppress("DEPRECATION")
    private fun identity(pkg: String): String? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val uid = info.applicationInfo?.uid ?: return null
        if (uid / 100_000 != userId || pm.getPackagesForUid(uid)?.toSet() != setOf(pkg)) return null
        val signers = info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: return null
        signers.map { signer -> MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) } }.sorted().joinToString(",")
    }.getOrNull()
}
