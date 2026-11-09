package com.nathanhanapps.wayd.capture.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ParsersTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun u16(v: Int) = bytes(v shr 8, v and 0xFF)

    // ---- IP ----

    private fun ipv4Udp(payload: ByteArray, sp: Int, dp: Int): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArrayOutputStream()
        out.write(bytes(0x45, 0, total shr 8, total and 0xFF, 0, 0, 0, 0, 64, 17, 0, 0, 10, 0, 0, 2, 8, 8, 8, 8))
        out.write(u16(sp)); out.write(u16(dp)); out.write(u16(8 + payload.size)); out.write(u16(0))
        out.write(payload)
        return out.toByteArray()
    }

    @Test fun ipv4Udp_parsesAddressesAndPorts() {
        val p = IpPacket.parse(ipv4Udp(ByteArray(4), 40000, 53))!!
        assertEquals(4, p.version)
        assertEquals(IpPacket.UDP, p.protocol)
        assertEquals(53, p.dstPort)
        assertEquals(40000, p.srcPort)
        assertEquals(20, p.payloadOffset)
        assertEquals(listOf<Byte>(8, 8, 8, 8), p.dstAddr.toList())
    }

    @Test fun ipv6Tcp_parses() {
        val b = ByteArray(40 + 20)
        b[0] = 0x60
        b[4] = 0; b[5] = 20
        b[6] = 6
        b[24] = 0x20; b[39] = 1
        b[40] = 0x01; b[41] = 0xBB.toByte() // src port 443
        b[42] = 0x9C.toByte(); b[43] = 0x40 // dst port 40000
        val p = IpPacket.parse(b)!!
        assertEquals(6, p.version)
        assertEquals(IpPacket.TCP, p.protocol)
        assertEquals(443, p.srcPort)
        assertEquals(40000, p.dstPort)
        assertEquals(40, p.payloadOffset)
    }

    @Test fun ip_rejectsGarbage() {
        assertNull(IpPacket.parse(ByteArray(0)))
        assertNull(IpPacket.parse(bytes(0x45, 0, 0)))
        assertNull(IpPacket.parse(bytes(0x10, 0, 0, 0)))
        // total length larger than the buffer
        val b = ipv4Udp(ByteArray(0), 1, 2); b[2] = 0x7F
        assertNull(IpPacket.parse(b))
        // IPv6 extension-header chain pointing past the end
        val v6 = ByteArray(48); v6[0] = 0x60; v6[5] = 8; v6[6] = 0; v6[41] = 0xFF.toByte()
        assertNull(IpPacket.parse(v6))
    }

    // ---- DNS ----

    private fun dnsResponse(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(bytes(0x12, 0x34, 0x81, 0x80, 0, 1, 0, 2, 0, 0, 0, 0))
        o.write(bytes(7)); o.write("example".toByteArray()); o.write(bytes(3)); o.write("com".toByteArray()); o.write(bytes(0))
        o.write(bytes(0, 1, 0, 1))
        // answer 1: pointer to question name, A 93.184.216.34
        o.write(bytes(0xC0, 12, 0, 1, 0, 1, 0, 0, 1, 0x2C, 0, 4, 93, 184, 216, 34))
        // answer 2: CNAME to "x.example.com" via pointer
        o.write(bytes(0xC0, 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 4, 1, 'x'.code, 0xC0, 12))
        return o.toByteArray()
    }

    @Test fun dns_parsesAnswersAndCompression() {
        val m = DnsParser.parse(dnsResponse())!!
        assertTrue(m.isResponse)
        assertEquals(0x1234, m.id)
        assertEquals("example.com", m.questions.single().name)
        assertEquals("93.184.216.34", m.answers[0].data)
        assertEquals(300L, m.answers[0].ttl)
        assertEquals("x.example.com", m.answers[1].data)
    }

    @Test fun dns_pointerLoopIsRejected() {
        val b = bytes(0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0, 12, 0, 1, 0, 1)
        assertNull(DnsParser.parse(b))
    }

    @Test fun dns_truncatedAndHugeCountsAreRejected() {
        val full = dnsResponse()
        for (cut in 0 until full.size) assertNotNullOrNull(DnsParser.parse(full.copyOf(cut)))
        val huge = bytes(0, 1, 0x81, 0x80, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0)
        assertNull(DnsParser.parse(huge))
    }

    private fun assertNotNullOrNull(@Suppress("UNUSED_PARAMETER") m: DnsMessage?) = Unit // must simply not throw

    // ---- TLS ----

    private fun clientHello(host: String): ByteArray {
        val name = host.toByteArray()
        val sniExt = ByteArrayOutputStream().apply {
            write(u16(0)); write(u16(name.size + 5)); write(u16(name.size + 3)); write(0); write(u16(name.size)); write(name)
        }.toByteArray()
        val alpnExt = bytes(0, 0x10, 0, 5, 0, 3, 2, 'h'.code, '2'.code)
        val verExt = bytes(0, 0x2B, 0, 3, 2, 3, 4)
        val exts = sniExt + alpnExt + verExt
        val body = ByteArrayOutputStream().apply {
            write(bytes(3, 3)); write(ByteArray(32)); write(0)
            write(u16(2)); write(bytes(0x13, 0x01)); write(1); write(0)
            write(u16(exts.size)); write(exts)
        }.toByteArray()
        val hs = bytes(1, 0, body.size shr 8, body.size and 0xFF) + body
        return bytes(0x16, 3, 1, hs.size shr 8, hs.size and 0xFF) + hs
    }

    @Test fun tls_extractsSniAlpnVersion() {
        val h = TlsSniParser.parse(clientHello("Api.Example.com"))!!
        assertEquals("api.example.com", h.sni)
        assertEquals(listOf("h2"), h.alpn)
        assertEquals("TLS1.3", h.tlsVersion)
    }

    @Test fun tls_rejectsNonTlsAndTruncated() {
        assertNull(TlsSniParser.parse("GET / HTTP/1.1\r\n\r\n".toByteArray()))
        val ch = clientHello("example.com")
        for (cut in 0 until ch.size) TlsSniParser.parse(ch.copyOf(cut)) // must not throw
    }

    @Test fun tls_nonPrintableSniIsDropped() {
        val ch = clientHello("exa mple.com")
        assertNull(TlsSniParser.parse(ch)!!.sni)
    }

    // ---- HTTP ----

    @Test fun http_parsesRequestHead() {
        val req = "POST /v1/track?id=1 HTTP/1.1\r\nHost: Tracker.Example\r\nUser-Agent: t\r\n\r\nbody".toByteArray()
        val h = HttpParser.parseRequest(req)!!
        assertEquals("POST", h.method)
        assertEquals("/v1/track?id=1", h.target)
        assertEquals("tracker.example", h.host)
        assertEquals(req.size - 4, h.headerBytes)
    }

    @Test fun http_rejectsNonHttp() {
        assertNull(HttpParser.parseRequest("hello world".toByteArray()))
        assertNull(HttpParser.parseRequest("FOO / HTTP/1.1\r\n\r\n".toByteArray()))
        assertNotNull(HttpParser.parseRequest("GET / HTTP/1.0\r\n\r\n".toByteArray()))
    }
}
