package com.nathanhanapps.wayd.ai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress

class ExternalAiUrlTest {
    @Test fun httpsIsAcceptedAnywhereAndCanonicalised() {
        assertEquals("https://api.openai.com/v1", ExternalAiUrl.normalise(" https://api.openai.com/v1/ "))
        assertEquals("https://example.com:8443/api/v1", ExternalAiUrl.normalise("https://EXAMPLE.com:8443/api/v1///"))
    }

    @Test fun plainHttpOnlyForTheUsersOwnNetwork() {
        for (ok in listOf("http://localhost:11434/v1", "http://127.0.0.1:11434/v1", "http://192.168.31.169:11434/v1", "http://10.0.0.2/v1",
            "http://172.20.1.5/v1", "http://100.100.1.1/v1", "http://mypc:11434/v1", "http://nas.local/v1", "http://[::1]:11434/v1")) {
            assertNotNull(ok, ExternalAiUrl.normalise(ok))
        }
        for (bad in listOf("http://api.openai.com/v1", "http://8.8.8.8/v1", "http://172.32.0.1/v1", "http://192.169.1.1/v1", "http://example.com/v1")) {
            assertNull(bad, ExternalAiUrl.normalise(bad))
        }
    }

    @Test fun rejectsCredentialsQueriesAndOtherSchemes() {
        for (bad in listOf("ftp://example.com", "https://user:pw@example.com/v1", "https://example.com/v1?key=1", "https://example.com/v1#x", "example.com/v1", "", "file:///etc/passwd")) {
            assertNull(bad, ExternalAiUrl.normalise(bad))
        }
    }

    @Test fun consentIsBoundToTheOrigin() {
        assertEquals("http://192.168.31.169:11434", ExternalAiUrl.origin("http://192.168.31.169:11434/v1"))
        assertEquals("https://api.openai.com", ExternalAiUrl.origin("https://api.openai.com/v1"))
    }
}

class OpenAiCompatibleClientTest {
    private var server: HttpServer? = null
    private val seen = ArrayList<Triple<String, String?, JSONObject?>>() // path, authorization, body

    @After fun stop() { server?.stop(0) }

