package com.nathanhanapps.wayd.capture.parse

import java.io.ByteArrayOutputStream

data class ClientHello(val sni: String?, val alpn: List<String>, val tlsVersion: String?)

/** Extracts a complete ClientHello across handshake records. Incomplete or malformed input returns null. */
object TlsSniParser {
    private const val MAX_SNI = 255
    private const val MAX_PREFIX = 16 * 1024

    fun parse(b: ByteArray, off: Int = 0, len: Int = b.size - off): ClientHello? = try {
        if (off < 0 || off > b.size || len < 0) null else {
            val end = off + minOf(len, b.size - off, MAX_PREFIX)
            val handshake = ByteArrayOutputStream()
            var p = off
            var complete: ByteArray? = null
            while (p + 5 <= end && complete == null) {
                if (b[p].toInt() != 0x16 || b[p + 1].toInt() != 0x03) break
                val size = u16(b, p + 3)
                if (size == 0 || p + 5 + size > end) break
                handshake.write(b, p + 5, size)
                val bytes = handshake.toByteArray()
                if (bytes.size >= 4) {
                    if (bytes[0].toInt() != 1) break
                    val bodySize = ((bytes[1].toInt() and 0xFF) shl 16) or u16(bytes, 2)
                    if (bodySize > MAX_PREFIX - 4) break
                    if (bytes.size >= bodySize + 4) complete = bytes.copyOf(bodySize + 4)
                }
                p += 5 + size
            }
            complete?.let { parseUnsafe(it, 0, it.size) }
        }
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun parseUnsafe(b: ByteArray, start: Int, len: Int): ClientHello? {
        val end = start + len
        if (len < 42) return null
        var p = start + 4 // handshake type + 3-byte length
        val legacyVersion = u16(b, p)
        p += 2 + 32 // version + random
        p += 1 + (b[p].toInt() and 0xFF) // session id
        if (p + 2 > end) return null
        val cipherBytes = u16(b, p)
        if (cipherBytes == 0 || cipherBytes % 2 != 0) return null
        p += 2 + cipherBytes
        if (p >= end) return null
        val compressionBytes = b[p].toInt() and 0xFF
        if (compressionBytes == 0) return null
        p += 1 + compressionBytes
        if (p > end) return null
        if (p == end) return ClientHello(null, emptyList(), versionName(legacyVersion))
        if (p + 2 > end) return null
        val extEnd = p + 2 + u16(b, p)
        if (extEnd != end) return null
        p += 2
        var sni: String? = null
        val alpn = ArrayList<String>()
        var maxVersion = legacyVersion
        while (p + 4 <= extEnd) {
            val type = u16(b, p)
            val l = u16(b, p + 2)
            val body = p + 4
            if (body + l > extEnd) return null
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
                    val versionsEnd = q + (b[body].toInt() and 0xFF)
                    if (versionsEnd != body + l || (versionsEnd - q) % 2 != 0) return null
                    while (q + 2 <= versionsEnd) {
                        val version = u16(b, q)
                        // GREASE and future values must not hide a known negotiated offer.
                        if (version in 0x0301..0x0304) maxVersion = maxOf(maxVersion, version)
                        q += 2
                    }
                }
            }
            p = body + l
        }
        if (p != extEnd) return null
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
