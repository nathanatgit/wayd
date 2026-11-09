package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.analysis.rules.BackgroundUpload
import com.nathanhanapps.wayd.analysis.rules.Beacon
import com.nathanhanapps.wayd.analysis.rules.CleartextHttp
import com.nathanhanapps.wayd.analysis.rules.DirectIp
import com.nathanhanapps.wayd.analysis.rules.NewDomain
import com.nathanhanapps.wayd.analysis.rules.OwnDoh
import com.nathanhanapps.wayd.analysis.rules.TrackerContact
import com.nathanhanapps.wayd.analysis.rules.UploadRatio
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.model.CaptureSource
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.NetType
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.enrich.TrackerDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private var nextId = 1L

internal fun flow(
    pkg: String = "com.example.app",
    host: String? = "api.example.com",
    hostSource: HostSource? = if (host != null) HostSource.SNI else null,
    dstIp: String = "93.184.216.34",
    dstPort: Int = 443,
    proto: Proto = Proto.TCP,
    up: Long = 1_000,
    down: Long = 1_000,
    startAt: Long = 1_000_000,
    screenOn: Boolean = true,
) = Flow(
    id = nextId++, sessionId = 1, uid = 10_000, pkg = pkg, userId = 0, proto = proto, ipVersion = 4, dstIp = dstIp,
    dstPort = dstPort, host = host, hostSource = hostSource, bytesUp = up, bytesDown = down, pktsUp = 0, pktsDown = 0,
    startAt = startAt, endAt = startAt + 100, screenOn = screenOn, appForeground = false, netType = NetType.WIFI,
    tlsVersion = null, alpn = null, ja4 = null, country = null, asn = null, company = null, category = null,
    source = CaptureSource.VPN,
)

internal fun ctx(flows: List<Flow>, known: Map<String, Set<String>> = emptyMap()) =
    RuleContext(flows, 0, 86_400_000, known, TrackerDb(), now = 5)

class RulesTest {
    private val mb = 1L shl 20

    @Test fun bgUpload_firesOnlyForScreenOffUploadOverThreshold() {
        val f = BackgroundUpload().evaluate(
            ctx(listOf(flow(up = 2 * mb, screenOn = false), flow(pkg = "b", up = 2 * mb, screenOn = true), flow(pkg = "c", up = 100, screenOn = false))),
        )
        assertEquals(listOf("com.example.app"), f.map { it.pkg })
        assertEquals(Severity.MEDIUM, f.single().severity)
        assertTrue(f.single().evidenceJson.startsWith("[\"f:"))
    }

    @Test fun bgUpload_ignoresLanAndUnattributed() {
        val f = BackgroundUpload().evaluate(
            ctx(listOf(flow(up = 5 * mb, screenOn = false, dstIp = "192.168.1.5"), flow(pkg = UNKNOWN_PKG, up = 5 * mb, screenOn = false))),
        )
        assertTrue(f.isEmpty())
    }

    @Test fun upRatio() {
        assertEquals(1, UploadRatio().evaluate(ctx(listOf(flow(up = 4 * mb, down = 100_000)))).size)
        assertTrue(UploadRatio().evaluate(ctx(listOf(flow(up = 4 * mb, down = 3 * mb)))).isEmpty()) // ratio below 3
        assertTrue(UploadRatio().evaluate(ctx(listOf(flow(up = 500_000, down = 0)))).isEmpty()) // under 1 MB
    }

    @Test fun beacon_regularIntervalsFire_irregularDoNot() {
        val regular = (0 until 8).map { flow(host = "ping.example.com", startAt = 1_000_000 + it * 60_000L) }
        assertEquals(1, Beacon().evaluate(ctx(regular)).size)
        val jittery = listOf(0L, 5_000, 70_000, 71_000, 300_000, 303_000, 900_000, 901_000).map { flow(host = "x.example.com", startAt = 1_000_000 + it) }
        assertTrue(Beacon().evaluate(ctx(jittery)).isEmpty())
        val fast = (0 until 10).map { flow(host = "burst.example.com", startAt = 1_000_000 + it * 500L) } // below min interval
        assertTrue(Beacon().evaluate(ctx(fast)).isEmpty())
        val few = (0 until 4).map { flow(host = "few.example.com", startAt = 1_000_000 + it * 60_000L) }
        assertTrue(Beacon().evaluate(ctx(few)).isEmpty())
    }

