package com.nathanhanapps.wayd.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsRuleParserTest {
    private fun parse(vararg lines: String) = DnsRuleParser.parse(lines.asSequence())

    @Test fun daedalusHostsFile() {
        val r = parse(
            "# hosts", "", "127.0.0.1 localhost", "::1 localhost ip6-localhost",
            "1.2.3.4 Example.COM www.example.com # aliases", "2001:db8::1\tv6.example.com",
            "0.0.0.0 ads.example.net", ":: ads6.example.net", "127.0.0.1 tracker.example.org",
        )
        assertEquals(listOf(
            "=\texample.com\tA\t1.2.3.4", "=\twww.example.com\tA\t1.2.3.4",
            "=\tv6.example.com\tAAAA\t2001:db8:0:0:0:0:0:1",
            "=\tads.example.net\tBLOCK", "=\tads6.example.net\tBLOCK",
            "=\ttracker.example.org\tA\t127.0.0.1", // kept literally, as Daedalus does
        ), r.lines)
        assertEquals(2, r.skipped) // the two localhost lines
    }

    @Test fun daedalusDnsmasqFile() {
        val r = parse(
            "# dnsmasq", "address=/ads.example.com/", "address=/.tracker.test/0.0.0.0", "address=/cdn.test/10.0.0.1",
            "address=/v6.test/2001:db8::2", "address=/a.test/b.test/10.0.0.2", "address=/blocked.test/#", "local=/local.test/",
        )
        assertEquals(listOf(
            ".\tads.example.com\tBLOCK", ".\ttracker.test\tBLOCK", ".\tcdn.test\tA\t10.0.0.1",
            ".\tv6.test\tAAAA\t2001:db8:0:0:0:0:0:2", ".\ta.test\tA\t10.0.0.2", ".\tb.test\tA\t10.0.0.2",
            ".\tblocked.test\tBLOCK", ".\tlocal.test\tBLOCK",
        ), r.lines)
        assertEquals(0, r.skipped)
    }

    @Test fun unsupportedLinesAreSkippedAndCounted() {
        val r = parse(
            "server=/corp.test/10.0.0.53", "address=/#/0.0.0.0", "address=/com/1.2.3.4", "address=/x.test/not-an-ip",
            "address=x.test/1.2.3.4", "local=/x.test/1.2.3.4", "cache-size=1000", "||adblock.test^", "1.2.3.4", "999.1.1.1 bad.test",
        )
        assertEquals(emptyList<String>(), r.lines)
        assertEquals(10, r.skipped)
    }

    @Test fun typedRuleLines() {
        assertEquals("=\tmy.test\tA\t192.168.1.10", DnsRuleParser.line("My.Test", false, "192.168.1.10"))
        assertEquals(".\tmy.test\tBLOCK", DnsRuleParser.line(".my.test", true, ""))
        assertEquals(".\tmy.test\tBLOCK", DnsRuleParser.line("my.test", true, "0.0.0.0"))
        assertNull(DnsRuleParser.line("localhost", false, "1.2.3.4"))
        assertNull(DnsRuleParser.line("single", true, "1.2.3.4"))
        assertNull(DnsRuleParser.line("my.test", true, "example.com"))
    }
}
