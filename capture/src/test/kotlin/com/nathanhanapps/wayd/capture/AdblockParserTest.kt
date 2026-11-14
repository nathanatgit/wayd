package com.nathanhanapps.wayd.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdblockParserTest {
    private fun parse(vararg lines: String) = AdblockParser.parse(lines.asSequence())

    @Test fun supportedSubsetBecomesNativeLines() {
        val r = parse(
            "[Adblock Plus 2.0]", "! comment", "# hosts comment", "",
            "||Ads.Example.com^", "||tracker.test^\$important", "@@||ok.tracker.test^", "||scoped.test^\$app=com.a|com.b",
            "0.0.0.0 hosts.example.net other.example.net # note", "127.0.0.1 localhost", "plain.example.org",
        )
        assertEquals(listOf(
            ".\tads.example.com", "!\ttracker.test", "@\tok.tracker.test", ".\tscoped.test\tcom.a|com.b",
            "=\thosts.example.net", "=\tother.example.net", ".\tplain.example.org",
        ), r.lines)
        assertEquals(7, r.applied)
        assertEquals(0, r.skipped)
    }

    @Test fun rulesThatNeedPageContextAreCountedNotGuessed() {
        val r = parse(
            "||ads.example.com^\$third-party", "||example.com/ads/banner.js", "/banner[0-9]+/", "example.com##.ad", "|https://x.example.com^",
            "||*.wild.example^", "@@||ok.test^\$app=com.a", "||bad_host.test^", "||com^", "0.0.0.0 0.0.0.0", "||1.2.3.4^", "||x.test^\$app=not a package",
        )
        assertTrue(r.lines.isEmpty())
        assertEquals(11, r.skipped) // the bare "0.0.0.0 0.0.0.0" hosts line has no host to add and is not a rule
    }

    @Test fun duplicatesCollapse() {
        val r = parse("||a.example^", "||a.example^", "a.example")
        assertEquals(listOf(".\ta.example"), r.lines)
    }
}
