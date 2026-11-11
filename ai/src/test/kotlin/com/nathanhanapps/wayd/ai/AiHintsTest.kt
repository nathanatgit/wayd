package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.db.AiAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiHintsTest {
    private fun row(refs: String, result: String, validated: Boolean = true, at: Long = 0) =
        AiAnalysis(inputRefsJson = refs, model = "m", promptVersion = 1, resultJson = result, validated = validated, createdAt = at, durationMs = 1)

    private fun answer(vararg dest: Pair<String, String>) = """{"schema_version":1,"summary":"s","verdict":"expected_telemetry","confidence":0.5,"destinations":[""" +
        dest.joinToString(",") { (purpose, ids) -> """{"host":"h.example.com","purpose_guess":"$purpose","evidence_ids":[$ids]}""" } + "]}"

    @Test fun mapsCitedConnectionsToThePurposeNewestFirst() {
        val newest = row("f:1,f:2,|en|", answer("ads" to "\"f:1\"", "functional" to "\"f:2\""))
        val older = row("f:1,f:3,|en|", answer("analytics" to "\"f:1\"", "push" to "\"f:3\""))
        val hints = AiHints.flowPurposes(listOf(newest, older))
        assertEquals(Purpose.ADS, hints[1])
        assertEquals(Purpose.FUNCTIONAL, hints[2])
        assertEquals(Purpose.PUSH, hints[3])
    }

    @Test fun inventedEvidenceUnknownPurposeAndInvalidRowsGiveNoHint() {
        val invented = row("f:1,|en|", answer("ads" to "\"f:99\""))
        val unknown = row("f:5,|en|", answer("unknown" to "\"f:5\""))
        val invalid = row("f:6,|en|", answer("ads" to "\"f:6\""), validated = false)
        val garbage = row("f:7,|en|", "not json")
        val hints = AiHints.flowPurposes(listOf(invented, unknown, invalid, garbage))
        assertNull(hints[99])
        assertNull(hints[5])
        assertNull(hints[6])
        assertNull(hints[7])
    }
}
