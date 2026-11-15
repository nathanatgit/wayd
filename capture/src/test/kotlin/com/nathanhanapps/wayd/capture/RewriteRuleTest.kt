package com.nathanhanapps.wayd.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteRuleTest {
    private val base = RewriteRule(7, "api.example.com", true, "post", "PREFIX", "/collect", "REJECT", "204", true, 0)

    @Test fun wireLineMatchesTheEngineFormat() {
        assertTrue(base.valid())
        assertEquals("7\t0:com.example.app\t.api.example.com\tPOST\tPREFIX\t/collect\tREJECT\t204", base.line(0, "com.example.app"))
        assertEquals("api.example.com", base.copy(subdomains = false).line(10, "com.example.app").split("\t")[2])
    }

    @Test fun rejectsWhatTheEngineWouldReject() {
        for (bad in listOf(
            base.copy(host = "Api.Example.com"), base.copy(host = "localhost"), base.copy(host = "1.2.3.4"),
            base.copy(method = "GE T"), base.copy(match = "GLOB"), base.copy(pattern = ""), base.copy(pattern = "/a\tb"),
            base.copy(arg = "500"), base.copy(action = "HEADER_REMOVE", arg = "bad name"), base.copy(action = "HEADER_SET", arg = "NoColon"),
            base.copy(action = "HEADER_SET", arg = "X-A: a\rb"), base.copy(action = "SCRIPT", arg = "x"),
        )) assertFalse(bad.toString(), bad.valid())
        assertTrue(base.copy(action = "HEADER_SET", arg = "User-Agent: generic client").valid())
        assertTrue(base.copy(action = "QUERY_REMOVE", arg = "idfa", method = "").valid())
        assertTrue(base.copy(action = "REDIRECT", arg = "307 https://example.com/x").valid())
        assertFalse(base.copy(action = "REDIRECT", arg = "301 https://example.com/x").valid())
        assertTrue(base.copy(match = "REGEX", pattern = "^https?://api[.]example[.]com/v[0-9]+/ads").valid())
        assertFalse(base.copy(match = "REGEX", pattern = "(?=x)y").valid())
        assertFalse(base.copy(match = "REGEX", pattern = "(unclosed").valid())
    }

    @Test fun oneSetExpandsToOneLinePerRuleAndApp() {
        val set = RewriteSet(1, "s", true, listOf(base, base.copy(id = 8, enabled = false)), listOf(RewriteApp("com.a", true), RewriteApp("com.b", true), RewriteApp("com.c", false)))
        assertEquals(2, set.lineCount)
        assertEquals(0, set.copy(enabled = false).lineCount)
    }
}
