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
import javax.inject.Inject
import javax.inject.Singleton

data class BlockingState(
    val rules: List<BlockRule> = emptyList(),
    val activeIds: Set<Long> = emptySet(),
    val running: Boolean = false,
)

/** Owns consent, durable/session rules and the native handle lifetime. No network or privileged operations. */
@Singleton
class BlockingRules @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: WaydDatabase,
) {
    private val mutex = Mutex()
    private val bridge = Any()
    private var net: NativeNet? = null
    private var handle = 0L
    private var revision = 0L
    private var nextSessionId = -1L
    private var temporary = emptyList<BlockRule>()
    private var installed = emptyList<BlockRule>()
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

    suspend fun startEngine(engine: NativeNet, fd: Int, mtu: Int): Long = withContext(Dispatchers.IO) { mutex.withLock {
        temporary = emptyList()
        val all = dao.all()
        val valid = validRules(all)
        val h = synchronized(bridge) {
            check(handle == 0L)
            check(engine.nativePolicyVersion() == 1) { "Incompatible native policy engine" }
            engine.nativeStart(fd, mtu, wire(valid)).also {
                if (it != 0L) { net = engine; handle = it; installed = valid }
            }
        }
        publish(all, if (h != 0L) valid else emptyList())
        h
    } }

    /** Called on the polling thread only after its final poll. Serialized with UI JNI calls. */
    suspend fun stopEngine() = withContext(Dispatchers.IO) { mutex.withLock {
        synchronized(bridge) {
            if (handle != 0L) net?.nativeStop(handle)
            handle = 0L
            net = null
        }
        val expired = temporary
        temporary = emptyList()
        installed = emptyList()
        _state.value = BlockingState(_state.value.rules.filter { it.id > 0 })
        val persistent = dao.all()
        db.withTransaction {
            expired.mapNotNull { it.pkg }.distinct().filter { pkg -> persistent.none { it.enabled && it.pkg == pkg } }.forEach { pkg ->
                dao.consent(feature(pkg))?.let { db.consentDao().upsert(it.copy(revokedAt = System.currentTimeMillis())) }
            }
        }
        _state.value = BlockingState(persistent)
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

    private suspend fun validRules(all: List<BlockRule>): List<BlockRule> {
        val identities = HashMap<String, String?>()
        val consents = HashMap<String, Boolean>()
        return all.filter { r ->
            val pkg = r.pkg ?: return@filter false
            r.enabled && r.userId == userId && pkg != context.packageName &&
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
