package com.nathanhanapps.wayd.capture

import org.junit.Assert.*
import org.junit.Test

class PolicyBackupTest {
    private val rule = PortableBlockRule("9ce10f14-7d24-4496-b8b1-d5963450c027", 2, false,
        "org.example.app", listOf("a".repeat(64)), "HOST", "metrics.example.com")
    private fun encoded() = PolicyBackup.encode(listOf(rule)).toString(Charsets.UTF_8)
    private fun reject(text: String) {
        try { PolicyBackup.decode(text.toByteArray()); fail("Accepted invalid policy") } catch (_: Exception) { }
    }

    @Test fun exactTargetsAndDisabledStateRoundTrip() {
        val rules = listOf(rule, rule.copy(id = "9ce10f14-7d24-4496-b8b1-d5963450c028", kind = "APP", target = "*", enabled = true),
            rule.copy(id = "9ce10f14-7d24-4496-b8b1-d5963450c029", kind = "IP", target = "2001:db8::1"))
        assertEquals(rules, PolicyBackup.decode(PolicyBackup.encode(rules)))
        assertFalse(encoded().contains("ConsentRecord"))
    }

    @Test fun redactedTargetsRemainExplicitlyIncomplete() {
        val redacted = rule.copy(target = null, redacted = true)
        assertEquals(listOf(redacted), PolicyBackup.decode(PolicyBackup.encode(listOf(redacted))))
        reject(encoded().replace("\"redacted\":false", "\"redacted\":true"))
        reject(encoded().replace("\"metrics.example.com\"", "null"))
    }

    @Test fun duplicateDecodedKeysAndIdsAreRejected() {
        reject(encoded().replace("\"revision\":2", "\"revision\":2,\"revision\":3"))
        reject(encoded().replace("\"revision\":2", "\"revision\":2,\"revi\\u0073ion\":3"))
        val one = encoded().substringAfter("\"rules\":[").dropLast(2)
        reject("{\"format\":\"wayd-policy\",\"schemaVersion\":1,\"rules\":[$one,$one]}")
    }

    @Test fun unsupportedActionsSchemaScopeAndInjectedGrantsAreRejected() {
        reject(encoded().replace("\"BLOCK\"", "\"SCRIPT\""))
        reject(encoded().replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        reject(encoded().replace("\"persistent\"", "\"session\""))
        reject(encoded().replace("\"hostExact\"", "\"hostRegex\""))
        reject(encoded().replace("\"enabled\":false", "\"enabled\":false,\"consent\":true"))
        reject(encoded().replace("\"org.example.app\"", "\"org.example.app\\nexec\""))
    }

    @Test fun strictJsonRejectsPermissiveSyntaxAndTrailingData() {
        for (text in listOf(encoded() + "{}", encoded().replace("\"revision\":2", "\"revision\":02"),
            encoded().replace("\"revision\":2", "\"revision\":2.0"), encoded().replace("\"revision\":2", "\"revision\":2e0"),
            encoded().replace("\"revision\":2", "\"revision\":9223372036854775808"),
            encoded().replace("\"rules\":[", "\"rules\":[/* comment */"), encoded().replace("\"revision\":2", "'revision':2"),
            encoded().replace("\"enabled\":false", "\"enabled\":false,"))) reject(text)
    }

    @Test fun malformedUtf8SurrogatesAndDepthAreRejected() {
        try { PolicyBackup.decode(byteArrayOf(0xc0.toByte(), 0x80.toByte())); fail() } catch (_: Exception) { }
        reject(encoded().replace("metrics.example.com", "\\ud800"))
        reject("[".repeat(20) + "0" + "]".repeat(20))
        reject(encoded().replace("metrics.example.com", "a".repeat(1025)))
    }

    @Test fun inputAndRuleCountCapsAreEnforced() {
        try { PolicyBackup.decode(ByteArray(PolicyBackup.MAX_BYTES + 1)); fail() } catch (_: Exception) { }
        reject("{\"format\":\"wayd-policy\",\"schemaVersion\":1,\"rules\":[" + List(1001) { "{}" }.joinToString(",") + "]}")
    }
}
