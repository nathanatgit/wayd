package com.nathanhanapps.wayd.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/** An external request failed. [code] is a stable `ext_*` identifier the UI maps to text; it never contains a URL, key or payload. */
class ExternalAiException(val code: String, cause: Throwable? = null) : Exception(code, cause)

/** Where and as whom to ask: an OpenAI-compatible server (OpenAI, Ollama, vLLM, LM Studio, OpenRouter…). */
data class ExternalEndpoint(val baseUrl: String, val model: String, val apiKey: String?)

/**
 * Validates the user-entered base URL. HTTPS works anywhere; plain HTTP is accepted only for hosts that are on the user's own
 * network (loopback, private ranges, `.local`, single-label names) so a key or capture data can never cross the internet in
 * the clear. Hostnames are not resolved here, so this cannot be bypassed by DNS tricks: a public name needs HTTPS.
 */
object ExternalAiUrl {
    /** Canonical form (no trailing slash, no query/fragment) or null when the address is unusable. */
    fun normalise(input: String): String? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null || host.isEmpty()) return null
        if (scheme == "http" && !isPrivateHost(host)) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val shownHost = if (':' in host) "[$host]" else host
        return "$scheme://$shownHost$port${uri.rawPath.orEmpty().trimEnd('/')}"
    }

    /**
     * Which key may be sent to [url]: one the user just typed (they typed it for this address), else the stored key only if [url] is the
     * same server it was saved for. A stored key never follows an edited address to a different host.
     */
    fun keyFor(url: String, savedUrl: String, typed: String?, stored: String?): String? =
        typed?.takeIf { it.isNotBlank() }?.trim() ?: stored?.takeIf { origin(url) == origin(savedUrl) && origin(url).isNotEmpty() }

    fun host(baseUrl: String): String = runCatching { URI(baseUrl).host.orEmpty().removeSurrounding("[", "]") }.getOrDefault("")

    /** `scheme://host:port`, the unit a consent is recorded against. */
    fun origin(baseUrl: String): String = runCatching {
        val u = URI(baseUrl)
        "${u.scheme}://${u.host}" + if (u.port == -1) "" else ":${u.port}"
    }.getOrDefault("")

    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        if (':' in host) return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = parts[0].toInt() to parts[1].toInt()
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        return '.' !in host // a bare name such as "mypc" is an intranet host
    }
}

/**
 * Minimal OpenAI-compatible chat client over `HttpURLConnection` (no extra dependency, no redirects, bounded reads). It asks for
 * schema-constrained JSON first and steps down to plain JSON mode and then to a prompt-only request for servers that reject
 * `response_format`; the reply goes through [SchemaValidator] either way, so the server's behaviour never has to be trusted.
 */
