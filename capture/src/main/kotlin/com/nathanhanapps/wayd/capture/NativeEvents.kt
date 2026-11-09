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

    class Close(override val flowId: Long, val bytesUp: Long, val bytesDown: Long) : NativeEvent
}

object NativeEvents {
    private const val OPEN = 1
    private const val DATA = 2
    private const val CLOSE = 3

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
                type == CLOSE && e - b >= 16 -> out += NativeEvent.Close(id, u64(buf, b), u64(buf, b + 8))
            }
            p = e
        }
        return out
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)
    private fun u64(b: ByteArray, i: Int) = (u32(b, i).toLong() shl 32) or (u32(b, i + 4).toLong() and 0xFFFFFFFFL)
}
