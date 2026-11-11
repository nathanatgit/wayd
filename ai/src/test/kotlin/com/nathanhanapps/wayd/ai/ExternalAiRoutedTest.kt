package com.nathanhanapps.wayd.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** D-050: external AI calls over a routed stream, with TLS replaced by plain streams so a local server can answer. */
class ExternalAiRoutedTest {
    private var server: HttpServer? = null
    private val seen = ArrayList<Triple<String, String?, String>>() // path, authorization, host header

    @After fun stop() { server?.stop(0) }

    private fun serve(reply: String): Int {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            ex.requestBody.readBytes()
            seen += Triple(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestHeaders.getFirst("Host"))
            val bytes = reply.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return s.address.port
    }

    private fun plain(): (ExternalStream, String, Int) -> Pair<java.io.InputStream, java.io.OutputStream> = { s, _, _ -> s.input to s.output }

    private fun noDirect(): (java.net.URL) -> java.net.HttpURLConnection = { fail("must not go direct: $it"); throw IllegalStateException() }

    @Test fun publicHttpsGoesOverTheRoutedStream() = runBlocking {
        val port = serve("""{"data":[{"id":"m1"}],"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"}}]}""")
        val opened = ArrayList<Pair<String, Int>>()
        val connector = ExternalConnector { host, p ->
            opened += host to p
            val socket = Socket("127.0.0.1", port)
            ExternalStream(socket.getInputStream(), socket.getOutputStream(), "Proxied") { socket.close() }
        }
        val client = OpenAiCompatibleClient(open = noDirect(), connector = connector, secure = plain())
        val ep = ExternalEndpoint("https://generativelanguage.googleapis.com/v1beta/openai", "m1", "k-test")
        assertEquals(listOf("m1"), client.models(ep))
        assertEquals("""{"ok":true}""", client.chat(ep, "SYS", "USER", null))
        assertEquals(listOf("generativelanguage.googleapis.com" to 443, "generativelanguage.googleapis.com" to 443), opened)
        assertEquals("/v1beta/openai/models", seen[0].first)
        assertEquals("/v1beta/openai/chat/completions", seen[1].first)
        assertTrue(seen.all { it.second == "Bearer k-test" && it.third == "generativelanguage.googleapis.com" })
    }

    @Test fun aRouteThatFailsNeverFallsBackToDirect() = runBlocking {
        val client = OpenAiCompatibleClient(open = noDirect(), connector = { _, _ -> throw IOException("route_failed") }, secure = plain())
        try {
            client.models(ExternalEndpoint("https://api.example.com/v1", "m", null))
            fail("expected ext_route")
        } catch (e: ExternalAiException) {
            assertEquals("ext_route", e.code)
        }
    }

    @Test fun privateHostsAndPlainHttpNeverUseTheRoute() = runBlocking {
        var asked = false
        val connector = ExternalConnector { _, _ -> asked = true; null }
        val client = OpenAiCompatibleClient(open = { throw IOException("direct attempted") }, connector = connector, secure = plain())
        for (url in listOf("https://192.168.31.169:8443/v1", "http://192.168.31.169:11434/v1", "https://mypc/v1")) {
            try { client.models(ExternalEndpoint(url, "m", null)) } catch (e: ExternalAiException) { assertEquals("ext_network", e.code) }
        }
        assertFalse("private servers stay on the local network", asked)
    }

    @Test fun nullFromTheConnectorMeansTheNormalNetwork() = runBlocking {
        val client = OpenAiCompatibleClient(open = { throw IOException("direct attempted") }, connector = ExternalConnector.NONE, secure = plain())
        try { client.models(ExternalEndpoint("https://api.example.com/v1", "m", null)) ; fail() } catch (e: ExternalAiException) { assertEquals("ext_network", e.code) }
    }
}

class StreamHttpTest {
    private fun run(reply: String, max: Int = 1024, body: ByteArray? = "{}".toByteArray()): Pair<Pair<Int, String>, String> {
        val sent = ByteArrayOutputStream()
        val out = StreamHttp.exchange(ByteArrayInputStream(reply.toByteArray()), sent, "POST", "api.example.com", "/v1/chat/completions",
            listOf("Authorization" to "Bearer k"), body, max)
        return out to sent.toString(Charsets.ISO_8859_1)
    }

    @Test fun writesOneCloseDelimitedRequest() {
        val (_, sent) = run("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        assertTrue(sent.startsWith("POST /v1/chat/completions HTTP/1.1\r\nHost: api.example.com\r\nAuthorization: Bearer k\r\n"))
        assertTrue(sent.contains("Accept-Encoding: identity\r\nConnection: close\r\nContent-Length: 2\r\n\r\n{}"))
    }

    @Test fun readsLengthChunkedAndCloseDelimitedBodies() {
        assertEquals(200 to "ok", run("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nokEXTRA").first)
        assertEquals(200 to "hello world", run("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;x=1\r\n world\r\n0\r\nT: v\r\n\r\n").first)
        assertEquals(404 to "gone", run("HTTP/1.1 404 Not Found\r\n\r\ngone").first)
        assertEquals(200 to "x", run("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nx").first)
    }

    @Test fun rejectsTruncatedOversizedAndMalformedReplies() {
        for (bad in listOf("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n", "garbage\r\n\r\n", "")) {
            try { run(bad); fail("accepted: $bad") } catch (e: IOException) { /* expected */ }
        }
        try { run("HTTP/1.1 200 OK\r\nContent-Length: 2000\r\n\r\n" + "a".repeat(2000)); fail() } catch (e: ExternalAiException) { assertEquals("ext_too_large", e.code) }
    }

    @Test fun replyJsonSurvivesTheRoundTrip() {
        val json = JSONObject().put("k", "值").toString()
        val bytes = json.toByteArray()
        val (out, _) = run("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n$json")
        assertEquals(json, out.second)
    }
}
