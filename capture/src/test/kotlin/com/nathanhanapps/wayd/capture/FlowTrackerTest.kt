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
    @Test fun recordingOff_keepsPolicyMetadataButStoresNoTraffic() {
        val sink = Sink()
        val mode = TrafficRecording()
        val owners = ArrayList<Long>()
        val hosts = ArrayList<String>()
        val t = FlowTracker(1, { _, _, _ -> Owner(10123, "com.example.app") }, sink, env,
            policyOwner = { id, _ -> owners += id }, policyHost = { _, host -> hosts += host }, recording = { mode.window })
        t.onEvent(open(1, 6, "93.184.216.34", 443))
        t.onEvent(NativeEvent.Data(1, false, clientHello("tracker.example.com")))
        t.onEvent(NativeEvent.Policy(1, 1, 2, 3))
        t.onEvent(NativeEvent.Close(1, 100, 0))
        t.onEvent(open(2, 17, "8.8.8.8", 53))
        t.onEvent(NativeEvent.Data(2, true, dnsResponse("example.org", byteArrayOf(1, 2, 3, 4))))
        t.finishAll()
        assertEquals(listOf(1L, 2L), owners)
        assertEquals(listOf("tracker.example.com"), hosts)
        assertEquals(0, sink.flows.size)
        assertEquals(0, sink.dns.size)
        assertEquals(2, t.stats().flows)
    }

    @Test fun recordingChanges_excludeSpanningFlowsAndResumeOnlyForNewFlows() {
        val sink = Sink()
        val mode = TrafficRecording()
        val t = FlowTracker(1, { _, _, _ -> Owner(10123, "com.example.app") }, sink, env, recording = { mode.window })
        t.onEvent(open(1, 6, "1.2.3.4", 80))
        mode.setEnabled(true)
        t.onEvent(NativeEvent.Close(1, 10, 20))
        t.onEvent(open(2, 6, "1.2.3.4", 80))
        mode.setEnabled(false)
        mode.setEnabled(true)
        t.onEvent(NativeEvent.Close(2, 10, 20))
        t.onEvent(open(3, 6, "1.2.3.4", 80))
        mode.setEnabled(true) // Setting the same mode must not discard valid evidence.
        t.onEvent(NativeEvent.Close(3, 10, 20))
        assertEquals(1, sink.flows.size)
        assertEquals(3, t.stats().flows)
    }

    @Test fun recordingOff_doesNotRetainDecryptedMessages() {
        val mode = TrafficRecording()
        var stored = 0
        val sink = object : CaptureSink {
            override fun flow(f: Flow) { stored++ }
            override fun flowWithMessages(f: Flow, messages: List<com.nathanhanapps.wayd.core.db.Message>) { stored += messages.size + 1 }
            override fun dns(d: DnsRecord) { stored++ }
        }
        val t = FlowTracker(1, { _, _, _ -> Owner(10123, "com.example.app") }, sink, env, recording = { mode.window })
        mode.setEnabled(true)
        t.onEvent(open(1, 6, "1.2.3.4", 443))
        t.onEvent(NativeEvent.Message(1, false, 0, "GET", "/", "", 3, false, byteArrayOf(1, 2, 3)))
        mode.setEnabled(false)
        t.onEvent(NativeEvent.Message(1, true, 200, "", "/", "", 3, false, byteArrayOf(4, 5, 6)))
        t.finishAll()
        assertEquals(0, stored)
    }

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

    @Test fun tlsHostSurvivesEveryStreamSplit() {
        val hello = clientHello("split.example")
        for (split in 1 until hello.size) {
            val sink = Sink()
            val t = tracker(sink)
            t.onEvent(open(1, 6, "5.6.7.8", 443))
            t.onEvent(NativeEvent.Data(1, false, hello.copyOfRange(0, split)))
            t.onEvent(NativeEvent.Data(1, true, byteArrayOf(1, 2, 3)))
            t.onEvent(NativeEvent.Data(1, false, hello.copyOfRange(split, hello.size)))
            t.onEvent(NativeEvent.Close(1, hello.size.toLong(), 3))
            assertEquals("split at $split", "split.example", sink.flows.single().host)
        }
    }

    @Test fun httpHostAfterLongSplitHeaderAndLaterRequestsKeepsFirstHost() {
        val sink = Sink()
        val t = tracker(sink)
        t.onEvent(open(1, 6, "5.6.7.8", 80))
        val request = "GET / HTTP/1.1\r\nX-Padding: ${"a".repeat(3000)}\r\nHost: first.example\r\n\r\n".toByteArray()
        request.toList().chunked(500).forEach { t.onEvent(NativeEvent.Data(1, false, it.toByteArray())) }
        t.onEvent(NativeEvent.Data(1, false, "GET / HTTP/1.1\r\nHost: second.example\r\n\r\n".toByteArray()))
        t.finishAll()
        assertEquals("first.example", sink.flows.single().host)
        assertEquals(HostSource.HTTP_HOST, sink.flows.single().hostSource)
    }

    @Test fun overBudgetAndIncompletePrefixesDoNotInventHosts() {
        val sink = Sink()
        val t = tracker(sink)
        t.onEvent(open(1, 6, "5.6.7.8", 80))
        t.onEvent(NativeEvent.Data(1, false, ByteArray(20_000) { 'a'.code.toByte() }))
        t.onEvent(NativeEvent.Data(1, false, "GET / HTTP/1.1\r\nHost: late.example\r\n\r\n".toByteArray()))
        t.onEvent(open(2, 6, "5.6.7.8", 443))
        t.onEvent(NativeEvent.Data(2, false, clientHello("partial.example").dropLast(1).toByteArray()))
        t.finishAll()
        sink.flows.forEach { assertNull(it.host) }
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

    @Test fun transientOwnerFailureRetriesExactTupleAndKeepsResolvedOwner() {
        val sink = Sink()
        var calls = 0
        val t = FlowTracker(1, { proto, src, dst ->
            assertEquals(17, proto)
            assertEquals(40000, src.port)
            assertEquals("5.6.7.8", dst.address.hostAddress)
            assertEquals(443, dst.port)
            if (++calls == 1) throw SecurityException("transient") else Owner(10123, "com.example.app")
        }, sink, env)
        t.onEvent(open(1, 17, "5.6.7.8", 443))
        repeat(5) { t.onEvent(NativeEvent.Data(1, false, byteArrayOf(1))) }
        t.finishAll()
        assertEquals(2, calls)
        assertEquals("com.example.app", sink.flows.single().pkg)
    }

    @Test fun ownerRetriesAreBoundedAndNeverRunOnClose() {
        val sink = Sink()
        var calls = 0
        val t = FlowTracker(1, { _, _, _ -> calls++; null }, sink, env)
        t.onEvent(open(1, 6, "5.6.7.8", 443))
        repeat(10) { t.onEvent(NativeEvent.Data(1, false, byteArrayOf(1))) }
        t.onEvent(NativeEvent.Close(1, 10, 0))
        assertEquals(3, calls)
        assertEquals(FlowTracker.UNKNOWN_PKG, sink.flows.single().pkg)
    }

    @Test fun lateOwnerLookupDoesNotAttributeAReusedTupleOrChangeInterceptConsent() {
        val sink = Sink()
        var now = 1_000L
        var calls = 0
        val clock = object : CaptureEnv by env { override fun now() = now }
        val decisions = ArrayList<Boolean>()
        val t = FlowTracker(1, { _, _, _ -> calls++; null }, sink, clock,
            gate = { _, _, _ -> error("Unknown owner must never pass the gate") },
            reply = { _, allow -> decisions += allow },
        )
        t.onEvent(open(1, 6, "5.6.7.8", 443))
        now += 1_001
        t.onEvent(NativeEvent.Data(1, false, byteArrayOf(1)))
        t.finishAll()
        assertEquals(1, calls)
        assertEquals(listOf(false), decisions)
    }

    @Test fun tcpDnsReassemblesEverySplitAndHandlesMultipleMessages() {
        val response = dnsResponse("tcp.example.org", byteArrayOf(1, 2, 3, 4))
        val framed = u16(response.size) + response
        for (split in 1 until framed.size) {
            val sink = Sink()
            val t = tracker(sink)
            t.onEvent(open(1, 6, "8.8.8.8", 53))
            // Requests must not be interpreted as replies.
            t.onEvent(NativeEvent.Data(1, false, framed))
            t.onEvent(NativeEvent.Data(1, true, framed.copyOfRange(0, split)))
            t.onEvent(NativeEvent.Data(1, true, framed.copyOfRange(split, framed.size) + framed))
            t.onEvent(open(2, 6, "1.2.3.4", 8080))
            t.finishAll()
            assertEquals(2, sink.dns.size)
            assertEquals("tcp.example.org", sink.flows.last().host)
            assertEquals(HostSource.DNS, sink.flows.last().hostSource)
        }
    }

    @Test fun incompleteOrOversizedTcpDnsNeverInventsHost() {
        val response = dnsResponse("partial.example", byteArrayOf(1, 2, 3, 4))
        val sink = Sink()
        val t = tracker(sink)
        t.onEvent(open(1, 6, "8.8.8.8", 53))
        t.onEvent(NativeEvent.Data(1, true, u16(response.size) + response.dropLast(1)))
        t.onEvent(open(2, 6, "8.8.4.4", 53))
        t.onEvent(NativeEvent.Data(2, true, u16(65535) + response))
        t.onEvent(NativeEvent.Data(2, true, u16(response.size) + response))
        t.onEvent(open(3, 6, "1.2.3.4", 8080))
        t.finishAll()
        assertEquals(0, sink.dns.size)
        assertNull(sink.flows.last().host)
    }

    @Test fun failingEnvDoesNotBreakTracking() {
        val sink = Sink()
        val broken = object : CaptureEnv {
            override fun now() = 5L
            override fun screenOn(): Boolean = throw SecurityException("no permission")
            override fun netType(): NetType = throw SecurityException("no permission")
        }
        val t = FlowTracker(1, { _, _, _ -> null }, sink, broken)
        t.onEvent(open(1, 6, "5.6.7.8", 443))
        t.onEvent(NativeEvent.Close(1, 1, 2))
        assertEquals(NetType.OTHER, sink.flows.single().netType)
        assertEquals(false, sink.flows.single().screenOn)
    }

    @Test fun finishAllFlushesOpenFlows() {
        val sink = Sink()
        val t = tracker(sink)
        t.onEvent(open(1, 6, "5.6.7.8", 443))
        t.finishAll()
        assertEquals(1, sink.flows.size)
        assertEquals(1, t.stats().flows)
    }

    @Test fun policyUsesOnlyCompleteVisibleHostAndRetainsNativeDisposition() {
        val sink = Sink()
        val hosts = ArrayList<String>()
        val owners = ArrayList<Owner?>()
        val t = FlowTracker(1, { _, _, _ -> Owner(10123, "com.example.app") }, sink, env,
            policyOwner = { _, owner -> owners += owner }, policyHost = { _, host -> hosts += host })
        t.onEvent(open(1, 6, "5.6.7.8", 443))
        val hello = clientHello("metrics.example.com")
        t.onEvent(NativeEvent.Data(1, false, hello.copyOfRange(0, 9)))
        assertEquals(emptyList<String>(), hosts)
        t.onEvent(NativeEvent.Data(1, false, hello.copyOfRange(9, hello.size)))
        assertEquals(listOf("metrics.example.com"), hosts)
        assertEquals("com.example.app", owners.single()?.pkg)
        t.onEvent(NativeEvent.Policy(1, 1, 7, -3))
        t.onEvent(NativeEvent.Close(1, 0, 0))
        assertEquals("BLOCKED", sink.flows.single().policyOutcome)
        assertEquals(-3L, sink.flows.single().blockRuleId)
        assertEquals(0L, sink.flows.single().bytesUp)
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