    private fun serve(handler: (path: String, body: JSONObject?, ex: HttpExchange) -> Pair<Int, String>): ExternalEndpoint {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val raw = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = raw.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            seen += Triple(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), body)
            val (code, text) = handler(ex.requestURI.path, body, ex)
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong().coerceAtLeast(if (code == 204) -1 else 0))
            if (code != 204) ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return ExternalEndpoint("http://127.0.0.1:${s.address.port}/v1", "demo-model", "sk-test")
    }

    private fun reply(content: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()

    @Test fun sendsAnOpenAiChatRequestWithSchemaAndBearerKey() = runBlocking {
        val ep = serve { _, _, _ -> 200 to reply("""{"ok":true}""") }
        val out = OpenAiCompatibleClient().chat(ep, "SYS", "USER", PromptBuilder.JSON_SCHEMA)
        assertEquals("""{"ok":true}""", out)
        val (path, auth, body) = seen.single()
        assertEquals("/v1/chat/completions", path)
        assertEquals("Bearer sk-test", auth)
        assertEquals("demo-model", body!!.getString("model"))
        assertFalse(body.getBoolean("stream"))
        val msgs = body.getJSONArray("messages")
        assertEquals("SYS", msgs.getJSONObject(0).getString("content"))
        assertEquals("USER", msgs.getJSONObject(1).getString("content"))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertEquals("thinking is switched off where the server understands it", "none", body.getString("reasoning_effort"))
        assertEquals("object", body.getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema").getString("type"))
    }

    @Test fun noKeyMeansNoAuthorizationHeader() = runBlocking {
        val ep = serve { _, _, _ -> 200 to reply("{}") }.copy(apiKey = null)
        OpenAiCompatibleClient().chat(ep, "s", "u", null)
        assertNull(seen.single().second)
        assertFalse(seen.single().third!!.has("response_format"))
    }

    @Test fun stepsDownWhenTheServerRejectsResponseFormatAndRemembersIt() = runBlocking {
        val ep = serve { _, body, _ ->
            val type = body?.optJSONObject("response_format")?.optString("type")
            when {
                body?.has("reasoning_effort") == true -> 400 to """{"error":"unknown field"}"""
                type == "json_schema" -> 400 to """{"error":"unsupported"}"""
                type == "json_object" -> 422 to "{}"
                else -> 200 to reply("""{"a":1}""")
            }
        }
        val client = OpenAiCompatibleClient()
        assertEquals("""{"a":1}""", client.chat(ep, "s", "u", PromptBuilder.JSON_SCHEMA))
        assertEquals(4, seen.size)
        seen.clear()
        client.chat(ep, "s", "u", PromptBuilder.JSON_SCHEMA)
        assertEquals("the working format is remembered", 1, seen.size)
    }

    @Test fun acceptsContentPartsAndDropsInlineThinking() = runBlocking {
        val parts = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content",
            org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "<think>hmm</think>{\"x\":")).put(JSONObject().put("type", "text").put("text", "1}")))))).toString()
        val ep = serve { _, _, _ -> 200 to parts }
        assertEquals("""{"x":1}""", OpenAiCompatibleClient().chat(ep, "s", "u", null))
    }

    @Test fun mapsFailuresToStableCodes() = runBlocking {
        val codes = mapOf(401 to "ext_auth", 403 to "ext_auth", 404 to "ext_not_found", 429 to "ext_rate", 500 to "ext_http_500", 302 to "ext_redirect")
        for ((status, code) in codes) {
            val ep = serve { _, _, _ -> status to "{}" }
            val e = runCatching { OpenAiCompatibleClient().chat(ep, "s", "u", null) }.exceptionOrNull() as ExternalAiException
            assertEquals("status $status", code, e.code)
            server?.stop(0)
        }
    }

    @Test fun emptyOrMalformedRepliesAreErrorsNotAnswers() = runBlocking {
        var next = reply("   ")
        val ep = serve { _, _, _ -> 200 to next }
        val client = OpenAiCompatibleClient()
        assertEquals("ext_empty", (runCatching { client.chat(ep, "s", "u", null) }.exceptionOrNull() as ExternalAiException).code)
        next = "not json"
        assertEquals("ext_bad_reply", (runCatching { client.chat(ep, "s", "u", null) }.exceptionOrNull() as ExternalAiException).code)
    }

    @Test fun refusesPublicPlainHttpEvenIfConfiguredDirectly() = runBlocking {
        val e = runCatching { OpenAiCompatibleClient().chat(ExternalEndpoint("http://api.example.com/v1", "m", "k"), "s", "u", null) }.exceptionOrNull() as ExternalAiException
        assertEquals("ext_url", e.code)
        assertTrue(seen.isEmpty())
    }

    @Test fun connectionRefusedIsReportedWithoutLeakingTheAddress() = runBlocking {
        val e = runCatching { OpenAiCompatibleClient().chat(ExternalEndpoint("http://127.0.0.1:1/v1", "m", "secret-key"), "s", "u", null) }.exceptionOrNull() as ExternalAiException
        assertEquals("ext_connect", e.code)
        assertFalse("127.0.0.1" in e.message.orEmpty() || "secret-key" in e.message.orEmpty())
    }

    @Test fun listsModelsSorted() = runBlocking {
        val ep = serve { _, _, _ -> 200 to """{"object":"list","data":[{"id":"qwen3.5:4b"},{"id":"gemma4:e2b"},{"id":""}]}""" }
        assertEquals(listOf("gemma4:e2b", "qwen3.5:4b"), OpenAiCompatibleClient().models(ep))
        assertEquals("/v1/models", seen.single().first)
    }

    /** Opt-in: `WAYD_OLLAMA_URL=http://localhost:11434/v1 WAYD_OLLAMA_MODEL=gemma4:e2b ./gradlew :ai:testDebugUnitTest`. */
    @Test fun liveOllamaAnswersTheRealAnalysisPrompt() = runBlocking {
        val url = System.getenv("WAYD_OLLAMA_URL")
        val model = System.getenv("WAYD_OLLAMA_MODEL")
        assumeTrue("set WAYD_OLLAMA_URL and WAYD_OLLAMA_MODEL to run", !url.isNullOrBlank() && !model.isNullOrBlank())
        val ep = ExternalEndpoint(url!!, model!!, null)
        val client = OpenAiCompatibleClient()
        assertTrue(model in client.models(ep))
        val engine = object : AiEngine {
            override suspend fun isReady() = true
            override suspend fun modelId() = "ext:$model"
            override suspend fun externalDestination() = "ollama"
            override suspend fun generate(system: String, user: String, jsonSchema: String?) = client.chat(ep, system, user, jsonSchema)
        }
        val req = AiRequest("Demo (com.demo), window today", listOf(
            EvidenceItem("f:1", "TCP graph.facebook.com:443 up=48213B down=1200B screen=off net=WIFI company=Meta category=ANALYTICS"),
            EvidenceItem("f:2", "TCP api.demo.com:443 up=1200B down=88000B screen=on net=WIFI company=? category=?"),
        ), listOf("R-BG-UPLOAD [MEDIUM] graph.facebook.com"), locale = "en")
        var shown: ExternalSend? = null
        val out = AiAnalyzer(engine, object : AnalysisStore {
            override suspend fun cached(refsKey: String, promptVersion: Int): String? = null
            override suspend fun save(refsKey: String, model: String, promptVersion: Int, resultJson: String, validated: Boolean, durationMs: Long) = Unit
        }).analyse(req, useCache = false) { shown = it; true }
        println("live outcome: $out")
        assertNotNull(shown)
        assertTrue("expected a validated analysis, got $out", out is AiOutcome.Success)
    }
}

