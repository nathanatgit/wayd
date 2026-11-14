package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.model.BlockAdvice
import com.nathanhanapps.wayd.core.model.ConnectionRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in answer-quality check for the whole-history prompt, so prompt changes can be compared by a number instead of by feel:
 * `WAYD_OLLAMA_URL=http://localhost:11434/v1 WAYD_OLLAMA_MODEL=gemma4:e2b ./gradlew :ai:testDebugUnitTest --tests '*HistoryEvalTest*'`
 * (add `WAYD_EXTERNAL_KEY` for a hosted server; `WAYD_EVAL_SIZE=SMALL|MEDIUM|LARGE` picks the chunk size, default SMALL as on E2B).
 * The fixtures are synthetic hosts with an obvious purpose; a model that cannot place most of them is not helping the user.
 */
class HistoryEvalTest {
    private class Case(val line: String, val blockable: Boolean, val roles: Set<ConnectionRole>, val advice: Set<BlockAdvice>)

    private val cases = listOf(
        Case("ads.adnetwork.example hint=ADS rec=14 up=20480 down=88000 off=10% apk perm=AD_ID,LOCATION comp=SplashAdActivity", true,
            setOf(ConnectionRole.ADS), setOf(BlockAdvice.TRY_TEMPORARY)),
        Case("log.metrics.example hint=ANALYTICS rec=40 up=96000 down=4000 off=85% apk perm=PHONE*,APP_LIST", true,
            setOf(ConnectionRole.ANALYTICS, ConnectionRole.DIAGNOSTICS), setOf(BlockAdvice.TRY_TEMPORARY)),
        Case("crash.reports.example hint=DIAGNOSTICS rec=2 up=30000 down=600 off=0% apk", true,
            setOf(ConnectionRole.DIAGNOSTICS, ConnectionRole.ANALYTICS), setOf(BlockAdvice.TRY_TEMPORARY, BlockAdvice.MAY_AFFECT_FUNCTION)),
        Case("api.shop.example hint=UNKNOWN rec=60 up=40000 down=900000 off=5% apk", true,
            setOf(ConnectionRole.FUNCTIONAL), setOf(BlockAdvice.MAY_AFFECT_FUNCTION)),
        Case("passport.shop.example hint=LOGIN rec=3 up=3000 down=5000 off=0% apk perm=ACCOUNTS", true,
            setOf(ConnectionRole.LOGIN), setOf(BlockAdvice.MAY_AFFECT_FUNCTION)),
        Case("img.cdn.example hint=CONTENT rec=120 up=9000 down=8000000 off=2%", true,
            setOf(ConnectionRole.CONTENT, ConnectionRole.MEDIA), setOf(BlockAdvice.MAY_AFFECT_FUNCTION)),
        Case("push.vendor.example hint=PUSH rec=6 up=2000 down=2500 off=90% perm=PUSH comp=PushService", true,
            setOf(ConnectionRole.PUSH), setOf(BlockAdvice.MAY_AFFECT_FUNCTION)),
        Case("203-0-113-9.unnamed.example hint=UNKNOWN rec=1 up=0 down=0", false,
            ConnectionRole.entries.toSet(), setOf(BlockAdvice.INSUFFICIENT)),
    )

    @Test fun liveModelPlacesObviousHosts() = runBlocking {
        val url = System.getenv("WAYD_OLLAMA_URL")
        val model = System.getenv("WAYD_OLLAMA_MODEL")
        assumeTrue("set WAYD_OLLAMA_URL and WAYD_OLLAMA_MODEL to run", !url.isNullOrBlank() && !model.isNullOrBlank())
        val size = runCatching { ContextSize.valueOf(System.getenv("WAYD_EVAL_SIZE").orEmpty()) }.getOrDefault(ContextSize.SMALL)
        val ep = ExternalEndpoint(url!!, model!!, System.getenv("WAYD_EXTERNAL_KEY")?.takeIf { it.isNotBlank() })
        val client = OpenAiCompatibleClient()
        val hosts = cases.map { HistoryHost(it.line.substringBefore(' '), it.line, it.blockable) }
        val notes = HashMap<String, HistoryNote>()
        var invalid = 0
        hosts.chunked(HistoryContract.chunkSize(size)).forEach { chunk ->
            // The compact prompt is what on-device Gemma gets; the server only stands in for the phone.
            val prompt = HistoryContract.prompt("com.example.shop", "en", chunk, compact = true)
            val parsed = HistoryContract.parse(client.chat(ep, prompt.system, prompt.user, HistoryContract.schema(chunk.size)), chunk)
            if (parsed == null) invalid++ else notes.putAll(parsed)
        }
        var roleOk = 0
        var adviceOk = 0
        cases.forEach { c ->
            val n = notes[c.line.substringBefore(' ')]
            if (n?.role in c.roles) roleOk++
            if (n?.advice in c.advice) adviceOk++
            println("EVAL ${c.line.substringBefore(' ')} -> ${n?.role}/${n?.advice} ${n?.note.orEmpty()}")
        }
        println("EVAL model=$model size=$size answered=${notes.size}/${cases.size} role=$roleOk/${cases.size} advice=$adviceOk/${cases.size} invalidChunks=$invalid")
        // A floor, not a target: below this the feature misleads more than it helps and the prompt needs work.
        assertTrue("role $roleOk/${cases.size}", roleOk * 10 >= cases.size * 6)
        assertTrue("advice $adviceOk/${cases.size}", adviceOk * 10 >= cases.size * 6)
    }
}
