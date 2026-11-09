package com.nathanhanapps.wayd.enrich

import com.nathanhanapps.wayd.core.model.TrackerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerDbTest {
    private val db = TrackerDb()

    @Test fun exactAndSuffixMatch() {
        assertEquals(DomainInfo("Baidu", TrackerCategory.UNKNOWN), db.lookup("www.baidu.com"))
        assertEquals(DomainInfo("Tencent", TrackerCategory.UNKNOWN), db.lookup("szextshort.weixin.qq.com"))
    }

    @Test fun specificSubdomainBeatsParent() {
        assertEquals(TrackerCategory.ANALYTICS, db.lookup("hm.baidu.com")?.category)
        assertEquals(TrackerCategory.ANALYTICS, db.lookup("x.hm.baidu.com")?.category)
        assertEquals(TrackerCategory.UNKNOWN, db.lookup("map.baidu.com")?.category) // falls back to baidu.com
    }

    @Test fun caseAndTrailingDotAreNormalised() {
        assertEquals("Google", db.lookup("Fcm.GoogleAPIs.com.")?.company)
        assertEquals(TrackerCategory.PUSH, db.lookup("fcm.googleapis.com")?.category)
    }

    @Test fun unknownAndLookalikesAreNull() {
        assertNull(db.lookup(null))
        assertNull(db.lookup(""))
        assertNull(db.lookup("example.org"))
        assertNull(db.lookup("notbaidu.com")) // suffix match must respect label boundaries
        assertNull(db.lookup("baidu.com.evil.example"))
    }

    @Test fun seedHasNoDuplicateDomains() {
        val dup = SeedDomains.all.groupBy { it.domain }.filter { it.value.map { e -> e.company to e.category }.distinct().size > 1 }
        assertTrue("conflicting duplicates: ${dup.keys}", dup.isEmpty())
    }

    @Test fun doh() {
        assertTrue(DohHosts.isDoh("dns.google"))
        assertTrue(DohHosts.isDoh("a.dns.alidns.com"))
        assertFalse(DohHosts.isDoh("google.com"))
        assertFalse(DohHosts.isDoh(null))
    }

    @Test fun privateAddresses() {
        listOf("10.1.2.3", "192.168.31.52", "172.16.0.1", "172.31.255.1", "127.0.0.1", "169.254.1.1", "fe80::1", "fd00::1")
            .forEach { assertTrue(it, PrivateAddr.isPrivate(it)) }
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "2408:8571::1", "93.184.216.34")
            .forEach { assertFalse(it, PrivateAddr.isPrivate(it)) }
    }
}
