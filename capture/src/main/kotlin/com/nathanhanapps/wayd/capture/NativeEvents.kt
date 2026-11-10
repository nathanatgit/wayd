package com.nathanhanapps.wayd.capture

import java.net.InetAddress

/** Flow events emitted by the native stack. Wire format is documented in capture/native/src/events.rs. */
sealed interface NativeEvent {
    val flowId: Long

    class Open(
        override val flowId: Long,
        val proto: Int,
        val ipVersion: Int,
        val srcPort: Int,
        val dstPort: Int,
        val srcIp: InetAddress,
        val dstIp: InetAddress,
    ) : NativeEvent

    class Data(override val flowId: Long, val down: Boolean, val bytes: ByteArray) : NativeEvent

    /** A decrypted HTTP/1.1 message (research flavor only). */
    class Message(
        override val flowId: Long,
        val response: Boolean,
        val status: Int,
        val method: String,
        val url: String,
        val headers: String,
        val bodyTotal: Long,
        val truncated: Boolean,
        val body: ByteArray,
    ) : NativeEvent

    /** kind: 1 intercepted, 2 app rejected our certificate (pinned), 3 upstream TLS failed, 4 skipped host. */
    class TlsNote(override val flowId: Long, val kind: Int) : NativeEvent

    /** Running byte total of one direction for a flow that is still open. */
    class Progress(override val flowId: Long, val down: Boolean, val total: Long) : NativeEvent

    class Close(override val flowId: Long, val bytesUp: Long, val bytesDown: Long) : NativeEvent

    class Policy(override val flowId: Long, val kind: Int, val revision: Long, val ruleId: Long) : NativeEvent

    /** Observed transport outcome: 1 Direct, 2 Proxied, 3 Blocked, 4 Error. */
    class Route(override val flowId: Long, val kind: Int) : NativeEvent
}

object NativeEvents {
    private const val OPEN = 1
    private const val DATA = 2
    private const val CLOSE = 3
    private const val MESSAGE = 4
    private const val TLS_NOTE = 5
    private const val PROGRESS = 6

    /** Decodes whole events from `buf[0, len)`. Malformed trailing bytes are ignored. */
    fun decode(buf: ByteArray, len: Int): List<NativeEvent> {
        val out = ArrayList<NativeEvent>()
        var p = 0
        val end = minOf(len, buf.size)
        while (p + 13 <= end) {
            val total = u32(buf, p)
            if (total < 13 || p + total > end) break
            val type = buf[p + 4].toInt()
            val id = u64(buf, p + 5)
            val b = p + 13
            val e = p + total
            when {
                type == OPEN && e - b >= 38 -> {
                    val ver = buf[b + 1].toInt()
                    val n = if (ver == 4) 4 else 16
                    out += NativeEvent.Open(
                        id, buf[b].toInt() and 0xFF, ver, u16(buf, b + 2), u16(buf, b + 4),
                        InetAddress.getByAddress(buf.copyOfRange(b + 6, b + 6 + n)),
                        InetAddress.getByAddress(buf.copyOfRange(b + 22, b + 22 + n)),
                    )
                }
                type == DATA && e - b >= 3 -> {
                    val n = u16(buf, b + 1)
                    if (3 + n <= e - b) out += NativeEvent.Data(id, buf[b].toInt() == 1, buf.copyOfRange(b + 3, b + 3 + n))
                }
                type == MESSAGE -> decodeMessage(buf, b, e, id)?.let { out += it }
                type == TLS_NOTE && e - b >= 1 -> out += NativeEvent.TlsNote(id, buf[b].toInt())
                type == PROGRESS && e - b >= 9 -> out += NativeEvent.Progress(id, buf[b].toInt() == 1, u64(buf, b + 1))
                type == CLOSE && e - b >= 16 -> out += NativeEvent.Close(id, u64(buf, b), u64(buf, b + 8))
                type == 7 && e - b == 17 && buf[b].toInt() in 1..5 -> out += NativeEvent.Policy(id, buf[b].toInt(), u64(buf, b + 1), u64(buf, b + 9))
                type == 8 && e - b == 1 && buf[b].toInt() in 1..4 -> out += NativeEvent.Route(id, buf[b].toInt())
            }
            p = e
        }
        return out
    }

    private fun decodeMessage(buf: ByteArray, start: Int, end: Int, id: Long): NativeEvent.Message? {
        var p = start
        if (end - p < 4) return null
        val response = buf[p].toInt() == 1
        val status = u16(buf, p + 1)
        val mlen = buf[p + 3].toInt() and 0xFF
        p += 4
        if (p + mlen + 2 > end) return null
        val method = String(buf, p, mlen, Charsets.UTF_8)
        p += mlen
        val ulen = u16(buf, p)
        p += 2
        if (p + ulen + 4 > end) return null
        val url = String(buf, p, ulen, Charsets.UTF_8)
        p += ulen
        val hlen = u32(buf, p)
        p += 4
        if (hlen < 0 || p + hlen + 13 > end) return null
        val headers = String(buf, p, hlen, Charsets.UTF_8)
        p += hlen
        val total = u64(buf, p)
        val truncated = buf[p + 8].toInt() == 1
        val blen = u32(buf, p + 9)
        p += 13
        if (blen < 0 || p + blen > end) return null
        return NativeEvent.Message(id, response, status, method, url, headers, total, truncated, buf.copyOfRange(p, p + blen))
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)
    private fun u64(b: ByteArray, i: Int) = (u32(b, i).toLong() shl 32) or (u32(b, i + 4).toLong() and 0xFFFFFFFFL)
}
