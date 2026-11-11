package com.nathanhanapps.wayd.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nathanhanapps.wayd.core.db.BlockRuleDao
import com.nathanhanapps.wayd.core.db.ConsentDao
import com.nathanhanapps.wayd.core.db.ConsentRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Which engine answers "Analyse with AI". The external one only ever runs when the user chose it and acknowledged the risk. */
enum class AiProvider { ON_DEVICE, EXTERNAL }

data class ExternalAiUi(
    val baseUrl: String = "",
    val model: String = "",
    val hasKey: Boolean = false,
    /** The user accepted that analysis text leaves the phone for this server. Recorded per server origin. */
    val acknowledged: Boolean = false,
    val provider: AiProvider = AiProvider.ON_DEVICE,
) {
    val configured get() = baseUrl.isNotBlank() && model.isNotBlank()
    val usable get() = configured && acknowledged
    val host get() = ExternalAiUrl.host(baseUrl)
}

/**
 * Settings for the optional OpenAI-compatible provider (AGENTS.md rule 1, D-046). Off by default. The API key is stored only
 * as Keystore-encrypted ciphertext; the acknowledgement is a revocable `ConsentRecord` bound to the server's origin, so
 * pointing the app at a different server asks again. Nothing here talks to the network.
 */
@Singleton
class ExternalAiSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consents: ConsentDao,
    private val records: BlockRuleDao,
) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("ai_secret", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read(false))
    val state: StateFlow<ExternalAiUi> = _state.asStateFlow()
    private val warnings = ExternalWarningPolicy(records::consent, consents::upsert, { consents.observeAll().first() })

    /** Re-reads stored values and the consent record (call when a settings screen opens). */
    suspend fun load() = withContext(Dispatchers.IO) { _state.value = read(isAcknowledged(prefs.getString(KEY_URL, "").orEmpty())) }

    /**
     * Stores the server. [apiKey]: null keeps the stored key, blank removes it, otherwise replaces it. Returns false for an address
     * that is not allowed (see [ExternalAiUrl]). Changing the origin revokes the acknowledgement and switches back to on-device.
     */
    suspend fun save(baseUrl: String, model: String, apiKey: String?): Boolean = withContext(Dispatchers.IO) {
        val url = ExternalAiUrl.normalise(baseUrl) ?: return@withContext false
        val oldOrigin = ExternalAiUrl.origin(prefs.getString(KEY_URL, "").orEmpty())
        if (url != prefs.getString(KEY_URL, "") || model.trim().take(200) != prefs.getString(KEY_MODEL, "")) warnings.reset()
        if (oldOrigin.isNotEmpty() && oldOrigin != ExternalAiUrl.origin(url)) revoke(oldOrigin)
        prefs.edit().putString(KEY_URL, url).putString(KEY_MODEL, model.trim().take(200)).apply()
        when {
            apiKey == null -> Unit
            apiKey.isBlank() -> secrets.edit().remove(KEY_SECRET).apply()
            else -> secrets.edit().putString(KEY_SECRET, encrypt(apiKey.trim())).apply()
        }
        if (oldOrigin != ExternalAiUrl.origin(url)) prefs.edit().putString(KEY_PROVIDER, AiProvider.ON_DEVICE.name).apply()
        _state.value = read(isAcknowledged(url))
        true
    }

    /** Records (or withdraws) the acknowledgement that analysis text is sent to this server. */
    suspend fun setAcknowledged(on: Boolean) = withContext(Dispatchers.IO) {
        val url = prefs.getString(KEY_URL, "").orEmpty()
        val origin = ExternalAiUrl.origin(url)
        if (origin.isEmpty()) return@withContext
        if (on) consents.upsert(ConsentRecord(feature(origin), STATEMENT, System.currentTimeMillis(), null))
        else {
            revoke(origin)
            warnings.reset()
            prefs.edit().putString(KEY_PROVIDER, AiProvider.ON_DEVICE.name).apply()
        }
        _state.value = read(on)
    }

    fun setProvider(provider: AiProvider) {
        if (provider == AiProvider.EXTERNAL && !_state.value.usable) return
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
        _state.value = _state.value.copy(provider = provider)
    }

    /** Forgets the server, the key and the acknowledgement. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        warnings.reset()
        val origin = ExternalAiUrl.origin(prefs.getString(KEY_URL, "").orEmpty())
        if (origin.isNotEmpty()) revoke(origin)
        prefs.edit().remove(KEY_URL).remove(KEY_MODEL).remove(KEY_PROVIDER).apply()
        secrets.edit().clear().apply()
        _state.value = ExternalAiUi()
    }

    /** The endpoint to use right now, or null. Consent is re-read from the database every time, so a revoked one cannot be used. */
    suspend fun usableEndpoint(): ExternalEndpoint? = withContext(Dispatchers.IO) {
        val url = ExternalAiUrl.normalise(prefs.getString(KEY_URL, "").orEmpty()) ?: return@withContext null
        val model = prefs.getString(KEY_MODEL, "").orEmpty()
        if (model.isBlank() || !isAcknowledged(url)) return@withContext null
        ExternalEndpoint(url, model, apiKey())
    }

    /** Current scope only: a remembered preview can never substitute for a live server acknowledgement. */
    suspend fun warningScope(send: ExternalSend): ExternalWarningScope? = withContext(Dispatchers.IO) {
        val url = ExternalAiUrl.normalise(prefs.getString(KEY_URL, "").orEmpty()) ?: return@withContext null
        val model = prefs.getString(KEY_MODEL, "").orEmpty()
        if (prefs.getString(KEY_PROVIDER, "") != AiProvider.EXTERNAL.name || model != send.model ||
            url != send.destination || !isAcknowledged(url)
        ) return@withContext null
        ExternalWarningScope(url, model)
    }

    suspend fun skipsWarning(scope: ExternalWarningScope): Boolean = withContext(Dispatchers.IO) { warnings.skips(scope) }

    suspend fun rememberWarning(scope: ExternalWarningScope) = withContext(Dispatchers.IO) { warnings.remember(scope) }

    /** Restores every optional preview, without touching provider configuration, keys or dangerous-feature consents. */
    suspend fun reenableAllWarnings() = withContext(Dispatchers.IO) { warnings.reset() }

    /**
     * For listing models / "Test connection" before anything is acknowledged. A key the user typed goes only to the address they
     * typed it for; the stored key is used only when the address is the one it was saved for, never for a different host.
     */
    fun draft(baseUrl: String, model: String, typedKey: String?): ExternalEndpoint? {
        val url = ExternalAiUrl.normalise(baseUrl) ?: return null
        val key = ExternalAiUrl.keyFor(url, prefs.getString(KEY_URL, "").orEmpty(), typedKey, apiKey())
        return ExternalEndpoint(url, model.trim(), key)
    }

    private fun apiKey(): String? = secrets.getString(KEY_SECRET, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    private suspend fun isAcknowledged(url: String): Boolean {
        val origin = ExternalAiUrl.origin(url)
        if (origin.isEmpty()) return false
        return records.consent(feature(origin))?.let { it.statementVersion == STATEMENT && it.revokedAt == null } == true
    }

    private suspend fun revoke(origin: String) {
        records.consent(feature(origin))?.let { consents.upsert(it.copy(revokedAt = System.currentTimeMillis())) }
    }

    private fun read(acknowledged: Boolean) = ExternalAiUi(
        baseUrl = prefs.getString(KEY_URL, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        hasKey = secrets.contains(KEY_SECRET),
        acknowledged = acknowledged,
        provider = runCatching { AiProvider.valueOf(prefs.getString(KEY_PROVIDER, null).orEmpty()) }.getOrDefault(AiProvider.ON_DEVICE),
    )

    private fun feature(origin: String) = "ai-external-v1:$origin"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
        return String(cipher.doFinal(data, 12, data.size - 12))
    }

    private companion object {
        const val KEY_URL = "ext_url"
        const val KEY_MODEL = "ext_model"
        const val KEY_PROVIDER = "provider"
        const val KEY_SECRET = "ext_key"
        const val ALIAS = "wayd-ai-external-v1"
        const val GCM = "AES/GCM/NoPadding"
        /** Version of the warning text the user acknowledged. Bump when the wording changes materially. */
        const val STATEMENT = 1
    }
}