class ExternalApprovalGateTest {
    private class Store : AnalysisStore {
        override suspend fun cached(refsKey: String, promptVersion: Int): String? = null
        override suspend fun save(refsKey: String, model: String, promptVersion: Int, resultJson: String, validated: Boolean, durationMs: Long) = Unit
    }

    private class Engine(val destination: String?, val reply: String) : AiEngine {
        var calls = 0
        override suspend fun isReady() = true
        override suspend fun modelId() = if (destination != null) "ext:big-model" else "gemma"
        override suspend fun externalDestination() = destination
        override suspend fun generate(system: String, user: String, jsonSchema: String?): String { calls++; return reply }
    }

    private val good = """{"schema_version":1,"summary":"ok","verdict":"benign","confidence":0.5,"destinations":[{"host":"t.example.com","purpose_guess":"analytics","evidence_ids":["f:1"]}]}"""
    private val req = AiRequest("App", listOf(EvidenceItem("f:1", "GET t.example.com android_id=9774d56d682e549c"), EvidenceItem("m:2", "decrypted body")))

    @Test fun declinedMeansNothingIsSent() = runBlocking {
        val engine = Engine("api.example.com", good)
        assertEquals(AiOutcome.Declined, AiAnalyzer(engine, Store()).analyse(req) { false })
        assertEquals(0, engine.calls)
    }

    @Test fun approvalIsFailClosedByDefault() = runBlocking {
        val engine = Engine("api.example.com", good)
        assertEquals(AiOutcome.Declined, AiAnalyzer(engine, Store()).analyse(req))
        assertEquals(0, engine.calls)
    }