    @Test fun newDomain_usesHistoryPerApp() {
        val fl = listOf(flow(host = "old.example.com"), flow(host = "new.example.com", startAt = 2_000_000), flow(pkg = "other", host = "old.example.com"))
        val f = NewDomain().evaluate(ctx(fl, known = mapOf("com.example.app" to setOf("old.example.com"))))
        assertEquals(setOf("com.example.app", "other"), f.map { it.pkg }.toSet())
        assertTrue(f.first { it.pkg == "com.example.app" }.title.startsWith("1 new"))
        assertEquals(Severity.INFO, f.first().severity)
    }

    @Test fun directIp_needsDataPublicAndNoHost() {
        val fl = listOf(
            flow(host = null, up = 10), // fires
            flow(host = null, up = 0, down = 0), // no data
            flow(host = null, dstIp = "10.0.0.8"), // LAN
            flow(host = "named.example.com"), // has name
            flow(host = null, proto = Proto.UDP), // UDP not considered
        )
        val f = DirectIp().evaluate(ctx(fl))
        assertEquals(1, f.size)
        assertTrue(f.single().title.startsWith("1 connection"))
    }

    @Test fun ownDoh() {
        val f = OwnDoh().evaluate(ctx(listOf(flow(host = "dns.alidns.com"), flow(pkg = "b", host = "www.example.com"))))
        assertEquals(listOf("com.example.app"), f.map { it.pkg })
    }

    @Test fun trackerContact_adsAreLow_analyticsInfo_andNeedUpload() {
        val fl = listOf(
            flow(host = "gdt.qq.com", up = 500), // ADS -> LOW
            flow(host = "app-measurement.com", up = 300), // ANALYTICS -> INFO
            flow(host = "pglstatp-toutiao.com", up = 0, down = 900), // no upload -> ignored
            flow(host = "www.example.com", up = 900), // not a tracker
        )
        val f = TrackerContact().evaluate(ctx(fl))
        assertEquals(2, f.size)
        assertEquals(Severity.LOW, f.first { it.title.contains("Tencent") }.severity)
        assertEquals(Severity.INFO, f.first { it.title.contains("Google") }.severity)
    }

    @Test fun cleartextHttp_excludesOsChecksAndLan() {
        val fl = listOf(
            flow(host = "tracker.example", dstPort = 80, hostSource = HostSource.HTTP_HOST, up = 700), // fires
            flow(host = "connectivitycheck.gstatic.com", dstPort = 80, hostSource = HostSource.HTTP_HOST, up = 100), // OS check
            flow(host = "nas.local", dstPort = 80, hostSource = HostSource.HTTP_HOST, dstIp = "192.168.1.9", up = 100), // LAN
            flow(host = "tls.example", dstPort = 443, up = 100), // not port 80
        )
        val f = CleartextHttp().evaluate(ctx(fl))
        assertEquals(1, f.size)
        assertTrue(f.single().title.contains("tracker.example"))
    }

    @Test fun engine_throwingRuleDoesNotHideOthers() {
        val bad = object : Rule {
            override val id = "R-BAD"
            override val version = 1
            override val tier = com.nathanhanapps.wayd.core.model.Tier.METADATA
            override fun evaluate(ctx: RuleContext): List<Finding> = error("boom")
        }
        val engine = RuleEngine(listOf(bad, OwnDoh()))
        assertEquals(1, engine.run(ctx(listOf(flow(host = "dns.google")))).size)
    }

    @Test fun riskScore_weightsAndCap() {
        val engine = RuleEngine()
        fun f(rule: String, sev: Severity) = Finding(0, null, "p", rule, sev, "t", "", "[]", 0, com.nathanhanapps.wayd.core.model.UserState.NEW)
        // MEDIUM (12) x METADATA 0.6 = 7.2 ; LOW (4) x PLAINTEXT 1.0 = 4.0 ; INFO = 0
        val s = RiskScorer.score(listOf(f("R-UP-RATIO", Severity.MEDIUM), f("R-CLEARTEXT-HTTP", Severity.LOW), f("R-NEW-DOMAIN", Severity.INFO)), engine)
        assertEquals(11, s.total)
        assertEquals(2, s.reasons.size) // INFO is not a reason, there is nothing to explain
        val many = (1..20).map { f("R-UP-RATIO", Severity.HIGH) }
        assertEquals(100, RiskScorer.score(many, engine).total)
    }

    @Test fun report_summaryBackgroundAndCoverage() {
        val fl = listOf(
            flow(host = "gdt.qq.com", up = 2 * mb, down = 10, screenOn = false, startAt = 3_600_000),
            flow(host = "www.example.com", up = 10, down = 5 * mb, startAt = 7_200_000),
            flow(host = null, up = 5, down = 5, startAt = 7_300_000),
            flow(pkg = UNKNOWN_PKG, host = null, up = 0, down = 0),
        )
        val c = ctx(fl)
        val engine = RuleEngine()
        val findings = engine.run(c)
        val r = DailyReportBuilder.build(c, findings, engine, TrackerDb())
        assertEquals(1, r.appCount)
        assertEquals(2, r.domainCount)
        assertEquals(1, r.companyCount)
        assertEquals(1, r.trackerContacts)
        assertEquals(75, r.coverage.withPackagePct)
        assertEquals(3, r.coverage.measurableTcp)
        assertEquals(66, r.coverage.withHostnamePct)
        assertEquals(1, r.background.size)
        assertEquals(fl.sumOf { it.bytesUp + it.bytesDown }, r.hourlyBytes.sum())
    }
}

