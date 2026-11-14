package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.ApkFact
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.model.AdviceReason
import com.nathanhanapps.wayd.core.model.RoleSource
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.core.model.BlockAdvice
import com.nathanhanapps.wayd.core.model.ConnectionRole
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.PermissionMeaning
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.core.model.TrackerCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelRulesTest {
    private fun fact(kind: ApkFactKind, key: String, pkg: String = "com.example.app") =
        ApkFact(pkg = pkg, versionCode = 1, kind = kind, key = key, value = null, evidence = null)

    @Test fun endpointHeavyExportsPreserveOtherEvidenceKinds() {
        val facts = (1..1000).map { fact(ApkFactKind.ENDPOINT, "$it.example.com") } +
            fact(ApkFactKind.SDK, "Baidu Location") + fact(ApkFactKind.PERMISSION, "android.permission.ACCESS_FINE_LOCATION") +
            fact(ApkFactKind.ACTIVITY, "com.example.LoginActivity") + fact(ApkFactKind.COVERAGE, "limit")
        val selected = LabelRules.exportFacts(facts, 400)
        assertEquals(400, selected.size)
        assertEquals(facts.map { it.kind }.toSet(), selected.map { it.kind }.toSet())
        assertEquals(facts, LabelRules.exportFacts(facts, 2000).sortedBy { facts.indexOf(it) })
        assertTrue(LabelRules.exportFacts(facts, 0).isEmpty())
    }

    @Test fun apkMatchIsExactAndPackageScoped() {
        val f = flow(host = "CNLOC.MAP.BAIDU.COM.")
        val right = fact(ApkFactKind.ENDPOINT, "cnloc.map.baidu.com")
        val facts = listOf(right, fact(ApkFactKind.ENDPOINT, "baidu.com"), fact(ApkFactKind.ENDPOINT, "cnloc.map.baidu.com", "another.app"))
        assertEquals(listOf(right), LabelRules.exactEndpoints(f, facts))
        assertTrue(LabelRules.exactEndpoints(f.copy(host = null), facts).isEmpty())
    }

    @Test fun noAdviceForMissingOrInferredHostZeroBytesOrUnresolvedOwner() {
        val f = flow(host = "metrics.example.com").copy(category = TrackerCategory.ANALYTICS)
        assertEquals(BlockAdvice.TRY_TEMPORARY, LabelRules.advice(f))
        for (other in listOf(f.copy(hostSource = HostSource.DNS), f.copy(host = null), f.copy(host = "119.29.29.90"),
            f.copy(host = "2001:db8::a"), f.copy(pkg = "uid:0"), f.copy(pkg = "?"), f.copy(bytesUp = 0, bytesDown = 0), f.copy(host = "bad.host/path"), f.copy(proto = Proto.UDP))) {
            assertEquals(BlockAdvice.INSUFFICIENT, LabelRules.advice(other))
        }
    }

    @Test fun locationPurposeIsSeparateFromSeverityAndFlagsPossibleImpact() {
        val f = flow(host = "cnloc.map.baidu.com")
        assertEquals(ConnectionRole.LOCATION, LabelRules.role(f.host, TrackerCategory.UNKNOWN))
        assertEquals(BlockAdvice.MAY_AFFECT_FUNCTION, LabelRules.advice(f))
        assertEquals(BlockAdvice.INSUFFICIENT, LabelRules.advice(flow(host = "unknown.example")))
    }

    @Test fun adviceIsPerHostWithReasons() {
        val dns = flow(host = "metrics.example.com").copy(id = 1, category = TrackerCategory.ANALYTICS, hostSource = HostSource.DNS)
        val sni = dns.copy(id = 2, hostSource = HostSource.SNI, bytesUp = 4096, bytesDown = 100, screenOn = false)
        val summaries = LabelRules.hostSummaries(listOf(dns, sni))
        val s = summaries.getValue("h:metrics.example.com")
        assertEquals(2, s.flows)
        assertEquals(BlockAdvice.TRY_TEMPORARY, s.advice)
        assertTrue(s.domainBlockAvailable)
        assertTrue(AdviceReason.SCREEN_OFF in s.reasons && AdviceReason.UPLOAD_HEAVY in s.reasons)
        assertEquals(BlockAdvice.INSUFFICIENT, LabelRules.summary(listOf(flow(host = "push.example.com").copy(category = TrackerCategory.OS))).advice)
        assertTrue(AdviceReason.ALREADY_BLOCKED in LabelRules.summary(listOf(sni.copy(policyOutcome = "BLOCKED"))).reasons)
    }

    @Test fun rolesComeFromCuratedListThenTrackerThenNameWords() {
        assertEquals(RoleMatch(ConnectionRole.LOCATION, RoleSource.CURATED), LabelRules.match("dualstack-a.apilocate.amap.com", null))
        assertEquals(ConnectionRole.CONTENT, LabelRules.role("img.alicdn.com", null))
        assertEquals(RoleMatch(ConnectionRole.ANALYTICS, RoleSource.NAME_PATTERN), LabelRules.match("log-2.example.com", null))
        assertEquals(ConnectionRole.UNKNOWN, LabelRules.role("catalog.example.com", null))
        assertEquals(ConnectionRole.UNKNOWN, LabelRules.role("log.com", null)) // registrable label is never a role word
        assertEquals("example.com.cn", LabelRules.site("a.b.example.com.cn"))
    }

    @Test fun rankingPrefersRelatedPermissionsComponentsAndOverlappingOps() {
        val facts = listOf(fact(ApkFactKind.PERMISSION, "android.permission.INTERNET"), fact(ApkFactKind.PERMISSION, "android.permission.CAMERA"),
            fact(ApkFactKind.PERMISSION, "android.permission.ACCESS_FINE_LOCATION"),
            fact(ApkFactKind.SERVICE, "com.amap.api.location.APSService"), fact(ApkFactKind.ACTIVITY, "com.example.app.SettingsActivity"))
        val roles = setOf(ConnectionRole.LOCATION)
        assertEquals("android.permission.ACCESS_FINE_LOCATION", LabelRules.rankPermissions(facts, roles, 1).single().key)
        assertEquals(listOf("com.amap.api.location.APSService"), LabelRules.rankComponents(facts, roles, emptySet(), 3).map { it.key })
        val target = flow(host = "a.example.com").copy(startAt = 1_000, endAt = 2_000)
        val ops = listOf(PermEvent(id = 1, pkg = "com.example.app", op = "COARSE_LOCATION", ts = 1_500, durationMs = null, background = false, source = "appops"),
            PermEvent(id = 2, pkg = "com.example.app", op = "CAMERA", ts = 9_000, durationMs = null, background = false, source = "appops"))
        val ranked = LabelRules.rankOps(ops, listOf(target), 2)
        assertEquals(1L, ranked.first().event.id)
        assertTrue(ranked.first().overlapsTarget && !ranked.last().overlapsTarget)
    }

    @Test fun newPermissionMeanings() {
        assertEquals(PermissionMeaning.APP_LIST, LabelRules.permission("android.permission.QUERY_ALL_PACKAGES"))
        assertEquals(PermissionMeaning.AD_ID, LabelRules.permission("com.google.android.gms.permission.AD_ID"))
        assertEquals(PermissionMeaning.SMS, LabelRules.permission("android.permission.READ_SMS"))
        assertEquals(PermissionMeaning.PUSH, LabelRules.permission("com.example.app.permission.MIPUSH_RECEIVE"))
        assertEquals(PermissionMeaning.UNKNOWN, LabelRules.permission("malicious.permission.AD_ID"))
    }

    @Test fun customPermissionsCannotSpoofAndroidCapabilityNames() {
        assertEquals(PermissionMeaning.LOCATION, LabelRules.permission("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals(PermissionMeaning.UNKNOWN, LabelRules.permission("malicious.permission.ACCESS_FINE_LOCATION"))
        assertEquals(PermissionMeaning.REBOOT, LabelRules.permission("android.permission.REBOOT"))
    }
}
