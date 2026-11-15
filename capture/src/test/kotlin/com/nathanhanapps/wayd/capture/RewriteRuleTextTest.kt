package com.nathanhanapps.wayd.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteRuleTextTest {
    private val base = RewriteRule(0, "api.example.com", true, "POST", "PREFIX", "/v1/collect", "REJECT", "204", true, 0)

    @Test fun waydTextRoundTrips() {
        val rules = listOf(
            base, base.copy(subdomains = false, method = "", match = "EXACT"),
            base.copy(action = "HEADER_SET", arg = "User-Agent: generic client"), base.copy(action = "QUERY_REMOVE", arg = "idfa", match = "CONTAINS", pattern = "track"),
            base.copy(action = "REDIRECT", arg = "307 https://example.com/x"), base.copy(match = "REGEX", pattern = "^https?://api\\.example\\.com/v[0-9]+/ads"),
        )
        for (r in rules) assertEquals(r.toString(), r, RewriteRuleText.fromText(RewriteRuleText.toText(r)).rule)
    }

    @Test fun waydTextReportsTheBadLine() {
        assertEquals(2, RewriteRuleText.fromText("host: api.example.com\nnonsense\n").errorLine)
        assertEquals(3, RewriteRuleText.fromText("host: api.example.com\n# comment\naction: explode\n").errorLine)
        assertEquals(0, RewriteRuleText.fromText("host: localhost\naction: reject\nvalue: 204\npattern: /").errorLine) // read, but not valid
        val ok = RewriteRuleText.fromText("# minimal\nhost: Api.Example.com\npattern: /x\naction: reject\nvalue: 200-json").rule!!
        assertEquals("PREFIX", ok.match)
        assertEquals("api.example.com", ok.host)
        assertEquals("", ok.method)
    }

    @Test fun pluginLineIsReadBackByTheImporter() {
        for (r in listOf(
            base, base.copy(subdomains = false, match = "EXACT"), base.copy(action = "HEADER_REMOVE", arg = "X-Device-Id"),
            base.copy(action = "HEADER_SET", arg = "User-Agent: generic client"), base.copy(action = "REDIRECT", arg = "302 https://example.com/new"),
            base.copy(action = "REJECT", arg = "200-array"),
        )) {
            val line = RewriteRuleText.toPlugin(r)!!
            val back = RewriteRuleText.fromPlugin(line)!!
            assertEquals(line, r.host, back.host)
            assertEquals(line, r.subdomains, back.subdomains)
            assertEquals(line, r.action, back.action)
            assertEquals(line, r.arg, back.arg)
            assertEquals("REGEX", back.match)
            assertTrue(Regex(back.pattern).containsMatchIn("https://" + r.host + r.pattern))
        }
        assertEquals("^https?://(?:[^/]+\\.)?api\\.example\\.com/v1/collect reject", RewriteRuleText.toPlugin(base))
        assertNull(RewriteRuleText.fromPlugin("^https?://api\\.example\\.com/x script-response-body x.js"))
    }
}
