package com.nathanhanapps.wayd.model.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRuleMatcherTest {
    private fun rule(id: Long, pattern: String, action: HostRuleMatcher.Action = HostRuleMatcher.Action.BLOCK, pkg: String? = null, enabled: Boolean = true) =
        HostRuleMatcher.Rule(id, pattern, action, pkg, enabled)

    @Test fun exactAndCaseNormalization() {
        assertEquals(1L, HostRuleMatcher.decide("app", "API.Example.Com.", listOf(rule(1, "api.example.com")))?.ruleId)
        assertNull(HostRuleMatcher.decide("app", "other.example.com", listOf(rule(1, "example.com"))))
    }

    @Test fun wildcardIncludesApexAndSubdomainsButNotLookalikes() {
        val rules = listOf(rule(1, "*.example.com"))
        for (host in listOf("example.com", "api.example.com", "a.b.example.com")) {
            assertEquals(1L, HostRuleMatcher.decide("app", host, rules)?.ruleId)
        }
        for (host in listOf("notexample.com", "example.com.evil.org")) {
            assertNull(HostRuleMatcher.decide("app", host, rules))
        }
    }

    @Test fun exactOverridesWildcardAndSubstring() {
        val rules = listOf(rule(1, "*example*"), rule(2, "*.example.com"), rule(3, "api.example.com"))
        assertEquals(3L, HostRuleMatcher.decide("app", "api.example.com", rules)?.ruleId)
        assertEquals(2L, HostRuleMatcher.decide("app", "example.com", rules)?.ruleId)
    }

    @Test fun scopedRuleWinsAtEqualPatternSpecificity() {
        val rules = listOf(rule(1, "example.com"), rule(2, "example.com", pkg = "app"))
        assertEquals(2L, HostRuleMatcher.decide("app", "example.com", rules)?.ruleId)
        assertEquals(1L, HostRuleMatcher.decide("other", "example.com", rules)?.ruleId)
    }

    @Test fun allowWinsAtEqualSpecificityAndDisabledIsIgnored() {
        val rules = listOf(rule(1, "example.com"), rule(2, "example.com", HostRuleMatcher.Action.ALLOW), rule(3, "example.com", enabled = false))
        assertEquals(HostRuleMatcher.Action.ALLOW, HostRuleMatcher.decide("app", "example.com", rules)?.action)
    }

    @Test fun invalidPatternsNeverMatch() {
        for (pattern in listOf("*", "foo*bar", "re:.*", "", "*.")) assertFalse(HostRuleMatcher.isValidPattern(pattern))
        assertTrue(HostRuleMatcher.isValidPattern("*example*"))
        assertNull(HostRuleMatcher.decide("app", "example.com", listOf(rule(1, "*"))))
    }
}