    @Test fun approvedRequestShowsExactMaskedTextAndFlagsDecryptedContent() = runBlocking {
        val engine = Engine("api.example.com", good)
        var shown: ExternalSend? = null
        val out = AiAnalyzer(engine, Store()).analyse(req) { shown = it; true }
        assertTrue(out is AiOutcome.Success)
        assertEquals(1, engine.calls)
        val s = shown!!
        assertEquals("api.example.com", s.destination)
        assertEquals("big-model", s.model)
        assertTrue(s.hasDecrypted)
        assertEquals(2, s.items)
        assertTrue("[ANDROID_ID]" in s.user && "9774d56d682e549c" !in s.user)
    }

    @Test fun onDeviceModelIsNeverAskedToApprove() = runBlocking {
        val engine = Engine(null, good)
        var asked = false
        AiAnalyzer(engine, Store()).analyse(req) { asked = true; false }
        assertFalse(asked)
        assertEquals(1, engine.calls)
    }

    @Test fun storedAnswersAreKeptPerModel() = runBlocking {
        val saved = ArrayList<String>()
        val store = object : AnalysisStore {
            override suspend fun cached(refsKey: String, promptVersion: Int): String? = if (saved.contains(refsKey)) good else null
            override suspend fun save(refsKey: String, model: String, promptVersion: Int, resultJson: String, validated: Boolean, durationMs: Long) { saved += refsKey }
        }
        AiAnalyzer(Engine(null, good), store).analyse(req)
        val ext = Engine("api.example.com", good)
        val out = AiAnalyzer(ext, store).analyse(req) { true } as AiOutcome.Success
        assertFalse("a Gemma answer must not be served as the external model's", out.fromCache)
        assertEquals(1, ext.calls)
    }
}

class ExternalAiKeyRoutingTest {
    private val saved = "https://api.example.com/v1"

    @Test fun storedKeyGoesOnlyToTheServerItWasSavedFor() {
        assertEquals("stored", ExternalAiUrl.keyFor("https://api.example.com/v1", saved, null, "stored"))
        assertEquals("same origin, different path", "stored", ExternalAiUrl.keyFor("https://api.example.com/other", saved, null, "stored"))
        assertNull("edited host", ExternalAiUrl.keyFor("https://api.example.co/v1", saved, null, "stored"))
        assertNull("half-typed host", ExternalAiUrl.keyFor("https://api.exam/v1", saved, null, "stored"))
        assertNull("different scheme/port", ExternalAiUrl.keyFor("http://api.example.com:8080/v1", saved, null, "stored"))
        assertNull("nothing saved yet", ExternalAiUrl.keyFor("https://api.example.com/v1", "", null, "stored"))
    }

    @Test fun aTypedKeyIsUsedForTheAddressItWasTypedFor() {
        assertEquals("typed", ExternalAiUrl.keyFor("https://other.example.org/v1", saved, "  typed ", "stored"))
        assertEquals("blank typed key falls back to the stored rule", "stored", ExternalAiUrl.keyFor("https://api.example.com/v1", saved, "  ", "stored"))
    }
}

class ExternalAiDialectTest {
    private var server: HttpServer? = null
    @After fun stop() { server?.stop(0) }

    @Test fun aServerThatRejectsOnlyTheThinkingHintKeepsSchemaOutput() = runBlocking {
        val bodies = ArrayList<JSONObject>()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)).also { bodies += it }
            val (code, text) = if (body.has("reasoning_effort")) 400 to "{}" else 200 to """{"choices":[{"message":{"content":"{\"ok\":1}"}}]}"""
            val bytes = text.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        s.start(); server = s
        val out = OpenAiCompatibleClient().chat(ExternalEndpoint("http://127.0.0.1:${s.address.port}/v1", "gpt-x", "k"), "s", "u", PromptBuilder.JSON_SCHEMA)
        assertEquals("""{"ok":1}""", out)
        assertEquals(2, bodies.size)
        assertEquals("json_schema", bodies[1].getJSONObject("response_format").getString("type"))
        assertFalse(bodies[1].has("reasoning_effort"))
    }
}
