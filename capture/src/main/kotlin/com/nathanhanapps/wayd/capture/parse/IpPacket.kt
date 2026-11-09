package com.nathanhanapps.wayd.capture.parse

/** Parsed IP header. All offsets index into the buffer passed to [IpPacket.parse]. */
class IpPacket(
    val version: Int,
    val protocol: Int,
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    /** Offset of the transport header (TCP/UDP), after IPv4 options / IPv6 extension headers. */
    val payloadOffset: Int,
    val totalLength: Int,
    val srcPort: Int,
    val dstPort: Int,
) {
    companion object {
        const val TCP = 6
        const val UDP = 17
        private val V6_EXT = setOf(0, 43, 60) // hop-by-hop, routing, destination options

        /** Returns null for anything malformed, truncated, or not IPv4/IPv6. Never throws. */
        fun parse(buf: ByteArray, len: Int = buf.size): IpPacket? {
            val n = minOf(len, buf.size)
            if (n < 1) return null
            return when ((buf[0].toInt() ushr 4) and 0xF) {
                4 -> parseV4(buf, n)
                6 -> parseV6(buf, n)
                else -> null
            }
        }

        private fun parseV4(b: ByteArray, n: Int): IpPacket? {
            if (n < 20) return null
            val ihl = (b[0].toInt() and 0xF) * 4
            if (ihl < 20 || n < ihl) return null
            val total = u16(b, 2)
            if (total < ihl || total > n) return null
            val proto = b[9].toInt() and 0xFF
            val (sp, dp) = ports(b, ihl, total, proto)
            return IpPacket(4, proto, b.copyOfRange(12, 16), b.copyOfRange(16, 20), ihl, total, sp, dp)
        }

        private fun parseV6(b: ByteArray, n: Int): IpPacket? {
            if (n < 40) return null
            val total = 40 + u16(b, 4)
            if (total > n) return null
            var next = b[6].toInt() and 0xFF
            var off = 40
            var hops = 0
            while (next in V6_EXT) {
                if (++hops > 8 || off + 8 > total) return null
                val extLen = ((b[off + 1].toInt() and 0xFF) + 1) * 8
                next = b[off].toInt() and 0xFF
                off += extLen
                if (off > total) return null
            }
            val (sp, dp) = ports(b, off, total, next)
            return IpPacket(6, next, b.copyOfRange(8, 24), b.copyOfRange(24, 40), off, total, sp, dp)
        }

        private fun ports(b: ByteArray, off: Int, total: Int, proto: Int): Pair<Int, Int> =
            if ((proto == TCP || proto == UDP) && off + 4 <= total) u16(b, off) to u16(b, off + 2) else 0 to 0

        private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    }
}
