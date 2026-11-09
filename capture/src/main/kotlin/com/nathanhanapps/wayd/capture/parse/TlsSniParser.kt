package com.nathanhanapps.wayd.capture.parse

data class ClientHello(val sni: String?, val alpn: List<String>, val tlsVersion: String?)

/** Extracts SNI / ALPN from the first TLS record of a connection. Returns null if not a ClientHello. */
object TlsSniParser {
    private const val MAX_SNI = 255

    fun parse(b: ByteArray, off: Int = 0, len: Int = b.size - off): ClientHello? = try {
        parseUnsafe(b, off, minOf(len, b.size - off))
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun parseUnsafe(b: ByteArray, start: Int, len: Int): ClientHello? {
        val end = start + len
        if (len < 43 || b[start].toInt() != 0x16 || b[start + 1].toInt() != 0x03) return null
        var p = start + 5
        if (b[p].toInt() != 0x01) return null // handshake type: ClientHello
        p += 4 // type + 3-byte length
        val legacyVersion = u16(b, p)
        p += 2 + 32 // version + random
        p += 1 + (b[p].toInt() and 0xFF) // session id
        p += 2 + u16(b, p) // cipher suites
        p += 1 + (b[p].toInt() and 0xFF) // compression methods
        if (p + 2 > end) return ClientHello(null, emptyList(), versionName(legacyVersion))
        val extEnd = minOf(p + 2 + u16(b, p), end)
        p += 2
        var sni: String? = null
        val alpn = ArrayList<String>()
        var maxVersion = legacyVersion
        var guard = 0
        while (p + 4 <= extEnd && ++guard < 64) {
            val type = u16(b, p)
            val l = u16(b, p + 2)
            val body = p + 4
            if (body + l > extEnd) break
            when (type) {
                0x0000 -> if (l >= 5 && b[body + 2].toInt() == 0) {
                    val n = u16(b, body + 3)
                    if (n in 1..MAX_SNI && 5 + n <= l) sni = ascii(b, body + 5, n)?.lowercase()
                }
                0x0010 -> if (l >= 2) {
                    var q = body + 2
                    val listEnd = minOf(body + 2 + u16(b, body), body + l)
                    while (q < listEnd && alpn.size < 16) {
                        val n = b[q].toInt() and 0xFF
                        if (q + 1 + n > listEnd) break
                        ascii(b, q + 1, n)?.let { alpn += it }
                        q += 1 + n
                    }
                }
                0x002B -> if (l >= 3) { // supported_versions (client list)
                    var q = body + 1
                    while (q + 2 <= body + l) {
                        maxVersion = maxOf(maxVersion, u16(b, q))
                        q += 2
                    }
                }
            }
            p = body + l
        }
        return ClientHello(sni, alpn, versionName(maxVersion))
    }

    private fun ascii(b: ByteArray, off: Int, n: Int): String? {
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val c = b[off + i].toInt() and 0xFF
            if (c !in 0x21..0x7E) return null
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun versionName(v: Int) = when (v) {
        0x0304 -> "TLS1.3"
        0x0303 -> "TLS1.2"
        0x0302 -> "TLS1.1"
        0x0301 -> "TLS1.0"
        else -> null
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
