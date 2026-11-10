package com.nathanhanapps.wayd.capture.pcap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Independent reader: parses the produced bytes and recomputes every checksum. */
private class Packet(val tsMicros: Long, val bytes: ByteArray) {
    val v6 = (bytes[0].toInt() shr 4) == 6
    val proto = if (v6) bytes[6].toInt() and 0xff else bytes[9].toInt() and 0xff
    val ipLen = if (v6) 40 else (bytes[0].toInt() and 0x0f) * 4
    val src = bytes.copyOfRange(if (v6) 8 else 12, if (v6) 24 else 16)
    val dst = bytes.copyOfRange(if (v6) 24 else 16, if (v6) 40 else 20)
    val l4 = bytes.copyOfRange(ipLen, bytes.size)
    val srcPort = u16(l4, 0)
    val dstPort = u16(l4, 2)
    val flags = if (proto == 6) l4[13].toInt() and 0xff else 0
    val seq = if (proto == 6) u32(l4, 4) else 0L
    val ack = if (proto == 6) u32(l4, 8) else 0L
    val payload = if (proto == 6) l4.copyOfRange((l4[12].toInt() shr 4) * 4, l4.size) else l4.copyOfRange(8, l4.size)

    fun ipChecksumOk() = v6 || PcapSynth.checksum(bytes.copyOfRange(0, ipLen)) == 0
    fun l4ChecksumOk(): Boolean {
        val pseudo = if (!v6) ByteArray(12).also { src.copyInto(it); dst.copyInto(it, 4); it[9] = proto.toByte(); it[10] = (l4.size shr 8).toByte(); it[11] = l4.size.toByte() }
        else ByteArray(40).also { src.copyInto(it); dst.copyInto(it, 16); it[34] = (l4.size shr 8).toByte(); it[35] = l4.size.toByte(); it[39] = proto.toByte() }
        return PcapSynth.checksum(pseudo + l4) == 0
    }

    companion object {
        fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
        fun u32(b: ByteArray, at: Int) = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
    }
}

private fun parse(file: ByteArray): List<Packet> {
    fun le32(at: Int) = (file[at].toLong() and 0xff) or ((file[at + 1].toLong() and 0xff) shl 8) or ((file[at + 2].toLong() and 0xff) shl 16) or ((file[at + 3].toLong() and 0xff) shl 24)
    assertEquals(0xa1b2c3d4L, le32(0))
    assertEquals(2, file[4].toInt()); assertEquals(4, file[6].toInt())
    assertEquals(101L, le32(20))
    val out = ArrayList<Packet>()
    var at = 24
    while (at < file.size) {
        val len = le32(at + 8).toInt()
        assertEquals("incl_len == orig_len", len.toLong(), le32(at + 12))
        out += Packet(le32(at) * 1_000_000 + le32(at + 4), file.copyOfRange(at + 16, at + 16 + len))
        at += 16 + len
    }
    assertEquals("no trailing bytes", file.size, at)
    return out
}

private fun build(vararg flows: SynthFlow): List<Packet> {
    val out = ByteArrayOutputStream()
    val synth = PcapSynth(PcapWriter(out))
    flows.sortedBy { it.startMs }.forEach(synth::add)
    synth.finish()
    return parse(out.toByteArray())
}

class PcapSynthTest {
    private fun tcp(id: Long, ip: String = "93.184.216.34", port: Int = 443, start: Long = 1_700_000_000_000, end: Long? = start + 2000, blocked: Boolean = false, payloads: List<SynthPayload> = emptyList()) =
        SynthFlow(id, true, ip, port, start, end, blocked, 100, 200, payloads)

    @Test fun emptyExportIsAValidFileWithOnlyAHeader() {
        val out = ByteArrayOutputStream()
        PcapSynth(PcapWriter(out)).finish()
        assertEquals(0, out.size()) // header is written lazily with the first record
        val w = ByteArrayOutputStream().also { PcapWriter(it).header() }.toByteArray()
        assertEquals(24, w.size)
        assertEquals(24, parse(w).let { 24 })
    }

