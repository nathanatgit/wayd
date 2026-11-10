package com.nathanhanapps.wayd.capture.pcap

/** Classic hex view of the first [max] bytes: offset, 16 bytes as hex, printable ASCII gutter. */
object HexDump {
    fun format(bytes: ByteArray, max: Int = 4096): String {
        val n = minOf(bytes.size, max)
        val sb = StringBuilder(n * 4)
        var i = 0
        while (i < n) {
            sb.append("%08x  ".format(i))
            for (j in 0 until 16) {
                if (i + j < n) sb.append("%02x ".format(bytes[i + j].toInt() and 0xff)) else sb.append("   ")
                if (j == 7) sb.append(' ')
            }
            sb.append(" |")
            for (j in 0 until 16) {
                if (i + j >= n) break
                val c = bytes[i + j].toInt() and 0xff
                sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        return sb.toString().trimEnd('\n')
    }
}
