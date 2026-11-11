package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.core.model.UserState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLabelsTest {
    private fun finding(severity: Severity, evidence: String, dataTypes: String = "") = Finding(
        sessionId = null, pkg = "com.example.app", ruleId = "R-TEST", severity = severity, title = "t", dataTypes = dataTypes,
        evidenceJson = evidence, createdAt = 0, userState = UserState.NEW,
    )

    @Test fun everyConnectionGetsALabelAndTheHighestWins() {
        val plain = flow()
        val ad = flow().copy(category = TrackerCategory.ADS)
        val analytics = flow().copy(category = TrackerCategory.ANALYTICS)
        val cdn = flow().copy(category = TrackerCategory.CDN)
        val leaky = flow().copy(category = TrackerCategory.ADS)
        val warned = flow()
        val danger = flow().copy(category = TrackerCategory.ADS)
        val findings = listOf(
            finding(Severity.LOW, """["f:${leaky.id}"]""", dataTypes = "ANDROID_ID"),
            finding(Severity.MEDIUM, """["f:${warned.id}","p:3"]"""),
            finding(Severity.MEDIUM, """["f:${danger.id}"]"""),
            finding(Severity.HIGH, """["f:${danger.id}","m:9"]"""),
        )
        val labels = ConnectionLabels.of(listOf(plain, ad, analytics, cdn, leaky, warned, danger), findings)
        assertEquals(ConnectionLabel.NORMAL, labels[plain.id])
        assertEquals(ConnectionLabel.AD, labels[ad.id])
        assertEquals(ConnectionLabel.AD, labels[analytics.id])
        assertEquals(ConnectionLabel.NORMAL, labels[cdn.id])
        assertEquals("personal data beats the ad category", ConnectionLabel.SENSITIVE, labels[leaky.id])
        assertEquals(ConnectionLabel.WARN, labels[warned.id])
        assertEquals("HIGH beats MEDIUM and ADS", ConnectionLabel.DANGER, labels[danger.id])
    }

    @Test fun lowFindingsWithoutDataTypesAndOtherEvidenceKindsDoNotLabel() {
        val f = flow()
        val labels = ConnectionLabels.of(listOf(f), listOf(finding(Severity.LOW, """["f:${f.id}"]"""), finding(Severity.HIGH, """["p:${f.id}","m:${f.id}"]""")))
        assertEquals(ConnectionLabel.NORMAL, labels[f.id])
    }

    @Test fun evidenceIdsMatchExactly() {
        val a = flow()
        val labels = ConnectionLabels.of(listOf(a), listOf(finding(Severity.HIGH, """["f:${a.id}0","f:1${a.id}"]""")))
        assertEquals(ConnectionLabel.NORMAL, labels[a.id])
    }
}
