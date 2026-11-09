package com.nathanhanapps.wayd.research

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.security.KeyChain
import com.nathanhanapps.wayd.capture.InterceptGate
import com.nathanhanapps.wayd.capture.InterceptHook
import com.nathanhanapps.wayd.capture.InterceptProvider
import com.nathanhanapps.wayd.capture.MitmConfig
import com.nathanhanapps.wayd.core.db.ConsentDao
import com.nathanhanapps.wayd.core.db.ConsentRecord
import com.nathanhanapps.wayd.core.db.InterceptApp
import com.nathanhanapps.wayd.core.db.InterceptAppDao
import com.nathanhanapps.wayd.core.db.MessageDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

data class MitmState(
    val consented: Boolean = false,
    val enabled: Boolean = false,
    val caReady: Boolean = false,
    val systemCa: Boolean = false,
    val apps: Set<String> = emptySet(),
    val busy: Boolean = false,
    /** Last result of a root action, shown verbatim to the user. */
    val message: String? = null,
)

/**
 * Everything about decrypting HTTPS (research flavor): consent, the CA, the per-app allowlist, the optional
 * system-store install. Capture reads it through [InterceptProvider]; nothing happens unless the user consented,
 * switched it on, and picked apps.
 */
@Singleton
class MitmController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consents: ConsentDao,
    private val appDao: InterceptAppDao,
    private val messages: MessageDao,
    private val hook: InterceptHook,
) : InterceptProvider {
    private val prefs = context.getSharedPreferences("mitm", Context.MODE_PRIVATE)
    private val ca = CaStore(context)
    private val system = SystemCa(context)
    private val _state = MutableStateFlow(MitmState())
    val state: StateFlow<MitmState> = _state.asStateFlow()
    @Volatile private var allowed: Set<String> = emptySet()

    /** Called once at app start. */
    fun install() {
        hook.provider = this
        runBlocking { refresh() }
    }

    private suspend fun refresh(message: String? = _state.value.message) {
        val apps = appDao.packages().toSet()
        allowed = apps
        val name = ca.storeName()
        _state.value = MitmState(
            consented = prefs.getInt(KEY_CONSENT, 0) == STATEMENT_VERSION,
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            caReady = ca.exists(),
            systemCa = prefs.getBoolean(KEY_SYSTEM_CA, false) && name != null,
            apps = apps,
            message = message,
        )
    }

    // ---- InterceptProvider (used by the capture service) ----

    override fun config(): MitmConfig? {
        if (!prefs.getBoolean(KEY_ENABLED, false) || prefs.getInt(KEY_CONSENT, 0) != STATEMENT_VERSION) return null
        val local = ca.load() ?: return null
        allowed = runBlocking { appDao.packages().toSet() }
        return MitmConfig(local.certPem, local.keyPem, MitmExclusions.hostSuffixes)
    }

    override fun gate() = InterceptGate { pkg, _, _ -> pkg in allowed && !MitmExclusions.isExcludedPackage(pkg) }

    // ---- user actions ----

    /** The user accepted the warning and the privacy statement: creates the CA and records consent. */
    suspend fun consentAndCreateCa(): Boolean {
        if (ca.create() == null) return false
        consents.upsert(ConsentRecord(FEATURE, STATEMENT_VERSION, System.currentTimeMillis(), null))
        prefs.edit().putInt(KEY_CONSENT, STATEMENT_VERSION).putBoolean(KEY_ENABLED, true).apply()
        refresh(null)
        return true
    }

    suspend fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        refresh(null)
    }

    suspend fun setApp(pkg: String, on: Boolean) {
        if (on) {
            if (MitmExclusions.isExcludedPackage(pkg)) return
            appDao.add(InterceptApp(pkg, System.currentTimeMillis()))
        } else {
            appDao.remove(pkg)
        }
        refresh()
    }

    /** Optional root step; the UI shows a warning first. Volatile: gone after a reboot. */
    suspend fun installSystemCa() {
        val pem = ca.certPem()
        val name = ca.storeName()
        if (pem == null || name == null) return
        _state.value = _state.value.copy(busy = true)
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { system.install(pem, name) }
        prefs.edit().putBoolean(KEY_SYSTEM_CA, r is SystemCa.Result.Ok).apply()
        refresh((r as? SystemCa.Result.Failed)?.detail?.ifBlank { "root denied" })
    }

    suspend fun removeSystemCa() {
        _state.value = _state.value.copy(busy = true)
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { system.remove() }
        if (r is SystemCa.Result.Ok) prefs.edit().putBoolean(KEY_SYSTEM_CA, false).apply()
        refresh((r as? SystemCa.Result.Failed)?.detail)
    }

    /** Intent that asks Android to install the CA as a user certificate (the user confirms in system settings). */
    fun userInstallIntent(): Intent? {
        val pem = ca.certPem() ?: return null
        return KeyChain.createInstallIntent()
            .putExtra(KeyChain.EXTRA_CERTIFICATE, pem.toByteArray())
            .putExtra(KeyChain.EXTRA_NAME, "WAYD Local CA")
    }

    /** Saves a copy of the CA certificate into Downloads so it can be installed manually. Returns true on success. */
    fun exportCertificate(): Boolean = runCatching {
        val pem = ca.certPem() ?: return false
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "wayd-ca.crt")
            put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
        true
    }.getOrDefault(false)

    suspend fun deleteCapturedContent() {
        messages.deleteAll()
    }

    /** Turns everything off and removes all traces: system mount, CA key, allowlist. Consent is marked revoked. */
    suspend fun disableAndRemove() {
        _state.value = _state.value.copy(busy = true)
        val sysResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (prefs.getBoolean(KEY_SYSTEM_CA, false)) system.remove() else SystemCa.Result.Ok
        }
        ca.delete()
        appDao.clear()
        consents.upsert(
            ConsentRecord(FEATURE, STATEMENT_VERSION, 0, System.currentTimeMillis()),
        )
        prefs.edit().clear().apply()
        refresh((sysResult as? SystemCa.Result.Failed)?.detail)
    }

    companion object {
        const val FEATURE = "MITM"
        /** Bump when the privacy statement text changes; consent must then be given again. */
        const val STATEMENT_VERSION = 1
        private const val KEY_CONSENT = "consent_version"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SYSTEM_CA = "system_ca"
    }
}
