package com.nathanhanapps.wayd.capture.parse

import java.net.InetAddress

data class DnsQuestion(val name: String, val type: Int)
data class DnsAnswer(val name: String, val type: Int, val ttl: Long, val data: String)
data class DnsMessage(
    val id: Int,
    val isResponse: Boolean,
    val rcode: Int,
    val questions: List<DnsQuestion>,
    val answers: List<DnsAnswer>,
)

/** Minimal DNS wire parser (RFC 1035). Hostile-input safe: bounded loops, no exceptions escape. */
object DnsParser {
    const val A = 1
    const val CNAME = 5
    const val AAAA = 28
    private const val MAX_RECORDS = 64
    private const val MAX_NAME = 255

    fun parse(b: ByteArray, off: Int = 0, len: Int = b.size - off): DnsMessage? = try {
        parseUnsafe(b, off, minOf(len, b.size - off))
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun parseUnsafe(b: ByteArray, start: Int, len: Int): DnsMessage? {
        if (len < 12) return null
        val end = start + len
        val flags = u16(b, start + 2)
        val qd = u16(b, start + 4)
        val an = u16(b, start + 6)
        if (qd > MAX_RECORDS || an > MAX_RECORDS) return null
        var p = start + 12
        val questions = ArrayList<DnsQuestion>()
        repeat(qd) {
            val (name, np) = readName(b, p, start, end) ?: return null
            if (np + 4 > end) return null
            questions += DnsQuestion(name, u16(b, np))
            p = np + 4
        }
        val answers = ArrayList<DnsAnswer>()
        repeat(an) {
            val (name, np) = readName(b, p, start, end) ?: return null
            if (np + 10 > end) return null
            val type = u16(b, np)
            val ttl = (u16(b, np + 4).toLong() shl 16) or u16(b, np + 6).toLong()
            val rdLen = u16(b, np + 8)
            val rd = np + 10
            if (rd + rdLen > end) return null
            val data = when {
                type == A && rdLen == 4 -> InetAddress.getByAddress(b.copyOfRange(rd, rd + 4)).hostAddress
                type == AAAA && rdLen == 16 -> InetAddress.getByAddress(b.copyOfRange(rd, rd + 16)).hostAddress
                type == CNAME -> readName(b, rd, start, end)?.first
                else -> null
            }
            if (data != null) answers += DnsAnswer(name, type, ttl, data)
            p = rd + rdLen
        }
        return DnsMessage(u16(b, start), flags and 0x8000 != 0, flags and 0xF, questions, answers)
    }

    /** Reads a possibly compressed name; returns the name and the offset just after it in the original stream. */
    private fun readName(b: ByteArray, from: Int, base: Int, end: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var p = from
        var after = -1
        var jumps = 0
        while (true) {
            if (p >= end) return null
            val l = b[p].toInt() and 0xFF
            when {
                l == 0 -> return sb.toString().lowercase() to (if (after >= 0) after else p + 1)
                l and 0xC0 == 0xC0 -> {
                    if (p + 1 >= end || ++jumps > 10) return null
                    if (after < 0) after = p + 2
                    p = base + (((l and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF))
                }
                l and 0xC0 != 0 -> return null
                else -> {
                    if (p + 1 + l > end) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 0 until l) {
                        val c = b[p + 1 + i].toInt() and 0xFF
                        sb.append(if (c in 0x20..0x7E) c.toChar() else '?')
                    }
                    if (sb.length > MAX_NAME) return null
                    p += 1 + l
                }
            }
        }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
