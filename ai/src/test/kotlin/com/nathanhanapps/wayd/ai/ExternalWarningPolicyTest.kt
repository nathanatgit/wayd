package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.db.ConsentRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExternalWarningPolicyTest {
    private val records = mutableMapOf<String, ConsentRecord>()
    private var now = 10L
    private val policy = ExternalWarningPolicy({ records[it] }, { records[it.feature] = it }, { records.values.toList() }, { now })
    private val scope = ExternalWarningScope("https://example.com/v1", "model-a")

    @Test fun asksByDefaultThenRemembersOnlyTheExactServerAndModel() = runBlocking {
        assertFalse(policy.skips(scope))
        policy.remember(scope)
        assertTrue(policy.skips(scope))
        assertFalse(policy.skips(scope.copy(baseUrl = "https://other.example/v1")))
        assertFalse(policy.skips(scope.copy(baseUrl = "http://example.com/v1")))
        assertFalse(policy.skips(scope.copy(baseUrl = "https://example.com:8443/v1")))
        assertFalse(policy.skips(scope.copy(baseUrl = "https://example.com/other")))
        assertFalse(policy.skips(scope.copy(model = "model-b")))
        assertEquals(10L, records.getValue(scope.feature).acceptedAt)
    }

    @Test fun resetRevokesAllPreviewChoicesButLeavesOtherConsentsUntouched() = runBlocking {
        val other = scope.copy(model = "model-b")
        val acknowledgement = ConsentRecord("ai-external-v1:https://example.com", 1, 1L, null)
        val decryption = ConsentRecord("mitm-v1", 1, 1L, null)
        records[acknowledgement.feature] = acknowledgement
        records[decryption.feature] = decryption
        policy.remember(scope)
        policy.remember(other)
        now = 20L
        policy.reset()
        assertFalse(policy.skips(scope))
        assertFalse(policy.skips(other))
        assertEquals(20L, records.getValue(scope.feature).revokedAt)
        assertEquals(10L, records.getValue(scope.feature).acceptedAt)
        assertEquals(acknowledgement, records[acknowledgement.feature])
        assertEquals(decryption, records[decryption.feature])
    }

    @Test fun revokedOrUnknownStatementVersionCannotSkip() = runBlocking {
        records[scope.feature] = ConsentRecord(scope.feature, 99, 1L, null)
        assertFalse(policy.skips(scope))
        records[scope.feature] = ConsentRecord(scope.feature, 1, 1L, 2L)
        assertFalse(policy.skips(scope))
    }

    @Test fun explicitChoiceAfterResetCreatesANewRecordedAcknowledgement() = runBlocking {
        policy.remember(scope)
        policy.reset()
        now = 30L
        policy.remember(scope)
        assertTrue(policy.skips(scope))
        assertEquals(30L, records.getValue(scope.feature).acceptedAt)
        assertNull(records.getValue(scope.feature).revokedAt)
    }
}
