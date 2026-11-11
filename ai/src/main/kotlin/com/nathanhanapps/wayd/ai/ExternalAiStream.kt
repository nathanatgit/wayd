package com.nathanhanapps.wayd.ai

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** A raw byte stream to an external AI server that someone else connected, such as the user's Clash route (D-050). */
class ExternalStream(val input: InputStream, val output: OutputStream, val route: String, private val onClose: () -> Unit) {
    @Volatile private var closed = false

    fun close() {
        if (closed) return
        closed = true
        runCatching { onClose() }
    }
}

/**
 * Opens host:port for an external AI call outside the normal network path. Returns null when the normal network should be used.
 * Throws when it applies but cannot connect: the call must then fail, never fall back to a direct connection. `ai/` only sees
 * this interface; the app binds it to the routing variant in use.
 */
fun interface ExternalConnector {
    suspend fun open(host: String, port: Int): ExternalStream?

    companion object {
        val NONE = ExternalConnector { _, _ -> null }
    }
}

/**
 * Client TLS over arbitrary streams with an [SSLEngine]: certificate and hostname checks are the platform's (endpoint
 * identification `HTTPS`, SNI set), so a routed call is verified exactly like a direct one. Whoever carries the stream only sees
 * ciphertext.
 */
internal class EngineTls(
    host: String,
    port: Int,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
    context: SSLContext = SSLContext.getDefault(),
    private val verifier: HostnameVerifier? = null,
) {
    private val engine: SSLEngine = context.createSSLEngine(host, port).apply {
        useClientMode = true
        sslParameters = sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName(host))
        }
    }
    private val host = host
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize).flipped()
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize).flipped()
    private val netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var inboundClosed = false

    fun handshake() {
        engine.beginHandshake()
        var hs = engine.handshakeStatus
        while (true) {
            hs = when (hs) {
                HandshakeStatus.NEED_WRAP -> wrap(EMPTY)
                HandshakeStatus.NEED_UNWRAP ->(unwrap() ?: throw SSLException("closed during handshake")).handshakeStatus
                HandshakeStatus.NEED_TASK -> { runTasks(); engine.handshakeStatus }
                else -> break
            }
        }
        if (verifier != null && !verifier.verify(host, engine.session)) throw SSLPeerUnverifiedException("hostname")
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (!appIn.hasRemaining()) {
                if (inboundClosed) return -1
                val r = unwrap() ?: return -1
                if (r.status == SSLEngineResult.Status.CLOSED) inboundClosed = true
                when (r.handshakeStatus) {
                    HandshakeStatus.NEED_TASK -> runTasks()
                    HandshakeStatus.NEED_WRAP -> wrap(EMPTY) // key update or session tickets
                    else -> Unit
                }
            }
            val n = minOf(len, appIn.remaining())
            appIn.get(b, off, n)
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) { wrap(ByteBuffer.wrap(b, off, len)) }
        override fun flush() = rawOut.flush()
    }

    private fun wrap(src: ByteBuffer): HandshakeStatus {
        var hs: HandshakeStatus
        do {
            (netOut as Buffer).clear()
            val r = engine.wrap(src, netOut)
            if (r.status != SSLEngineResult.Status.OK) throw SSLException("wrap ${r.status}")
            netOut.flipped()
            rawOut.write(netOut.array(), netOut.arrayOffset(), netOut.limit())
            hs = r.handshakeStatus
        } while (src.hasRemaining())
        rawOut.flush()
        return hs
    }

    /** One unwrap that consumed a record, reading more network bytes as needed; null at end of stream. */
    private fun unwrap(): SSLEngineResult? {
        while (true) {
            appIn.compact()
            val r = try { engine.unwrap(netIn, appIn) } finally { appIn.flipped() }
            when (r.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> if (!fill()) return null
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    if (appIn.hasRemaining()) return r // let the reader drain first
                    appIn = ByteBuffer.allocate(engine.session.applicationBufferSize + appIn.capacity()).flipped()
                }
                else -> return r
            }
        }
    }

    private fun fill(): Boolean {
        netIn.compact()
        if (!netIn.hasRemaining()) {
            val bigger = ByteBuffer.allocate(netIn.capacity() + engine.session.packetBufferSize)
            netIn.flipped()
            bigger.put(netIn)
            netIn = bigger
        }
        val n = rawIn.read(netIn.array(), netIn.arrayOffset() + netIn.position(), netIn.remaining())
        if (n > 0) (netIn as Buffer).position(netIn.position() + n)
        netIn.flipped()
        return n > 0
    }

    private fun runTasks() {
        while (true) (engine.delegatedTask ?: return).run()
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /** Through [Buffer]: the covariant `ByteBuffer.flip()` does not exist on older Android versions. */
        fun ByteBuffer.flipped(): ByteBuffer = apply { (this as Buffer).flip() }
    }
}

/**
 * One HTTP/1.1 request and its bounded reply over already-open streams (`Connection: close`, no compression, no redirects).
 * Handles `Content-Length`, chunked and read-to-close bodies; a body cut short is an error, not a shorter answer.
 */
internal object StreamHttp {
    fun exchange(
        input: InputStream,
        output: OutputStream,
        method: String,
        authority: String,
        path: String,
        headers: List<Pair<String, String>>,
        body: ByteArray?,
        max: Int,
    ): Pair<Int, String> {
        val head = buildString {
            append(method).append(' ').append(path.ifEmpty { "/" }).append(" HTTP/1.1\r\n")
            append("Host: ").append(authority).append("\r\n")
            for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
            append("Accept-Encoding: identity\r\nConnection: close\r\n")
            if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body != null) output.write(body)
        output.flush()

        val inp = BufferedInputStream(input, 16 * 1024)
        var status: Int
        var length: Long
        var chunked: Boolean
        do { // skip interim 1xx responses
            status = line(inp).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")
            length = -1L
            chunked = false
            var count = 0
            while (true) {
                val h = line(inp)
                if (h.isEmpty()) break
                if (++count > 100) throw IOException("too many headers")
                val name = h.substringBefore(':').trim().lowercase()
                val value = h.substringAfter(':', "").trim()
                if (name == "content-length") length = value.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("bad length")
                if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
            }
        } while (status in 100..199)

        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        fun take(n: Long) {
            var left = n
            while (left > 0) {
                val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("body cut short")
                if (out.size() + r > max) throw ExternalAiException("ext_too_large")
                out.write(buf, 0, r)
                left -= r
            }
        }
        when {
            chunked -> while (true) {
                val size = line(inp).substringBefore(';').trim().toLongOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("bad chunk")
                if (size == 0L) {
                    while (line(inp).isNotEmpty()) Unit // trailers
                    break
                }
                take(size)
                if (line(inp).isNotEmpty()) throw IOException("bad chunk end")
            }
            length >= 0 -> take(length)
            else -> while (true) {
                val r = inp.read(buf)
                if (r < 0) break
                if (out.size() + r > max) throw ExternalAiException("ext_too_large")
                out.write(buf, 0, r)
            }
        }
        return status to out.toString(Charsets.UTF_8)
    }

    private fun line(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("connection closed")
            if (c == '\n'.code) break
            if (b.size() >= 8 * 1024) throw IOException("line too long")
            b.write(c)
        }
        return b.toString(Charsets.ISO_8859_1).trimEnd('\r')
    }
}
