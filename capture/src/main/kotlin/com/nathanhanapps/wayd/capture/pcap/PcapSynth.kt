package com.nathanhanapps.wayd.capture.pcap

import java.io.OutputStream
import java.net.InetAddress
import java.util.PriorityQueue

/** One recorded connection, reduced to what a reconstructed capture needs (no Room types, so it tests on the JVM). */
class SynthFlow(
    val id: Long,
    val tcp: Boolean,
    val dstIp: String,
    val dstPort: Int,
    val startMs: Long,
    /** Null while the connection was still open when it was recorded: no closing packets are written. */
    val endMs: Long?,
    /** Dropped by a WAYD rule: the capture shows the SYN and a reset, nothing else. */
    val blocked: Boolean,
    val bytesUp: Long,
    val bytesDown: Long,
    /** Decrypted application data, in time order. The only payload bytes that ever appear in the file. */
    val payloads: List<SynthPayload> = emptyList(),
)

class SynthPayload(val up: Boolean, val tsMs: Long, val bytes: ByteArray)

/** Classic libpcap writer (microsecond timestamps, little-endian, link type 101 = raw IPv4/IPv6). */
class PcapWriter(private val out: OutputStream) {
    private var started = false

    fun header() {
        if (started) return
        started = true
        out.write(le32(0xa1b2c3d4L)); out.write(le16(2)); out.write(le16(4))
        out.write(le32(0)); out.write(le32(0)); out.write(le32(SNAPLEN.toLong())); out.write(le32(LINKTYPE_RAW.toLong()))
    }

    fun record(tsMicros: Long, packet: ByteArray) {
        header()
        out.write(le32(tsMicros / 1_000_000)); out.write(le32(tsMicros % 1_000_000))
        out.write(le32(packet.size.toLong())); out.write(le32(packet.size.toLong()))
        out.write(packet)
    }

    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    companion object {
        const val LINKTYPE_RAW = 101
        const val SNAPLEN = 262_144
    }
}

/**
 * Rebuilds a capture from WAYD's records. WAYD terminates connections in userspace and keeps metadata, not packets, so this
 * file is a *reconstruction*: real endpoints, times and (research build) decrypted payload, wrapped in well-formed packets
 * with valid checksums. Flows without decrypted payload carry handshake and close only: no bytes are invented to match volume.
 * Flows must be added in start-time order; packets are written in global time order.
 */
