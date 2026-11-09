package com.nathanhanapps.wayd.research

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nathanhanapps.wayd.capture.NativeNet
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The local CA used for TLS interception (research flavor). */
class LocalCa(val certPem: String, val keyPem: String)

/**
 * Stores WAYD's CA. The certificate is public; the private key is encrypted with an AES-GCM key held in the Android
 * Keystore and kept in no-backup storage. [delete] removes both the files and the Keystore key (docs/08).
 */
class CaStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "mitm")
    private val certFile = File(dir, "ca.pem")
    private val keyFile = File(dir, "ca.key.enc")

    fun exists() = certFile.exists() && keyFile.exists()

    @Synchronized
    fun create(): LocalCa? {
        val id = ByteArray(3).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val out = NativeNet().nativeGenerateCa("WAYD Local CA - $id")
        val parts = out.split('\u001F')
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        delete()
        dir.mkdirs()
        certFile.writeText(parts[0])
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        keyFile.writeBytes(cipher.iv + cipher.doFinal(parts[1].toByteArray()))
        return LocalCa(parts[0], parts[1])
    }

    @Synchronized
    fun load(): LocalCa? = runCatching {
        if (!exists()) return null
        val blob = keyFile.readBytes()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)))
        LocalCa(certFile.readText(), String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)))
    }.getOrNull()

    fun certPem(): String? = runCatching { certFile.readText() }.getOrNull()

    /** System-store file name for the CA certificate (OpenSSL subject_hash_old), e.g. `f28c7bdf`. */
    fun storeName(): String? = certPem()?.let { NativeNet().nativeSubjectHash(it) }?.takeIf { it.isNotBlank() }

    @Synchronized
    fun delete() {
        certFile.delete()
        keyFile.delete()
        dir.delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "wayd_mitm_ca_wrap"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
