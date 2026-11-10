package com.nathanhanapps.wayd.capture

import android.content.Context
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
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class RoutingUi(val available: Boolean = false, val enabled: Boolean = false, val apps: Set<String> = emptySet(), val damaged: Boolean = false)
data class RoutingSnapshot(val profile: ByteArray = byteArrayOf(), val owners: String = "")
data class RoutingApp(val pkg: String, val label: String)

/** Routing secrets live only in a Keystore-encrypted, no-backup private file. */
@Singleton
class RoutingSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: WaydDatabase,
    private val capture: CaptureState,
    private val geoIp: RoutingGeoIp,
) {
    private val mutex = Mutex()
    private val file = AtomicFile(File(context.noBackupFilesDir, "routing-v1.enc"))
    private val _state = MutableStateFlow(RoutingUi())
    val state = _state.asStateFlow()
    private val userId get() = Process.myUid() / 100_000
    private val pm get() = context.packageManager
    private val alias = "wayd-routing-v1"

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock {
        runCatching { read() }.onSuccess { publish(it) }.onFailure { _state.value = RoutingUi(damaged = true) }
    } }

    suspend fun apps(): List<RoutingApp> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(0).asSequence()
            .filter { it.packageName != context.packageName && pm.getPackagesForUid(it.uid)?.size == 1 }
            .map { RoutingApp(it.packageName, it.loadLabel(pm).toString()) }.sortedBy { it.label.lowercase() }.toList()
    }

    fun validate(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.size <= 48 * 1024 * 1024 && NativeNet().nativeValidateRouting(bytes)

    suspend fun save(bytes: ByteArray, packages: Set<String>, profileId: String? = null) = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        require(validate(bytes) && packages.isNotEmpty() && packages.size <= 1000)
        val previous = read()
        val bindings = JSONObject()
        packages.sorted().forEach { pkg -> bindings.put(pkg, requireNotNull(identity(pkg))) }
        val now = System.currentTimeMillis()
        // Consent is committed before the encrypted profile; a failed write cannot
        // activate a new profile. Every later activation revalidates this binding.
        read()?.optJSONObject("bindings")?.let { old ->
            old.keys().asSequence().toList().forEach { pkg ->
                db.blockRuleDao().consent(feature(pkg, old.getString(pkg)))?.let {
                    db.consentDao().upsert(it.copy(revokedAt = now))
                }
            }
        }
        packages.forEach { pkg -> db.consentDao().upsert(ConsentRecord(feature(pkg, bindings.getString(pkg)), 1, now, null)) }
        val value = JSONObject().put("profile", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("enabled", false).put("user", userId).put("bindings", bindings)
            .put("profiles", previous?.optJSONArray("profiles") ?: JSONArray()).put("selectedProfile", profileId ?: "")
        write(value)
        publish(value)
    } }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        val value = requireNotNull(read())
        if (enabled) snapshot(value) // identity/consent/profile must all be valid
        value.put("enabled", enabled)
        write(value)
        publish(value)
    } }

    suspend fun delete() = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        db.consentDao().routingRecords().filter { it.feature.startsWith("routing-v1:$userId:") }.forEach {
            db.consentDao().upsert(it.copy(revokedAt = System.currentTimeMillis()))
        }
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        _state.value = RoutingUi()
    } }

    /** Invalid enabled settings fail VPN startup; never silently become Direct. */
    suspend fun prepare(): RoutingSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        geoIp.load()
        val value = read() ?: return@withLock RoutingSnapshot()
        publish(value)
        if (!value.getBoolean("enabled")) RoutingSnapshot() else snapshot(value)
    } }

    suspend fun profiles(): List<RoutingProfile> = withContext(Dispatchers.IO) { mutex.withLock {
        val value = read() ?: return@withLock emptyList()
        val entries = value.optJSONArray("profiles") ?: JSONArray()
        (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            RoutingProfile(entry.getString("id"), entry.getString("name"), entry.getLong("updated"), value.optString("selectedProfile") == entry.getString("id"))
        }
    } }
    suspend fun archive(name: String, bytes: ByteArray, id: String? = null): String = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        require(name.isNotBlank() && name.length <= 128 && bytes.size <= 48 * 1024 * 1024)
        val value = read() ?: JSONObject().put("enabled", false).put("user", userId).put("bindings", JSONObject()).put("profile", "")
        val entries = value.optJSONArray("profiles") ?: JSONArray()
        val profileId = id ?: UUID.randomUUID().toString()
        val next = JSONArray()
        for (index in 0 until entries.length()) if (entries.getJSONObject(index).getString("id") != profileId) next.put(entries.getJSONObject(index))
        require(next.length() < 20)
        next.put(JSONObject().put("id", profileId).put("name", name).put("updated", System.currentTimeMillis()).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        value.put("profiles", next)
        if (value.optString("selectedProfile") == profileId) { value.put("enabled", false).put("profile", Base64.encodeToString(bytes, Base64.NO_WRAP)) }
        write(value)
        publish(value)
        profileId
    } }
    suspend fun profile(id: String): ByteArray = withContext(Dispatchers.IO) { mutex.withLock {
        val entries = requireNotNull(read()).getJSONArray("profiles")
        val entry = (0 until entries.length()).map { entries.getJSONObject(it) }.first { it.getString("id") == id }
        Base64.decode(entry.getString("data"), Base64.NO_WRAP)
    } }
    suspend fun removeProfile(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        requireStopped()
        val value = requireNotNull(read())
        val entries = value.optJSONArray("profiles") ?: JSONArray()
        val next = JSONArray()
        for (index in 0 until entries.length()) if (entries.getJSONObject(index).getString("id") != id) next.put(entries.getJSONObject(index))
        value.put("profiles", next)
        if (value.optString("selectedProfile") == id) {
            value.put("enabled", false).put("profile", "").put("selectedProfile", "")
            db.consentDao().routingRecords().filter { it.feature.startsWith("routing-v1:$userId:") }.forEach { db.consentDao().upsert(it.copy(revokedAt = System.currentTimeMillis())) }
            value.put("bindings", JSONObject())
        }
        write(value); publish(value)
    } }
    fun affected(pkg: String?) = _state.value.enabled && pkg in _state.value.apps

    private suspend fun snapshot(value: JSONObject): RoutingSnapshot {
        geoIp.load()
        require(value.getInt("user") == userId)
        val profile = Base64.decode(value.getString("profile"), Base64.NO_WRAP)
        require(validate(profile))
        val bindings = value.getJSONObject("bindings")
        require(bindings.length() in 1..1000)
        val owners = bindings.keys().asSequence().toList().map { pkg ->
            val signer = bindings.getString(pkg)
            require(identity(pkg) == signer)
            require(db.blockRuleDao().consent(feature(pkg, signer))?.let { it.statementVersion == 1 && it.revokedAt == null } == true)
            "$userId:$pkg"
        }.sorted().joinToString("\n")
        return RoutingSnapshot(profile, owners)
    }

    private fun identity(pkg: String): String? = runCatching {
        require(pkg != context.packageName && BlockTargets.packageName(pkg))
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val uid = requireNotNull(info.applicationInfo).uid
        require(uid / 100_000 == userId && pm.getPackagesForUid(uid)?.size == 1)
        requireNotNull(info.signingInfo).apkContentsSigners.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().also { require(it.isNotEmpty()) }.joinToString(",")
    }.getOrNull()

    private fun feature(pkg: String, signer: String) = "routing-v1:$userId:$pkg:$signer"
    private fun requireStopped() = check(capture.ui.value.phase in setOf(CapturePhase.IDLE, CapturePhase.ERROR))

    private fun publish(value: JSONObject?) {
        _state.value = if (value == null) RoutingUi() else RoutingUi(value.optString("profile").isNotEmpty(), value.getBoolean("enabled"), value.getJSONObject("bindings").keys().asSequence().toSet())
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun read(): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val encrypted = file.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= 70 * 1024 * 1024)
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        require(encrypted.size in 29..70 * 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(encrypted, 12, encrypted.size - 12), Charsets.UTF_8))
    }

    private fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val encrypted = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        require(encrypted.size <= 70 * 1024 * 1024)
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) } catch (error: Throwable) { file.failWrite(output); throw error }
    }
}

data class RoutingProfile(val id: String, val name: String, val updated: Long, val selected: Boolean)