class PcapSynth(
    private val writer: PcapWriter,
    private val client4: ByteArray = byteArrayOf(10, 111, 222.toByte(), 1),
    private val client6: ByteArray = InetAddress.getByName("fd00:111:222::1").address,
) {
    private class Pending(val ts: Long, val seq: Long, val packet: ByteArray)

    private val queue = PriorityQueue<Pending>(compareBy<Pending> { it.ts }.thenBy { it.seq })
    private var seq = 0L
    private var nextPort = FIRST_PORT
    private var ipId = 0

    fun add(flow: SynthFlow) {
        flushUntil(flow.startMs * 1000)
        val dst = runCatching { InetAddress.getByName(flow.dstIp).address }.getOrNull() ?: return
        val v6 = dst.size == 16
        val src = if (v6) client6 else client4
        val srcPort = nextPort.also { nextPort = if (it >= LAST_PORT) FIRST_PORT else it + 1 }
        fun put(ts: Long, packet: ByteArray) { queue.add(Pending(ts, seq++, packet)) }
        fun ip(proto: Int, from: ByteArray, to: ByteArray, l4: ByteArray) = if (v6) ipv6(proto, from, to, l4) else ipv4(proto, from, to, l4, ipId++ and 0xffff)
        val t0 = flow.startMs * 1000

        if (!flow.tcp) {
            put(t0, ip(UDP, src, dst, udp(src, dst, srcPort, flow.dstPort, ByteArray(0))))
            if (flow.bytesDown > 0) put((flow.endMs ?: flow.startMs) * 1000 + 200, ip(UDP, dst, src, udp(dst, src, flow.dstPort, srcPort, ByteArray(0))))
            return
        }

        var cSeq = isn(flow.id, 1)
        var sSeq = isn(flow.id, 2)
        fun seg(fromClient: Boolean, flags: Int, payload: ByteArray = ByteArray(0), options: ByteArray = ByteArray(0)): ByteArray {
            val a = if (fromClient) src else dst
            val b = if (fromClient) dst else src
            val pa = if (fromClient) srcPort else flow.dstPort
            val pb = if (fromClient) flow.dstPort else srcPort
            return ip(TCP, a, b, tcp(a, b, pa, pb, if (fromClient) cSeq else sSeq, if (fromClient) sSeq else cSeq, flags, payload, options))
        }

        put(t0, seg(true, SYN, options = MSS_OPTION)); cSeq = cSeq + 1 and 0xffffffffL
        if (flow.blocked) { put(t0 + 300, seg(false, RST or ACK)); return }
        put(t0 + 400, seg(false, SYN or ACK, options = MSS_OPTION)); sSeq = sSeq + 1 and 0xffffffffL
        put(t0 + 800, seg(true, ACK))

        var clock = t0 + 1200
        for (p in flow.payloads) {
            clock = maxOf(clock, p.tsMs * 1000)
            var off = 0
            while (off < p.bytes.size) {
                val chunk = p.bytes.copyOfRange(off, minOf(off + MSS, p.bytes.size))
                put(clock, seg(p.up, PSH or ACK, chunk))
                if (p.up) cSeq = cSeq + chunk.size and 0xffffffffL else sSeq = sSeq + chunk.size and 0xffffffffL
                off += chunk.size
                clock += 40
            }
            put(clock + 100, seg(!p.up, ACK))
            clock += 150
        }

        flow.endMs?.let { end ->
            val t = maxOf(end * 1000, clock + 500)
            put(t, seg(true, FIN or ACK)); cSeq = cSeq + 1 and 0xffffffffL
            put(t + 300, seg(false, FIN or ACK)); sSeq = sSeq + 1 and 0xffffffffL
            put(t + 600, seg(true, ACK))
        }
    }

    /** Writes everything still queued; call once after the last flow. */
    fun finish() = flushUntil(Long.MAX_VALUE)

    private fun flushUntil(ts: Long) {
        while (queue.isNotEmpty() && queue.peek().ts <= ts) queue.poll().let { writer.record(it.ts, it.packet) }
    }

    private fun isn(id: Long, salt: Int): Long = ((id + salt) * 2654435761L + salt * 40503L) and 0xffffffffL

    private fun ipv4(proto: Int, src: ByteArray, dst: ByteArray, l4: ByteArray, id: Int): ByteArray {
        val h = ByteArray(20)
        h[0] = 0x45; h[2] = ((20 + l4.size) shr 8).toByte(); h[3] = (20 + l4.size).toByte()
        h[4] = (id shr 8).toByte(); h[5] = id.toByte(); h[6] = 0x40; h[8] = 64; h[9] = proto.toByte()
        src.copyInto(h, 12); dst.copyInto(h, 16)
        val c = checksum(h)
        h[10] = (c shr 8).toByte(); h[11] = c.toByte()
        return h + l4
    }

    private fun ipv6(proto: Int, src: ByteArray, dst: ByteArray, l4: ByteArray): ByteArray {
        val h = ByteArray(40)
        h[0] = 0x60; h[4] = (l4.size shr 8).toByte(); h[5] = l4.size.toByte(); h[6] = proto.toByte(); h[7] = 64
        src.copyInto(h, 8); dst.copyInto(h, 24)
        return h + l4
    }

    private fun tcp(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, seqNo: Long, ackNo: Long, flags: Int, payload: ByteArray, options: ByteArray): ByteArray {
        val len = 20 + options.size
        val h = ByteArray(len + payload.size)
        h[0] = (sp shr 8).toByte(); h[1] = sp.toByte(); h[2] = (dp shr 8).toByte(); h[3] = dp.toByte()
        put32(h, 4, seqNo); put32(h, 8, if (flags and ACK != 0) ackNo else 0)
        h[12] = ((len / 4) shl 4).toByte(); h[13] = flags.toByte(); h[14] = 0xff.toByte(); h[15] = 0xff.toByte()
        options.copyInto(h, 20); payload.copyInto(h, len)
        val c = l4Checksum(src, dst, TCP, h)
        h[16] = (c shr 8).toByte(); h[17] = c.toByte()
        return h
    }

    private fun udp(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(8 + payload.size)
        h[0] = (sp shr 8).toByte(); h[1] = sp.toByte(); h[2] = (dp shr 8).toByte(); h[3] = dp.toByte()
        h[4] = (h.size shr 8).toByte(); h[5] = h.size.toByte()
        payload.copyInto(h, 8)
        val c = l4Checksum(src, dst, UDP, h).let { if (it == 0) 0xffff else it }
        h[6] = (c shr 8).toByte(); h[7] = c.toByte()
        return h
    }

    private fun put32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v shr 24).toByte(); b[at + 1] = (v shr 16).toByte(); b[at + 2] = (v shr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun l4Checksum(src: ByteArray, dst: ByteArray, proto: Int, segment: ByteArray): Int {
        val pseudo = if (src.size == 4) {
            ByteArray(12).also { src.copyInto(it, 0); dst.copyInto(it, 4); it[9] = proto.toByte(); it[10] = (segment.size shr 8).toByte(); it[11] = segment.size.toByte() }
        } else {
            ByteArray(40).also { src.copyInto(it, 0); dst.copyInto(it, 16); it[34] = (segment.size shr 8).toByte(); it[35] = segment.size.toByte(); it[39] = proto.toByte() }
        }
        return checksum(pseudo + segment)
    }

    companion object {
        const val TCP = 6
        const val UDP = 17
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        private const val MSS = 1400
        private const val FIRST_PORT = 20000
        private const val LAST_PORT = 60000
        private val MSS_OPTION = byteArrayOf(2, 4, 0x05, 0xb4.toByte()) // MSS 1460

        /** RFC 1071 internet checksum. */
        fun checksum(data: ByteArray): Int {
            var sum = 0L
            var i = 0
            while (i + 1 < data.size) { sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff); i += 2 }
            if (i < data.size) sum += (data[i].toInt() and 0xff) shl 8
            while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
            return (sum.inv() and 0xffff).toInt()
        }
    }
}

