package com.nathanhanapps.wayd.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewritePluginParserTest {
    private fun parse(text: String) = RewritePluginParser.parse(text.lineSequence())

    @Test fun importsRejectsRedirectsAndRequestHeaderEdits() {
        val r = parse(
            """
            #!name=Example plugin
            #!desc=demo
            [Rule]
            DOMAIN,ads.example.com,REJECT
            [Rewrite]
            ^https?://api\.example\.com/v\d+/ads reject
            ^https?:\/\/(api|log)\.example\.com\/collect reject-dict
            ^https://cdn.example.net/banner reject-array
            ^https?://api\.example\.com/old 307 https://api.example.com/new
            ^https?://api\.example\.com/track header-del X-Device-Id
            ^https?://api\.example\.com/track header-replace User-Agent generic client
            ^https?://api\.example\.com/q query-del idfa
            ^https?://api\.example\.com/img url reject-img
            [MITM]
            hostname = api.example.com, *.example.net, -skip.example.org
            """.trimIndent(),
        )
        assertEquals("Example plugin", r.name)
        assertEquals(0, r.skipped)
        assertEquals(
            listOf(
                "api.example.com|false|REJECT|204", "example.com|true|REJECT|200-json", "cdn.example.net|false|REJECT|200-array",
                "api.example.com|false|REDIRECT|307 https://api.example.com/new", "api.example.com|false|HEADER_REMOVE|X-Device-Id",
                "api.example.com|false|HEADER_SET|User-Agent: generic client", "api.example.com|false|QUERY_REMOVE|idfa", "api.example.com|false|REJECT|200-empty",
            ),
            r.rules.map { "${it.host}|${it.subdomains}|${it.action}|${it.arg}" },
        )
        assertTrue(r.rules.all { it.match == "REGEX" && it.method.isEmpty() })
        assertEquals(listOf("api.example.com", "example.net"), r.mitmHosts)
    }

    @Test fun skipsWhatTheEngineCannotHonour() {
        val r = parse(
            """
            [Rewrite]
            ^https?://api\.example\.com/a response-body-replace-regex foo bar
            ^https?://api\.example\.com/b mock-response-body data="{}"
            ^https?://api\.example\.com/(?=c)c reject
            ^https?://.*/ads reject
            /no-scheme reject
            ^https?://api\.example\.com/(.*) 302 https://other.example.com/$1
            ^https?://api\.example\.com/d header-del bad name here
            [Script]
            http-response ^https?://api\.example\.com/e script-path=x.js
            """.trimIndent(),
        )
        assertTrue(r.rules.toString(), r.rules.isEmpty())
        assertEquals(8, r.skipped)
    }

    @Test fun hostComesFromTheLiteralPartOfThePattern() {
        assertEquals("api.example.com" to false, RewritePluginParser.host("^https?://api\\.example\\.com/x"))
        assertEquals("example.com" to true, RewritePluginParser.host("^https?://.*\\.example\\.com/x"))
        assertEquals("example.com" to true, RewritePluginParser.host("^https?://[a-z]+pi\\.example\\.com/x")) // never "pi.example.com"
        assertEquals("api.example.com" to false, RewritePluginParser.host("^http:\\/\\/api\\.example\\.com\\/x"))
        assertEquals("api.example.com" to false, RewritePluginParser.host("https://api.example.com"))
        assertNull(RewritePluginParser.host("^https?://[^/]+/ads"))
        assertNull(RewritePluginParser.host("^https?://.*\\.com/ads")) // a bare top-level label is not a host
        assertNull(RewritePluginParser.host("^wss://api\\.example\\.com/x"))
    }
}