class AppReportTest {
    private val mb = 1L shl 20

    @Test fun groupsDestinationsByCompanyAndExplainsActivity() {
        val fl = listOf(
            flow(host = "szextshort.weixin.qq.com", up = 100, down = 2 * mb),
            flow(host = "wx.qlogo.cn", up = 10, down = 3 * mb), // Tencent CDN: same company group
            flow(host = "gdt.qq.com", up = 300, down = 50, screenOn = false),
            flow(host = null, up = 5, down = 5, dstIp = "8.8.4.4"),
            flow(host = "nas.local", dstIp = "192.168.1.2", up = 9, down = 9), // LAN, kept out of destinations
        )
        val c = ctx(fl)
        val engine = RuleEngine()
        val r = AppReportBuilder.build("com.example.app", fl, engine.run(c), engine, TrackerDb())
        // Tencent traffic is split by purpose: CDN (qlogo), plain service (weixin) and advertising (gdt).
        assertEquals("Tencent", r.destinations.first().label)
        assertEquals(TrackerCategory.CDN, r.destinations.first().category)
        assertEquals(3, r.destinations.count { it.label == "Tencent" })
        assertTrue(r.destinations.any { it.label == "Tencent" && it.category == TrackerCategory.ADS && it.screenOffBytes > 0 })
        assertEquals(1, r.localFlows)
        assertEquals(InsightKind.TOP_DESTINATION, r.insights.first().kind)
        assertTrue(r.insights.any { it.kind == InsightKind.TRACKERS })
        assertTrue(r.insights.any { it.kind == InsightKind.BACKGROUND_SHARE })
        assertTrue(r.insights.any { it.kind == InsightKind.UNNAMED_DESTINATIONS })
        val bucket = r.destinations.single { it.unnamed }
        assertEquals(1, bucket.addressCount)
        assertEquals(listOf("8.8.4.4"), bucket.hosts)
        assertTrue(r.insights.any { it.kind == InsightKind.LOCAL_NETWORK })
        assertEquals(fl.filter { it.dstIp != "192.168.1.2" }.sumOf { it.bytesUp + it.bytesDown }, r.total)
        assertEquals(r.total, r.hourlyUp.sum() + r.hourlyDown.sum())
    }

    @Test fun unnamedAddressesAreOneBucketAndNeverTheHeadline() {
        val fl = (1..30).map { flow(host = null, dstIp = "42.63.50.$it", up = 10, down = 1_000_000) } + flow(host = "api.example.com", up = 10, down = 100)
        val engine = RuleEngine()
        val r = AppReportBuilder.build("p", fl, emptyList(), engine, TrackerDb())
        assertEquals(2, r.destinations.size) // one named, one unnamed bucket
        assertEquals(30, r.destinations.first { it.unnamed }.addressCount)
        // The named destination has ~0% of the traffic, so there must be no "mostly talks to" headline.
        assertTrue(r.insights.none { it.kind == InsightKind.TOP_DESTINATION })
    }

    @Test fun emptyOrQuietAppDoesNotCrash() {
        val engine = RuleEngine()
        val r = AppReportBuilder.build("p", listOf(flow(up = 0, down = 0)), emptyList(), engine, TrackerDb())
        assertEquals(0L, r.total)
        assertTrue(r.insights.none { it.kind == InsightKind.TOP_DESTINATION })
    }
}

private var nextPermId = 1L

internal fun perm(
    pkg: String = "com.example.app",
    op: String,
    background: Boolean,
    count: Int = 1,
    ts: Long = 1_000_000,
    bucketMs: Long = 15 * 60_000L,
) = com.nathanhanapps.wayd.core.db.PermEvent(
    id = nextPermId++, pkg = pkg, op = op, ts = ts, durationMs = null, background = background,
    source = "appops:" + if (background) "bg" else "top", count = count, bucketEnd = ts + bucketMs,
)

internal fun ctxWithPerms(flows: List<com.nathanhanapps.wayd.core.db.Flow>, perms: List<com.nathanhanapps.wayd.core.db.PermEvent>) =
    RuleContext(flows, 0, 86_400_000, emptyMap(), TrackerDb(), now = 5, permEvents = perms)

