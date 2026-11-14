package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.ApkFact
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.core.model.Attention
import com.nathanhanapps.wayd.core.model.PermissionMeaning
import com.nathanhanapps.wayd.core.model.RelationStrength
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.SuggestionKind
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.core.model.UserState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelRelationsTest {
    private val pkg = "com.example.app"
    private var id = 1L
    private fun fact(kind: ApkFactKind, key: String) = ApkFact(id = id++, pkg = pkg, versionCode = 1, kind = kind, key = key, value = null, evidence = null)
    private fun op(name: String, ts: Long) = PermEvent(id = id++, pkg = pkg, op = name, ts = ts, durationMs = null, background = true, source = "appops")

    private val facts = listOf(
        fact(ApkFactKind.PERMISSION, "android.permission.ACCESS_FINE_LOCATION"), fact(ApkFactKind.PERMISSION, "android.permission.READ_PHONE_STATE"),
        fact(ApkFactKind.PERMISSION, "android.permission.INTERNET"), fact(ApkFactKind.ACTIVITY, "com.vendor.ads.SplashAdActivity"),
        fact(ApkFactKind.SERVICE, "com.example.app.SyncService"), fact(ApkFactKind.ENDPOINT, "ads.tracker.example"),
    )

    @Test fun combinesRolePermissionUseAndFindingsIntoAttentionAndSuggestions() {
        val ads = flow(host = "ads.tracker.example", up = 9_000, down = 500, startAt = 10_000, screenOn = false).copy(id = 101, category = TrackerCategory.ADS)
        val api = flow(host = "api.example.com", startAt = 10_000).copy(id = 102, category = TrackerCategory.FIRST_PARTY)
        val ops = listOf(op("FINE_LOCATION", 10_050), op("CAMERA", 900_000))
        val hosts = LabelRelations.assessAll(pkg, listOf(ads, api), facts, ops, emptyList())
        val a = hosts.first()
        assertEquals("ads.tracker.example", a.summary.host)
        assertTrue(a.inApk)
        // Location fits an advertising role and was in use while the record was open.
        assertEquals(RelationStrength.USED_TOGETHER, a.permissions.first().strength)
        assertEquals(PermissionMeaning.LOCATION, a.permissions.first().meaning)
        assertTrue(a.components.any { it.fact?.key == "com.vendor.ads.SplashAdActivity" })
        assertEquals(Attention.ACT, a.attention)
        val kinds = a.suggestions.map { it.kind }
        assertTrue(SuggestionKind.BLOCK_TRIAL in kinds && SuggestionKind.APPOPS_RESTRICT in kinds && SuggestionKind.THANOX_REDIRECT in kinds)
        assertEquals("FINE_LOCATION", a.suggestions.first { it.kind == SuggestionKind.APPOPS_RESTRICT }.op)

        val plain = hosts.last()
        assertEquals("api.example.com", plain.summary.host)
        assertTrue(plain.attention <= Attention.NOTE)
        assertTrue(plain.suggestions.none { it.kind == SuggestionKind.BLOCK_TRIAL })
    }

    @Test fun ruleFindingRaisesAttentionAndBlockedHostNeedsNone() {
        val f = flow(host = "api.example.com", startAt = 10_000).copy(id = 201, category = TrackerCategory.FIRST_PARTY)
        val finding = Finding(sessionId = null, pkg = pkg, ruleId = "R-X", severity = Severity.HIGH, title = "t", dataTypes = "", evidenceJson = """["f:201"]""", createdAt = 0, userState = UserState.NEW)
        val a = LabelRelations.assessAll(pkg, listOf(f), facts, emptyList(), listOf(finding)).single()
        assertEquals(Severity.HIGH, a.finding)
        assertTrue(a.attention >= Attention.REVIEW)
        val blocked = LabelRelations.assessAll(pkg, listOf(f.copy(policyOutcome = "BLOCKED")), facts, emptyList(), listOf(finding)).single()
        assertEquals(Attention.NONE, blocked.attention)
        assertNull(LabelRelations.assessAll(pkg, listOf(f), facts, emptyList(), emptyList()).single().finding)
    }

    @Test fun opNamesMapToCapabilities() {
        assertEquals(PermissionMeaning.LOCATION, LabelRelations.opMeaning("COARSE_LOCATION"))
        assertEquals(PermissionMeaning.CLIPBOARD, LabelRelations.opMeaning("READ_CLIPBOARD"))
        assertEquals(PermissionMeaning.MICROPHONE, LabelRelations.opMeaning("RECORD_AUDIO"))
        assertEquals(PermissionMeaning.UNKNOWN, LabelRelations.opMeaning("VIBRATE"))
    }
}
