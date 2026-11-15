package com.nathanhanapps.wayd.capture

import com.nathanhanapps.wayd.core.model.HostSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsIpMapTest {
    private val min = 60_000L

    @Test fun freshDnsWinsThenLearnedNameThenStaleDns() {
        val m = DnsIpMap()
        assertNull(m.resolve("1.2.3.4", 0))
        m.put("1.2.3.4", "cdn.example.com", now = 0, ttlSeconds = 30)
        assertEquals("cdn.example.com" to HostSource.DNS, m.resolve("1.2.3.4", 4 * min)) // short TTLs get a 5-minute floor
        m.learn("1.2.3.4", "api.example.com", 10 * min)
        assertEquals("api.example.com" to HostSource.INFERRED, m.resolve("1.2.3.4", 20 * min)) // DNS answer is stale, a seen name is better
        m.put("1.2.3.4", "new.example.com", now = 30 * min, ttlSeconds = 3600)
        assertEquals("new.example.com" to HostSource.DNS, m.resolve("1.2.3.4", 60 * min))
        // After a day the learned name expires; the last DNS answer is still used, as before.
        assertEquals("new.example.com" to HostSource.DNS, m.resolve("1.2.3.4", 30 * min + 25 * 3_600_000L))
    }

    @Test fun learnedNameAloneNamesUnnamedTrafficAndKeepsTheNewest() {
        val m = DnsIpMap()
        m.learn("5.6.7.8", "old.example.com", 100)
        m.learn("5.6.7.8", "quic.example.com", 200)
        m.learn("5.6.7.8", "older.example.com", 50) // seeding order does not matter
        assertEquals("quic.example.com" to HostSource.INFERRED, m.resolve("5.6.7.8", 1_000))
        assertNull(m.resolve("5.6.7.8", 200 + DnsIpMap.LEARNED_MS + 1))
        assertEquals("quic.example.com", m.get("5.6.7.8"))
    }
}