class PermissionRulesTest {
    @Test fun clipboardBackground_onlyBackgroundReads() {
        val f = com.nathanhanapps.wayd.analysis.rules.ClipboardBackground().evaluate(
            ctxWithPerms(emptyList(), listOf(perm(op = "READ_CLIPBOARD", background = true, count = 3), perm(op = "READ_CLIPBOARD", background = false, count = 50), perm(pkg = "b", op = "READ_CLIPBOARD", background = false))),
        )
        assertEquals(1, f.size)
        assertEquals(Severity.MEDIUM, f.single().severity)
        assertTrue(f.single().title.contains("3 time"))
        assertTrue(f.single().evidenceJson.startsWith("[\"p:"))
    }

    @Test fun screenCapture_backgroundIsHigh_visibleIsIgnored() {
        val rule = com.nathanhanapps.wayd.analysis.rules.ScreenCaptureBackground()
        assertTrue(rule.evaluate(ctxWithPerms(emptyList(), listOf(perm(op = "PROJECT_MEDIA", background = false)))).isEmpty())
        val f = rule.evaluate(ctxWithPerms(emptyList(), listOf(perm(op = "PROJECT_MEDIA", background = true, count = 2))))
        assertEquals(Severity.HIGH, f.single().severity)
    }

    @Test fun locationThenUpload_needsBackgroundLocationAndUploadInSameBucket() {
        val rule = com.nathanhanapps.wayd.analysis.rules.LocationThenUpload()
        val loc = perm(op = "FINE_LOCATION", background = true, count = 4, ts = 1_000_000)
        val inside = flow(up = 20_000, startAt = 1_000_000 + 5 * 60_000L)
        val outside = flow(up = 500_000, startAt = 1_000_000 + 40 * 60_000L)
        val f = rule.evaluate(ctxWithPerms(listOf(inside, outside), listOf(loc)))
        assertEquals(1, f.size)
        val json = f.single().evidenceJson
        assertTrue(json.contains("p:${loc.id}") && json.contains("f:${inside.id}") && !json.contains("f:${outside.id}"))
        // visible location use, a small upload, an upload to the LAN, and a coarse bucket must not fire
        assertTrue(rule.evaluate(ctxWithPerms(listOf(inside), listOf(perm(op = "FINE_LOCATION", background = false, ts = 1_000_000)))).isEmpty())
        assertTrue(rule.evaluate(ctxWithPerms(listOf(flow(up = 100, startAt = 1_100_000)), listOf(loc))).isEmpty())
        assertTrue(rule.evaluate(ctxWithPerms(listOf(flow(up = 50_000, dstIp = "192.168.1.9", startAt = 1_100_000)), listOf(loc))).isEmpty())
        val coarse = perm(op = "FINE_LOCATION", background = true, ts = 1_000_000, bucketMs = 4 * 3_600_000L)
        assertTrue(rule.evaluate(ctxWithPerms(listOf(inside), listOf(coarse))).isEmpty())
    }

    @Test fun engineIncludesPermissionRulesAndScoresThem() {
        val engine = RuleEngine()
        assertTrue(engine.rules.map { it.id }.containsAll(listOf("R-CLIPBOARD-BG", "R-SCREEN-CAPTURE", "R-LOC-THEN-UP")))
        val findings = engine.run(ctxWithPerms(emptyList(), listOf(perm(op = "PROJECT_MEDIA", background = true))))
        assertEquals(18, RiskScorer.score(findings, engine).total) // HIGH 30 x METADATA 0.6
    }
}

class FindingTextTest {
    @Test fun roundTripsFallbackAndArguments() {
        val t = FindingText.of("Sent 5 KB to Tencent (advertising)", "5 KB", "Tencent", "ADS")
        val p = FindingText.parse(t)
        assertEquals("Sent 5 KB to Tencent (advertising)", p.fallback)
        assertEquals(listOf("5 KB", "Tencent", "ADS"), p.args)
        assertEquals(emptyList<String>(), FindingText.parse("plain title").args)
    }

    @Test fun everyRuleProducesLocalisableTitles() {
        // All default rules must emit titles whose first segment is the English fallback and whose args are present.
        val c = ctxWithPerms(
            listOf(flow(host = "gdt.qq.com", up = 5_000_000, down = 10, screenOn = false)),
            listOf(perm(op = "READ_CLIPBOARD", background = true)),
        )
        val findings = RuleEngine().run(c)
        assertTrue(findings.isNotEmpty())
        findings.forEach { f -> assertTrue(f.ruleId + " " + f.title, FindingText.parse(f.title).args.isNotEmpty()) }
    }
}