    @Test fun tcpFlowHasHandshakeAndCloseWithValidChecksumsAndSequencing() {
        val p = build(tcp(1))
        assertEquals(listOf(PcapSynth.SYN, PcapSynth.SYN or PcapSynth.ACK, PcapSynth.ACK, PcapSynth.FIN or PcapSynth.ACK, PcapSynth.FIN or PcapSynth.ACK, PcapSynth.ACK), p.map { it.flags })
        assertTrue(p.all { it.ipChecksumOk() && it.l4ChecksumOk() })
        assertEquals(p.sortedBy { it.tsMicros }.map { it.tsMicros }, p.map { it.tsMicros })
        // SYN consumes one sequence number: the third packet acknowledges the server's ISN + 1 and uses client ISN + 1.
        assertEquals(p[0].seq + 1, p[2].seq)
        assertEquals(p[1].seq + 1, p[2].ack)
        assertEquals(p[1].ack, p[0].seq + 1)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), p[0].src)
        assertEquals(443, p[0].dstPort)
        assertEquals(p[0].srcPort, p[1].dstPort)
    }

    @Test fun decryptedPayloadIsCarriedVerbatimAndSegmented() {
        val req = "GET /x HTTP/1.1\r\nHost: a.example\r\n\r\n".toByteArray()
        val resp = ByteArray(3500) { (it % 251).toByte() }
        val p = build(tcp(2, port = 80, payloads = listOf(SynthPayload(true, 1_700_000_000_100, req), SynthPayload(false, 1_700_000_000_200, resp))))
        assertTrue(p.all { it.ipChecksumOk() && it.l4ChecksumOk() })
        val up = p.filter { it.srcPort != 80 && it.payload.isNotEmpty() }
        val down = p.filter { it.srcPort == 80 && it.payload.isNotEmpty() }
        assertArrayEquals(req, up.flatMap { it.payload.toList() }.toByteArray())
        assertArrayEquals(resp, down.flatMap { it.payload.toList() }.toByteArray())
        assertEquals("3500 bytes need 3 segments of at most 1400", 3, down.size)
        // Sequence numbers advance by exactly the payload length.
        assertEquals(down[0].seq + 1400, down[1].seq)
        assertEquals(down[1].seq + 1400, down[2].seq)
    }

    @Test fun flowsWithoutDecryptedContentCarryNoInventedBytes() {
        assertTrue(build(tcp(3)).all { it.payload.isEmpty() })
    }

    @Test fun blockedFlowIsASynAndAReset() {
        val p = build(tcp(4, blocked = true))
        assertEquals(listOf(PcapSynth.SYN, PcapSynth.RST or PcapSynth.ACK), p.map { it.flags })
        assertEquals(p[0].seq + 1, p[1].ack)
        assertTrue(p.all { it.ipChecksumOk() && it.l4ChecksumOk() })
    }

    @Test fun openFlowHasNoClosingPackets() {
        assertEquals(3, build(tcp(5, end = null)).size)
    }

    @Test fun ipv6UsesTheTunnelAddressAndValidChecksums() {
        val p = build(tcp(6, ip = "2606:4700:4700::1111"))
        assertTrue(p.all { it.v6 && it.l4ChecksumOk() })
        assertEquals(0xfd.toByte(), p[0].src[0])
        assertEquals(0x26.toByte(), p[0].dst[0])
    }

    @Test fun udpFlowsAreOneOrTwoDatagramsAndNeverAZeroChecksum() {
        val p = build(SynthFlow(7, false, "8.8.8.8", 53, 1_700_000_000_000, 1_700_000_000_050, false, 40, 80), SynthFlow(8, false, "8.8.4.4", 443, 1_700_000_000_010, null, false, 1200, 0))
        assertEquals(3, p.size)
        assertTrue(p.all { it.proto == 17 && it.ipChecksumOk() && it.l4ChecksumOk() && (it.l4[6].toInt() != 0 || it.l4[7].toInt() != 0) })
    }

    @Test fun overlappingFlowsAreInterleavedInTimeOrder() {
        val p = build(tcp(10, start = 1_700_000_000_000, end = 1_700_000_005_000), tcp(11, ip = "1.1.1.1", start = 1_700_000_001_000, end = 1_700_000_002_000))
        assertEquals(12, p.size)
        assertEquals(p.map { it.tsMicros }.sorted(), p.map { it.tsMicros })
        assertFalse("flow 11 starts before flow 10 closes", p[3].dst.contentEquals(p[0].dst) && p[3].flags == PcapSynth.FIN)
    }

    @Test fun checksumMatchesAKnownRfc1071Vector() {
        // Header from RFC 1071 section 3 style example: checksum over data including its own checksum folds to zero.
        val hdr = byteArrayOf(0x45, 0, 0, 0x73, 0, 0, 0x40, 0, 0x40, 0x11, 0, 0, 0xc0.toByte(), 0xa8.toByte(), 0, 1, 0xc0.toByte(), 0xa8.toByte(), 0, 0xc7.toByte())
        val c = PcapSynth.checksum(hdr)
        assertEquals(0xb861, c)
        hdr[10] = (c shr 8).toByte(); hdr[11] = c.toByte()
        assertEquals(0, PcapSynth.checksum(hdr))
    }
}

class PcapDroidCsvTest {
    @Test fun headerIsPcapdroidsSeventeenColumns() {
        assertEquals(17, PcapDroidCsv.HEADER.split(',').size)
        assertTrue(PcapDroidCsv.HEADER.startsWith("IPProto,SrcIP,SrcPort,DstIp,DstPort,UID,App,PackageName,Proto,Status,Info,"))
        assertTrue(PcapDroidCsv.HEADER.endsWith("BytesSent,BytesRcvd,PktsSent,PktsRcvd,FirstSeen,LastSeen"))
    }

    @Test fun rowsHaveExactlyTheHeaderColumnsEvenWithCommasInValues() {
        val r = PcapDroidCsv.row(true, "10.111.222.1", 0, "1.2.3.4", 443, 10234, "Chat, Pro", "com.x", "TLS", "Closed", "a,b\nc", 12, 34, 1_700_000_000_000, 1_700_000_001_500)
        assertEquals(17, r.split(',').size)
        assertTrue(r.startsWith("6,10.111.222.1,0,1.2.3.4,443,10234,Chat; Pro,com.x,TLS,Closed,a;b c,12,34,0,0,"))
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}|Z$""").containsMatchIn(r.substringAfterLast(',')))
    }

    @Test fun l7ProtocolNames() {
        assertEquals("DNS", PcapDroidCsv.l7(false, 53, false, false))
        assertEquals("QUIC", PcapDroidCsv.l7(false, 443, false, false))
        assertEquals("TLS", PcapDroidCsv.l7(true, 443, true, false))
        assertEquals("HTTP", PcapDroidCsv.l7(true, 80, false, true))
        assertEquals("unknown", PcapDroidCsv.l7(true, 5222, false, false))
    }
}