/** PCAPdroid's "connections CSV": same 17 columns and order, unquoted (so commas inside values are replaced), ISO-8601 times. */
object PcapDroidCsv {
    const val HEADER = "IPProto,SrcIP,SrcPort,DstIp,DstPort,UID,App,PackageName,Proto,Status,Info,BytesSent,BytesRcvd,PktsSent,PktsRcvd,FirstSeen,LastSeen"

    fun clean(s: String?): String = s.orEmpty().replace(',', ';').replace('\n', ' ').replace('\r', ' ')

    fun iso(ms: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(ms))

    /** PCAPdroid's L7 protocol column, as far as WAYD can tell from what it saw. */
    fun l7(tcp: Boolean, port: Int, tls: Boolean, httpHost: Boolean): String = when {
        port == 53 -> "DNS"
        !tcp && port == 443 -> "QUIC"
        httpHost || (tcp && port == 80) -> "HTTP"
        tls || (tcp && port == 443) -> "TLS"
        else -> "unknown"
    }

    fun row(
        tcp: Boolean, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, uid: Int, app: String, pkg: String, l7: String,
        status: String, info: String, up: Long, down: Long, firstSeen: Long, lastSeen: Long,
    ): String = listOf(
        if (tcp) "6" else "17", srcIp, srcPort.toString(), dstIp, dstPort.toString(), uid.toString(), clean(app), clean(pkg), l7, status, clean(info),
        up.toString(), down.toString(), "0", "0", iso(firstSeen), iso(lastSeen),
    ).joinToString(",")
}