class OpenAiCompatibleClient(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    /** Carries HTTPS calls to public hosts over the user's proxy route when it applies (D-050); private hosts never use it. */
    private val connector: ExternalConnector = ExternalConnector.NONE,
    /** Wraps a routed stream in TLS; tests replace it to talk plain HTTP to a local server. */
    private val secure: (ExternalStream, String, Int) -> Pair<java.io.InputStream, java.io.OutputStream> = { s, host, port ->
        EngineTls(host, port, s.input, s.output).run { handshake(); input to output }
    },
) {
    /** Request dialects, most capable first. A server that rejects one (HTTP 400/422) is retried with the next and remembered. */
    private enum class Format { SCHEMA_NO_THINKING, JSON_SCHEMA, JSON_OBJECT, NONE }

    private val known = java.util.concurrent.ConcurrentHashMap<String, Format>()

    suspend fun chat(ep: ExternalEndpoint, system: String, user: String, jsonSchema: String?): String {
        val base = ExternalAiUrl.normalise(ep.baseUrl) ?: throw ExternalAiException("ext_url")
        require(ep.model.isNotBlank())
        val key = base + "|" + ep.model
        val start = if (jsonSchema == null) Format.NONE else known[key] ?: Format.SCHEMA_NO_THINKING
        var level = start
        while (true) {
            val body = request(ep.model, system, user, jsonSchema, level)
            val (status, text) = post(URL("$base/chat/completions"), ep.apiKey, body)
            if ((status == 400 || status == 422) && level != Format.NONE) {
                level = Format.values()[level.ordinal + 1] // this server does not take that response_format
                continue
            }
            val content = parse(status, text)
            if (jsonSchema != null) known[key] = level
            return content
        }
    }

    /** Model ids the server offers (`GET /models`), also a cheap check that the address and key work. */
    suspend fun models(ep: ExternalEndpoint): List<String> {
        val base = ExternalAiUrl.normalise(ep.baseUrl) ?: throw ExternalAiException("ext_url")
        val (status, text) = get(URL("$base/models"), ep.apiKey)
        if (status !in 200..299) throw mapStatus(status)
        val data = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull() ?: throw ExternalAiException("ext_bad_reply")
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }.sorted()
    }

    private fun request(model: String, system: String, user: String, schema: String?, level: Format): String {
        val o = JSONObject().put("model", model).put("temperature", 0.2).put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        when (level) {
            Format.SCHEMA_NO_THINKING, Format.JSON_SCHEMA -> {
                // Reasoning models (qwen3.5, gpt-oss, ...) can spend the whole context thinking and answer nothing. Ask them not to; servers
                // that do not know the field answer 400 and the next dialect is used.
                if (level == Format.SCHEMA_NO_THINKING) o.put("reasoning_effort", "none")
                o.put("response_format", JSONObject().put("type", "json_schema")
                    .put("json_schema", JSONObject().put("name", "wayd_analysis").put("strict", false).put("schema", JSONObject(requireNotNull(schema)))))
            }
            Format.JSON_OBJECT -> o.put("response_format", JSONObject().put("type", "json_object"))
            Format.NONE -> Unit
        }
        return o.toString()
    }

    private fun parse(status: Int, text: String): String {
        if (status !in 200..299) throw mapStatus(status)
        val message = runCatching { JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message") }
            .getOrNull() ?: throw ExternalAiException("ext_bad_reply")
        val raw = message.opt("content")
        val content = when (raw) {
            is String -> raw
            is JSONArray -> (0 until raw.length()).joinToString("") { raw.optJSONObject(it)?.optString("text").orEmpty() }
            else -> ""
        }
        // Some reasoning models inline their thoughts; the answer is what follows.
        val answer = content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        if (answer.isEmpty()) throw ExternalAiException("ext_empty")
        return answer
    }

    private fun mapStatus(status: Int) = ExternalAiException(
        when (status) {
            401, 403 -> "ext_auth"
            404 -> "ext_not_found"
            408, 504 -> "ext_timeout"
            429 -> "ext_rate"
            in 300..399 -> "ext_redirect"
            else -> "ext_http_$status"
        },
    )

    private suspend fun post(url: URL, key: String?, body: String) =
        routedOr(url, key, "POST", body.toByteArray()) { call(url, key) { it.requestMethod = "POST"; it.doOutput = true; it.setRequestProperty("Content-Type", "application/json"); it.outputStream.use { o -> o.write(body.toByteArray()) } } }

    private suspend fun get(url: URL, key: String?) = routedOr(url, key, "GET", null) { call(url, key) { it.requestMethod = "GET" } }

    /**
     * HTTPS to a public host goes through [connector] when it applies; otherwise [direct]. A connector that applies but fails ends
     * the call (`ext_route`): the evidence is never sent around the user's chosen route.
     */
    private suspend fun routedOr(url: URL, key: String?, method: String, body: ByteArray?, direct: suspend () -> Pair<Int, String>): Pair<Int, String> {
        val host = url.host.lowercase()
        if (url.protocol != "https" || ExternalAiUrl.isPrivateHost(host)) return direct()
        val port = if (url.port == -1) 443 else url.port
        val stream = try {
            connector.open(host, port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ExternalAiException("ext_route", e)
        } ?: return direct()
        return overStream(stream, url, host, port, key, method, body)
    }

    /** One request over a routed stream. A timer closes the stream after the read timeout; cancelling the call closes it too. */
    private suspend fun overStream(s: ExternalStream, url: URL, host: String, port: Int, key: String?, method: String, body: ByteArray?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                var timedOut = false
                val timer = launch {
                    try { delay(READ_TIMEOUT_MS.toLong()); timedOut = true } finally { s.close() }
                }
                try {
                    val (input, output) = secure(s, host, port)
                    val headers = buildList {
                        add("Accept" to "application/json")
                        add("User-Agent" to "WAYD-ai")
                        if (body != null) add("Content-Type" to "application/json")
                        if (!key.isNullOrBlank()) add("Authorization" to "Bearer $key")
                    }
                    val authority = if (port == 443) host else "$host:$port"
                    val path = url.path + (url.query?.let { "?$it" } ?: "")
                    StreamHttp.exchange(input, output, method, authority, path, headers, body, MAX_REPLY)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ExternalAiException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) throw CancellationException("cancelled")
                    throw ExternalAiException(if (timedOut) "ext_timeout" else classify(e), e)
                } finally {
                    timer.cancel()
                    s.close()
                }
            }
        }

    /** One bounded request. Cancelling the coroutine closes the socket, so a stuck LAN model never holds a thread. */
    private suspend fun call(url: URL, key: String?, send: (HttpURLConnection) -> Unit): Pair<Int, String> = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        var conn: HttpURLConnection? = null
        val watch = job?.invokeOnCompletion { if (it != null) runCatching { conn?.disconnect() } }
        try {
            val c = open(url)
            conn = c
            c.instanceFollowRedirects = false // never replay a bearer token to a redirect target
            c.connectTimeout = 15_000
            c.readTimeout = READ_TIMEOUT_MS // a cold local model can take minutes to answer the first call
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "WAYD-ai")
            if (!key.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $key")
            send(c)
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            status to (stream?.use { readBounded(it) } ?: "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExternalAiException) {
            throw e
        } catch (e: Exception) {
            if (job?.isActive == false) throw CancellationException("cancelled")
            throw ExternalAiException(classify(e), e)
        } finally {
            watch?.dispose()
            runCatching { conn?.disconnect() }
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_REPLY) throw ExternalAiException("ext_too_large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun classify(e: Exception) = when (e) {
        is UnknownHostException -> "ext_dns"
        is SocketTimeoutException -> "ext_timeout"
        is SSLException -> "ext_tls"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "ext_connect"
        is IOException -> "ext_network"
        else -> "ext_network"
    }

    private companion object {
        const val MAX_REPLY = 512 * 1024
        const val READ_TIMEOUT_MS = 300_000
    }
}
