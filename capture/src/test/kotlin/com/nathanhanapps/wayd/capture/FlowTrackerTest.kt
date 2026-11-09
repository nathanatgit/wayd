package com.nathanhanapps.wayd.capture

import com.nathanhanapps.wayd.core.db.DnsRecord
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.NetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class FlowTrackerTest {
    private class Sink : CaptureSink {
        val flows = ArrayList<Flow>()
        val dns = ArrayList<DnsRecord>()
        override fun flow(f: Flow) { flows += f }
        override fun dns(d: DnsRecord) { dns += d }
    }

    private val env = object : CaptureEnv {
        override fun now() = 1_000L
        override fun screenOn() = true
        override fun netType() = NetType.WIFI
    }

    private fun tracker(sink: Sink, owner: Owner? = Owner(10123, "com.example.app")) =
        FlowTracker(1, { _, _, _ -> owner }, sink, env)

    private fun open(id: Long, proto: Int, dst: String, port: Int) = NativeEvent.Open(
        id, proto, 4, 40000, port, InetAddress.getByName("10.111.222.1"), InetAddress.getByName(dst),
    )

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun clientHello(host: String): ByteArray {
        val name = host.toByteArray()
        val sni = ByteArrayOutputStream().apply {
            write(u16(0)); write(u16(name.size + 5)); write(u16(name.size + 3)); write(0); write(u16(name.size)); write(name)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(3, 3)); write(ByteArray(32)); write(0)
            write(u16(2)); write(byteArrayOf(0x13, 0x01)); write(1); write(0)
            write(u16(sni.size)); write(sni)
        }.toByteArray()
        val hs = byteArrayOf(1, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
        return byteArrayOf(0x16, 3, 1, (hs.size shr 8).toByte(), hs.size.toByte()) + hs
    }

    private fun dnsResponse(name: String, ip: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
        name.split('.').forEach { o.write(it.length); o.write(it.toByteArray()) }
        o.write(0); o.write(byteArrayOf(0, 1, 0, 1))
        o.write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)); o.write(ip)
        return o.toByteArray()
    }

    @Test fun tlsFlow_getsSniHostAndOwner() {
        val sink = Sink()
        val t = tracker(sink)
        t.onEvent(open(1, 6, "93.184.216.34", 443))
        t.onEvent(NativeEvent.Data(1, false, clientHello("tracker.example.com")))
        t.onEvent(NativeEvent.Close(1, 700, 9000))
        val f = sink.flows.single()
        assertEquals("tracker.example.com", f.host)
        assertEquals(HostSource.SNI, f.hostSource)
        assertEquals("com.example.app", f.pkg)
        assertEquals(10123, f.uid)
        assertEquals(700L, f.bytesUp)
        assertEquals("TLS1.2", f.tlsVersion)
        assertEquals(false, f.appForeground) // never guessed
    }

    @Test fun dnsAnswerFillsHostForLaterFlow() {
        val sink = Sink()
        val t = tracker(sink)
        t.onEvent(open(1, 17, "8.8.8.8", 53))
        t.onEvent(NativeEvent.Data(1, true, dnsResponse("cdn.example.org", byteArrayOf(1, 2, 3, 4))))
        t.onEvent(NativeEvent.Close(1, 40, 80))
        t.onEvent(open(2, 6, "1.2.3.4", 8080)) // no SNI/HTTP seen
        t.onEvent(NativeEvent.Close(2, 10, 20))
        assertEquals("cdn.example.org", sink.dns.single().qname)
        assertEquals("[\"1.2.3.4\"]", sink.dns.single().answersJson)
        val f = sink.flows.last()
        assertEquals("cdn.example.org", f.host)
        assertEquals(HostSource.DNS, f.hostSource)
    }

    @Test fun httpHostIsUsedWhenNotTls() {
        val sink = Sink()
        val t = tracker(sink)
        t.onEvent(open(1, 6, "5.6.7.8", 80))
        t.onEvent(NativeEvent.Data(1, false, "GET /a HTTP/1.1\r\nHost: Ads.Example\r\n\r\n".toByteArray()))
        t.onEvent(NativeEvent.Close(1, 1, 2))
        assertEquals("ads.example", sink.flows.single().host)
        assertEquals(HostSource.HTTP_HOST, sink.flows.single().hostSource)
    }

    @Test fun unresolvedOwnerIsMarkedUnknown_notGuessed() {
        val sink = Sink()
        val t = tracker(sink, owner = null)
        t.onEvent(open(1, 6, "5.6.7.8", 443))
        t.onEvent(NativeEvent.Close(1, 1, 2))
        assertEquals(FlowTracker.UNKNOWN_PKG, sink.flows.single().pkg)
        assertEquals(-1, sink.flows.single().uid)
        assertNull(sink.flows.single().host)
    }

    @Test fun finishAllFlushesOpenFlows() {
        val sink = Sink()
        val t = tracker(sink)
        t.onEvent(open(1, 6, "5.6.7.8", 443))
        t.finishAll()
        assertEquals(1, sink.flows.size)
        assertEquals(1, t.stats().flows)
    }

    @Test fun decoder_roundTripsAndIgnoresGarbage() {
        // OPEN: 4-byte len | type | id | proto ver sport dport src[16] dst[16]
        val body = ByteArray(38).also {
            it[0] = 6; it[1] = 4; it[2] = 0x9C.toByte(); it[3] = 0x40; it[4] = 0x01; it[5] = 0xBB.toByte()
            it[6] = 10; it[9] = 1
            it[22] = 93.toByte(); it[23] = 184.toByte(); it[24] = 216.toByte(); it[25] = 34
        }
        val total = 13 + body.size
        val ev = byteArrayOf(0, 0, 0, total.toByte(), 1, 0, 0, 0, 0, 0, 0, 0, 7) + body
        val decoded = NativeEvents.decode(ev + byteArrayOf(0, 0, 0, 99), ev.size + 4)
        val o = decoded.single() as NativeEvent.Open
        assertEquals(7L, o.flowId)
        assertEquals(443, o.dstPort)
        assertEquals("93.184.216.34", o.dstIp.hostAddress)
        assertEquals(emptyList<NativeEvent>(), NativeEvents.decode(ByteArray(5), 5))
    }
}
