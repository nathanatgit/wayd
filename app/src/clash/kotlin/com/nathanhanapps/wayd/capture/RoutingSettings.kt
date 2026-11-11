package com.nathanhanapps.wayd.capture

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.nathanhanapps.wayd.core.db.ConsentRecord
import com.nathanhanapps.wayd.core.db.WaydDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** FlClash-style access control: route only the listed apps, or every app except the listed ones. */
enum class RoutingMode { WHITELIST, BLACKLIST }

data class RoutingUi(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val mode: RoutingMode = RoutingMode.WHITELIST,
    val apps: Set<String> = emptySet(),
    val damaged: Boolean = false,
)
data class RoutingApp(val pkg: String, val label: String, val system: Boolean = false)
data class RoutingProfile(val id: String, val name: String, val updated: Long, val selected: Boolean)

/**
 * Routing profiles and access control. Secrets live only in no-backup private files, encrypted with a random AES-256 data
 * key that is itself wrapped by a non-exportable Android Keystore key. Bulk data never goes through the Keystore (which
 * is slow for megabytes): one small unwrap per process, then in-process AES-GCM. The decrypted index is cached in memory;
 * profile bundles are read only when needed.
 *
 * Consent: whitelist mode records one consent per selected app bound to its signer; blacklist mode records one global
 * consent ([BLACKLIST_FEATURE]) because it routes apps nobody picked, including apps installed later. Excluded apps need
 * no consent (they are not routed). WAYD blocks still apply first in both modes.
 */
