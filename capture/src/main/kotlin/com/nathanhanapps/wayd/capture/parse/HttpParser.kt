package com.nathanhanapps.wayd.capture.parse

data class HttpRequestHead(
    val method: String,
    val target: String,
    val host: String?,
    val headers: Map<String, String>,
    val headerBytes: Int,
)

/** Parses an HTTP/1.x request head from cleartext bytes. Size-limited; returns null if not HTTP. */
object HttpParser {
    const val MAX_HEAD = 16 * 1024
    private const val MAX_HEADERS = 64
    private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "CONNECT")

    fun parseRequest(b: ByteArray, len: Int = b.size): HttpRequestHead? {
        val n = minOf(len, b.size, MAX_HEAD)
        val end = indexOfHeadEnd(b, n)
        if (end < 0) return null
        val lines = String(b, 0, end, Charsets.ISO_8859_1).split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size != 3 || parts[0] !in METHODS || !parts[2].startsWith("HTTP/1.")) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1).take(MAX_HEADERS)) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            headers.putIfAbsent(line.substring(0, i).trim().lowercase(), line.substring(i + 1).trim())
        }
        return HttpRequestHead(parts[0], parts[1], headers["host"]?.lowercase(), headers, end + 4)
    }

    private fun indexOfHeadEnd(b: ByteArray, n: Int): Int {
        for (i in 0..n - 4) {
            if (b[i] == CR && b[i + 1] == LF && b[i + 2] == CR && b[i + 3] == LF) return i
        }
        return -1
    }

    private const val CR: Byte = 13
    private const val LF: Byte = 10
}
