package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.analysis.rules.IdentifierScan
import com.nathanhanapps.wayd.analysis.rules.IdentifiersInRequests
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.model.DataType
import com.nathanhanapps.wayd.core.model.Dir
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.Tier
import com.nathanhanapps.wayd.core.model.TierSource
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.enrich.TrackerDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptedRulesTest {
    private var mid = 1L

    private fun msg(flow: Flow, url: String = "t.example.com/x", body: String? = null, headers: String = "", dir: Dir = Dir.UP) = Message(
        id = mid++, flowId = flow.id, dir = dir, ts = 1, tier = Tier.DECRYPTED, tierSource = TierSource.MITM, method = "POST",
        url = url, status = null, headersJson = headers, bodyBlobId = null, bodySize = body?.length ?: 0, contentType = null,
        body = body?.toByteArray(),
    )

    private fun ctx(flows: List<Flow>, messages: List<Message>) =
        RuleContext(flows, 0, 10_000_000, emptyMap(), TrackerDb(), 1, messages = messages)

    @Test fun findsIdentifiersByFieldNameAndFormat() {
        val f = flow()
        val hits = IdentifierScan.scan(
            msg(
                f, "t.example.com/c?android_id=9774d56d682e549c&oaid=123e4567-e89b-12d3-a456-426614174000",
                body = """{"imei":"356938035643809","phone":"13812345678","bssid":"00:1A:2B:3C:4D:5E","lat":31.230416,"lng":121.473701}""",
            ),
        ).map { it.type }.toSet()
        assertEquals(
            setOf(DataType.ANDROID_ID, DataType.ADVERTISING_ID, DataType.IMEI_SERIAL, DataType.PHONE_NUMBER, DataType.WIFI_BSSID, DataType.LOCATION_PRECISE),
            hits,
        )
    }

    @Test fun doesNotGuessAtUnlabelledValuesOrWrongFormats() {
        val f = flow()
        // right name, wrong format; right format, unrelated name; responses are never scanned
        assertTrue(IdentifierScan.scan(msg(f, "a.com/x?android_id=notahex&imei=12&phone=hello")).isEmpty())
        assertTrue(IdentifierScan.scan(msg(f, "a.com/x?session=9774d56d682e549c&page=13812345678")).isEmpty())
        assertTrue(IdentifierScan.scan(msg(f, "a.com/x?android_id=9774d56d682e549c", dir = Dir.DOWN)).isEmpty())
    }

    @Test fun detectsAnAppListUpload() {
        val f = flow()
        val pkgs = (1..12).joinToString(",") { "\"com.vendor.app$it\"" }
        assertTrue(IdentifierScan.scan(msg(f, body = "{\"apps\":[$pkgs]}")).any { it.type == DataType.APP_LIST })
        assertFalse(IdentifierScan.scan(msg(f, body = "{\"apps\":[\"com.vendor.app1\",\"com.vendor.app2\"]}")).any { it.type == DataType.APP_LIST })
    }

    @Test fun severityRisesWhenTheDestinationIsATracker() {
        val tracker = flow(host = "log.umeng.com").copy(category = TrackerCategory.ANALYTICS, company = "Umeng")
        val first = flow(host = "api.example.com").copy(category = TrackerCategory.FIRST_PARTY)
        val findings = IdentifiersInRequests().evaluate(
            ctx(
                listOf(tracker, first),
                listOf(
                    msg(tracker, "log.umeng.com/u?android_id=9774d56d682e549c"),
                    msg(first, "api.example.com/u?imei=356938035643809"),
                ),
            ),
        ).associateBy { it.dataTypes }
        assertEquals(Severity.MEDIUM, findings.getValue("ANDROID_ID").severity) // LOW identifier, to a tracker
        assertEquals(Severity.MEDIUM, findings.getValue("IMEI_SERIAL").severity) // hard identifier, first party
        assertTrue(findings.getValue("ANDROID_ID").evidenceJson.contains("m:"))
    }

    @Test fun neverCopiesTheValueIntoTheFinding() {
        val f = flow(host = "t.example.com")
        val out = IdentifiersInRequests().evaluate(ctx(listOf(f), listOf(msg(f, "t.example.com/u?android_id=9774d56d682e549c"))))
        assertFalse(out.any { "9774d56d682e549c" in it.title || "9774d56d682e549c" in it.evidenceJson })
    }
}
