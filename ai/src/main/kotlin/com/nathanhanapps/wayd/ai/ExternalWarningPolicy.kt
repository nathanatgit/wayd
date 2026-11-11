package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.db.ConsentRecord
import java.security.MessageDigest

/** Remembered preview choice is bound to the full server address and model, independently of server consent. */
data class ExternalWarningScope(val baseUrl: String, val model: String) {
    val feature: String get() {
        val bytes = "${baseUrl.length}:$baseUrl$model".toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ExternalWarningPolicy.PREFIX + hash
    }
}

/** Versioned, revocable ConsentRecords; resetting previews never changes any other consent or rule. */
class ExternalWarningPolicy(
    private val read: suspend (String) -> ConsentRecord?,
    private val write: suspend (ConsentRecord) -> Unit,
    private val all: suspend () -> List<ConsentRecord>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun skips(scope: ExternalWarningScope): Boolean = read(scope.feature)?.let {
        it.statementVersion == 1 && it.revokedAt == null
    } == true

    suspend fun remember(scope: ExternalWarningScope) = write(ConsentRecord(scope.feature, 1, clock(), null))

    suspend fun reset() {
        val now = clock()
        all().filter { it.feature.startsWith(PREFIX) && it.revokedAt == null }.forEach { write(it.copy(revokedAt = now)) }
    }

    companion object {
        const val PREFIX = "ai-external-auto-v1:"
    }
}