@Singleton
class RoutingSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: WaydDatabase,
    private val capture: CaptureState,
    private val geoIp: RoutingGeoIp,
) {
    private val mutex = Mutex()
    private val dir = File(context.noBackupFilesDir, "routing")
    private val keyFile = AtomicFile(File(dir, "key.wrapped"))
    private val indexFile = AtomicFile(File(dir, "index.enc"))
    private val legacy = AtomicFile(File(context.noBackupFilesDir, "routing-v1.enc"))
    private val _state = MutableStateFlow(RoutingUi())
    val state = _state.asStateFlow()
    private val userId get() = Process.myUid() / 100_000
    private val pm get() = context.packageManager
    private var dataKey: SecretKey? = null
    private var cached: JSONObject? = null

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        runCatching { index() }.onSuccess { publish(it) }.onFailure { _state.value = RoutingUi(damaged = true) }
    } }

    /**
     * Installed single-package apps of this user (shared UIDs cannot be attributed to one app). WAYD itself is listed too: its own
     * traffic never enters the tunnel, so selecting it only routes its external AI calls (D-050).
     */
    suspend fun apps(): List<RoutingApp> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(0).asSequence()
            .filter { pm.getPackagesForUid(it.uid)?.size == 1 }
            .map { RoutingApp(it.packageName, it.loadLabel(pm).toString(), it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) }
            .sortedBy { it.label.lowercase() }.toList()
    }

    fun validate(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.size <= 48 * 1024 * 1024 && NativeNet().nativeValidateRouting(bytes)

    // ---- Profiles ----

    suspend fun profiles(): List<RoutingProfile> = withContext(Dispatchers.IO) { mutex.withLock {
        val value = index() ?: return@withLock emptyList()
        val entries = value.optJSONArray("profiles") ?: JSONArray()
        (0 until entries.length()).map { i ->
            val e = entries.getJSONObject(i)
            RoutingProfile(e.getString("id"), e.getString("name"), e.getLong("updated"), value.optString("selectedProfile") == e.getString("id"))
        }
    } }

    /** Stores (or replaces) a profile. The first profile becomes the selected one. */
    suspend fun archive(name: String, bytes: ByteArray, id: String? = null): String = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        require(name.isNotBlank() && name.length <= 128 && bytes.size <= 48 * 1024 * 1024)
        val value = index() ?: empty()
        val entries = value.optJSONArray("profiles") ?: JSONArray()
        val profileId = id ?: UUID.randomUUID().toString()
        val next = JSONArray()
        for (i in 0 until entries.length()) if (entries.getJSONObject(i).getString("id") != profileId) next.put(entries.getJSONObject(i))
        require(next.length() < 20)
        next.put(JSONObject().put("id", profileId).put("name", name).put("updated", System.currentTimeMillis()))
        writeBlob(profileFile(profileId), bytes)
        value.put("profiles", next)
        if (value.optString("selectedProfile").isEmpty()) value.put("selectedProfile", profileId)
        commit(value)
        profileId
    } }

    suspend fun select(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        val value = requireNotNull(index())
        require(entryIds(value).contains(id))
        value.put("selectedProfile", id)
        commit(value)
    } }

    suspend fun profile(id: String): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        require(entryIds(requireNotNull(index())).contains(id))
        readBlob(profileFile(id))
    } }

    suspend fun removeProfile(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        val value = requireNotNull(index())
        val entries = value.optJSONArray("profiles") ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until entries.length()) if (entries.getJSONObject(i).getString("id") != id) next.put(entries.getJSONObject(i))
        value.put("profiles", next)
        if (value.optString("selectedProfile") == id) {
            value.put("selectedProfile", if (next.length() > 0) next.getJSONObject(0).getString("id") else "")
            if (next.length() == 0) value.put("enabled", false)
        }
        commit(value)
        profileFile(id).delete()
    } }

    // ---- Access control ----

    /**
     * Replaces the access list. Whitelist: each app gets a fresh signer-bound consent and earlier grants are revoked.
     * Blacklist: one global consent is recorded and per-app grants are revoked. Changing mode or list disables routing
     * only if the new configuration is empty in whitelist mode.
     */
    suspend fun setAccess(mode: RoutingMode, packages: Set<String>) = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        require(packages.size <= 1000 && packages.all { BlockTargets.packageName(it) })
        val value = index() ?: empty()
        val now = System.currentTimeMillis()
        revokeAll(now)
        val bindings = JSONObject()
        when (mode) {
            RoutingMode.WHITELIST -> {
                packages.sorted().forEach { pkg -> bindings.put(pkg, requireNotNull(identity(pkg))) }
                packages.forEach { pkg -> db.consentDao().upsert(ConsentRecord(feature(pkg, bindings.getString(pkg)), 1, now, null)) }
                if (packages.isEmpty()) value.put("enabled", false)
            }
            RoutingMode.BLACKLIST -> {
                packages.sorted().forEach { pkg -> bindings.put(pkg, "") }
                db.consentDao().upsert(ConsentRecord(blacklistFeature(), BLACKLIST_STATEMENT, now, null))
            }
        }
        value.put("mode", mode.name).put("bindings", bindings)
        commit(value)
    } }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        val value = requireNotNull(index())
        if (enabled) snapshot(value) // identity, consent and profile must all be valid
        value.put("enabled", enabled)
        commit(value)
    } }

    suspend fun delete() = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        revokeAll(System.currentTimeMillis())
        dir.deleteRecursively()
        legacy.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.apply { deleteEntry(WRAP_ALIAS); deleteEntry(LEGACY_ALIAS) }
        dataKey = null; cached = null
        _state.value = RoutingUi()
    } }

    /** Invalid enabled settings fail VPN startup; never silently become Direct. */
    suspend fun prepare(): RoutingSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        geoIp.load()
        val value = index() ?: return@withLock RoutingSnapshot()
        publish(value)
        if (!value.optBoolean("enabled")) RoutingSnapshot() else snapshot(value)
    } }

    /**
     * Whitelist: a routed app being replaced or removed stops the VPN, so a grant never moves to a new installation.
     * Blacklist routes newly installed apps by design (that is what the global consent covers), so nothing stops.
     */
    fun affected(pkg: String?): Boolean {
        val s = _state.value
        return s.enabled && s.mode == RoutingMode.WHITELIST && pkg in s.apps
    }

    private suspend fun snapshot(value: JSONObject): RoutingSnapshot {
        geoIp.load()
        require(value.getInt("user") == userId)
        val selected = value.optString("selectedProfile")
        require(selected.isNotEmpty() && entryIds(value).contains(selected))
        val profile = readBlob(profileFile(selected))
        require(validate(profile))
        val bindings = value.optJSONObject("bindings") ?: JSONObject()
        val packages = bindings.keys().asSequence().toList()
        return when (mode(value)) {
            RoutingMode.WHITELIST -> {
                require(packages.size in 1..1000)
                val owners = packages.map { pkg ->
                    val signer = bindings.getString(pkg)
                    require(identity(pkg) == signer)
                    require(db.blockRuleDao().consent(feature(pkg, signer))?.let { it.statementVersion == 1 && it.revokedAt == null } == true)
                    "$userId:$pkg"
                }.sorted()
                RoutingSnapshot(profile, owners.joinToString("\n"))
            }
            RoutingMode.BLACKLIST -> {
                require(db.blockRuleDao().consent(blacklistFeature())?.let { it.statementVersion == BLACKLIST_STATEMENT && it.revokedAt == null } == true)
                RoutingSnapshot(profile, (listOf("!exclude:$userId") + packages.sorted().map { "$userId:$it" }).joinToString("\n"))
            }
        }
    }

    private suspend fun revokeAll(now: Long) {
        db.consentDao().routingRecords().filter { it.feature.startsWith("routing-v1:$userId:") && it.revokedAt == null }.forEach {
            db.consentDao().upsert(it.copy(revokedAt = now))
        }
    }

    private fun identity(pkg: String): String? = runCatching {
        require(BlockTargets.packageName(pkg))
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val uid = requireNotNull(info.applicationInfo).uid
        require(uid / 100_000 == userId && pm.getPackagesForUid(uid)?.size == 1)
        requireNotNull(info.signingInfo).apkContentsSigners.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().also { require(it.isNotEmpty()) }.joinToString(",")
    }.getOrNull()

    private fun feature(pkg: String, signer: String) = "routing-v1:$userId:$pkg:$signer"
    private fun blacklistFeature() = "routing-v1:$userId:*blacklist"
    private fun requireStopped() = check(capture.ui.value.phase in setOf(CapturePhase.IDLE, CapturePhase.ERROR))
    private fun mode(value: JSONObject) = runCatching { RoutingMode.valueOf(value.optString("mode")) }.getOrDefault(RoutingMode.WHITELIST)
    private fun entryIds(value: JSONObject): Set<String> {
        val entries = value.optJSONArray("profiles") ?: return emptySet()
        return (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }.toSet()
    }
    private fun profileFile(id: String): AtomicFile {
        require(id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' })
        return AtomicFile(File(dir, "profile-$id.enc"))
    }
    private fun empty() = JSONObject().put("user", userId).put("enabled", false).put("mode", RoutingMode.WHITELIST.name)
        .put("bindings", JSONObject()).put("profiles", JSONArray()).put("selectedProfile", "")

    private fun publish(value: JSONObject?) {
        _state.value = if (value == null) RoutingUi() else RoutingUi(
            available = value.optString("selectedProfile").isNotEmpty(),
            enabled = value.optBoolean("enabled"),
            mode = mode(value),
            apps = (value.optJSONObject("bindings") ?: JSONObject()).keys().asSequence().toSet(),
        )
    }

    private fun commit(value: JSONObject) {
        writeBlob(indexFile, value.toString().toByteArray(Charsets.UTF_8))
        cached = value
        publish(value)
    }

    /** Decrypted index (copy), migrating the old single-file format once. */
    private fun index(): JSONObject? {
        cached?.let { return JSONObject(it.toString()) }
        val value = when {
            indexFile.baseFile.exists() || File(indexFile.baseFile.path + ".bak").exists() -> JSONObject(String(readBlob(indexFile), Charsets.UTF_8))
            legacy.baseFile.exists() || File(legacy.baseFile.path + ".bak").exists() -> migrate()
            else -> null
        }
        cached = value
        return value?.let { JSONObject(it.toString()) }
    }

    /** One-time move from `routing-v1.enc` (whole file encrypted by the Keystore key) to the index + per-profile layout. */
    private fun migrate(): JSONObject {
        val old = JSONObject(String(decryptLegacy(readAll(legacy)), Charsets.UTF_8))
        val value = empty().put("user", old.optInt("user", userId)).put("enabled", false)
            .put("bindings", old.optJSONObject("bindings") ?: JSONObject())
            .put("selectedProfile", old.optString("selectedProfile"))
        val entries = old.optJSONArray("profiles") ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            writeBlob(profileFile(e.getString("id")), Base64.decode(e.getString("data"), Base64.NO_WRAP))
            next.put(JSONObject().put("id", e.getString("id")).put("name", e.getString("name")).put("updated", e.getLong("updated")))
        }
        value.put("profiles", next)
        writeBlob(indexFile, value.toString().toByteArray(Charsets.UTF_8))
        legacy.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LEGACY_ALIAS) }
        return value
    }

    // ---- Crypto ----

    private fun key(): SecretKey {
        dataKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrap = (store.getKey(WRAP_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val raw = if (keyFile.baseFile.exists() || File(keyFile.baseFile.path + ".bak").exists()) {
            val wrapped = readAll(keyFile)
            Cipher.getInstance(GCM).run { init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, wrapped, 0, 12)); doFinal(wrapped, 12, wrapped.size - 12) }
        } else {
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, wrap) }
            dir.mkdirs()
            writeAll(keyFile, cipher.iv + cipher.doFinal(fresh))
            fresh
        }
        require(raw.size == 32)
        return SecretKeySpec(raw, "AES").also { dataKey = it }
    }

    private fun writeBlob(file: AtomicFile, plain: ByteArray) {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        dir.mkdirs()
        writeAll(file, iv + cipher.doFinal(plain))
    }

    private fun readBlob(file: AtomicFile): ByteArray {
        val data = readAll(file)
        require(data.size >= 29)
        return Cipher.getInstance(GCM).run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)); doFinal(data, 12, data.size - 12) }
    }

    private fun decryptLegacy(data: ByteArray): ByteArray {
        require(data.size in 29..MAX_FILE)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = requireNotNull(store.getKey(LEGACY_ALIAS, null) as? SecretKey)
        return Cipher.getInstance(GCM).run { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)); doFinal(data, 12, data.size - 12) }
    }

    private fun readAll(file: AtomicFile): ByteArray = file.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size() + n <= MAX_FILE)
            output.write(buffer, 0, n)
        }
        output.toByteArray()
    }

    private fun writeAll(file: AtomicFile, bytes: ByteArray) {
        require(bytes.size <= MAX_FILE)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private companion object {
        const val GCM = "AES/GCM/NoPadding"
        const val WRAP_ALIAS = "wayd-routing-v2-wrap"
        const val LEGACY_ALIAS = "wayd-routing-v1"
        const val MAX_FILE = 70 * 1024 * 1024
        /** Consent statement version for blacklist ("route every app except…") mode. */
        const val BLACKLIST_STATEMENT = 2
    }
}
