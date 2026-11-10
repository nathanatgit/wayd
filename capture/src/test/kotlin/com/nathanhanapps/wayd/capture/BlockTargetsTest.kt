package com.nathanhanapps.wayd.capture

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BlockTargetsTest {
    @Test fun hostsAreTypedAndNormalized() {
        assertEquals("metrics.example.com", BlockTargets.host("Metrics.Example.COM."))
        assertEquals("xn--fiqs8s.example", BlockTargets.host("中国.example"))
        assertEquals("example.com", BlockTargets.httpHost("Example.COM:8080"))
        assertNull(BlockTargets.httpHost("example.com:65536"))
        assertNull(BlockTargets.httpHost("user@example.com:443"))
        for (bad in listOf("", "*.example.com", "https://example.com/", "example..com", "evil\r\nHost", "-bad.example", "192.0.2.1")) assertNull(bad, BlockTargets.host(bad))
    }

    @Test fun addressesCannotTriggerHostnameResolution() {
        assertEquals("192.0.2.1", BlockTargets.ip("192.0.2.1"))
        assertNotNull(BlockTargets.ip("2001:db8::1"))
        for (bad in listOf("example.com", "127.1", "999.0.0.1", "01.2.3.4", "fe80::1%wlan0", "::http")) assertNull(bad, BlockTargets.ip(bad))
    }

    @Test fun policyDecoderPreservesSignedSessionIdAndRejectsMalformedEvent() {
        val event = ByteBuffer.allocate(30).putInt(30).put(7).putLong(4).put(1).putLong(12).putLong(-2).array()
        val decoded = NativeEvents.decode(event, event.size).single() as NativeEvent.Policy
        assertEquals(12L, decoded.revision)
        assertEquals(-2L, decoded.ruleId)
        assertTrue(NativeEvents.decode(event.copyOf(29), 29).isEmpty())
        event[13] = 99
        assertTrue(NativeEvents.decode(event, event.size).isEmpty())
    }
}
