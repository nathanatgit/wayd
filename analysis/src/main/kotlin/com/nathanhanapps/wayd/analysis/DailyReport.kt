package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.enrich.PrivateAddr
import com.nathanhanapps.wayd.enrich.TrackerDb

data class AppTraffic(val pkg: String, val flows: Int, val bytesUp: Long, val bytesDown: Long, val screenOffUp: Long, val screenOffDown: Long)
data class CompanyRow(val company: String, val category: TrackerCategory, val apps: List<Pair<String, Long>>, val bytesUp: Long)
/** Traffic to one country (ISO 3166-1 alpha-2). Only produced when the GeoIP database from the Clash extension is present. */
data class CountryRow(val code: String, val flows: Int, val bytes: Long)
data class CoverageSummary(val flows: Int, val withPackagePct: Int, val measurableTcp: Int, val withHostnamePct: Int)

/** Everything the Reports screen shows for one day; cheap to render, built once per analysis run. */
data class DailyReport(
    val windowStart: Long,
    val windowEnd: Long,
    val appCount: Int,
    val domainCount: Int,
    val companyCount: Int,
    val trackerContacts: Int,
    val alerts: List<Finding>,
    val others: List<Finding>,
    val background: List<AppTraffic>,
    val companies: List<CompanyRow>,
    val newToday: List<Finding>,
    val scores: Map<String, RiskScorer.Score>,
    val appTraffic: List<AppTraffic>,
    val hourlyBytes: LongArray,
    val coverage: CoverageSummary,
    val countries: List<CountryRow> = emptyList(),
)

object DailyReportBuilder {
    fun build(ctx: RuleContext, findings: List<Finding>, engine: RuleEngine, tracker: TrackerDb): DailyReport {
        val flows = ctx.flows
        val attributed = ctx.attributed

        val traffic = attributed.groupBy { it.pkg }.map { (pkg, fl) ->
            val off = fl.filter { !it.screenOn }
            AppTraffic(pkg, fl.size, fl.sumOf { it.bytesUp }, fl.sumOf { it.bytesDown }, off.sumOf { it.bytesUp }, off.sumOf { it.bytesDown })
        }.sortedByDescending { it.bytesUp + it.bytesDown }

        val background = traffic.filter { it.screenOffUp + it.screenOffDown > 0 }
            .sortedByDescending { it.screenOffUp + it.screenOffDown }.take(10)

        val companies = attributed.mapNotNull { f -> tracker.lookup(f.host)?.let { Triple(f, it.company, it.category) } }
            .groupBy { it.second to it.third }.map { (key, rows) ->
                val perApp = rows.groupBy { it.first.pkg }.map { (pkg, r) -> pkg to r.sumOf { it.first.bytesUp + it.first.bytesDown } }
                    .sortedByDescending { it.second }
                CompanyRow(key.first, key.second, perApp, rows.sumOf { it.first.bytesUp })
            }.sortedByDescending { it.apps.sumOf { a -> a.second } }

        val hourly = LongArray(24)
        val cal = java.util.Calendar.getInstance()
        flows.forEach {
            cal.timeInMillis = it.startAt
            hourly[cal.get(java.util.Calendar.HOUR_OF_DAY)] += it.bytesUp + it.bytesDown
        }

        val trackerContacts = attributed.count { tracker.lookup(it.host)?.category in setOf(TrackerCategory.ADS, TrackerCategory.ANALYTICS, TrackerCategory.CRASH) }
        val measurable = flows.filter { it.proto == Proto.TCP && (it.bytesUp > 0 || it.bytesDown > 0) && !PrivateAddr.isPrivate(it.dstIp) }

        // Routing categories (e.g. CLOUDFLARE) and private/unknown addresses are not countries; keep only two-letter codes.
        val countries = attributed.mapNotNull { f -> f.country?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }?.let { it to f } }
            .groupBy({ it.first }, { it.second })
            .map { (code, fl) -> CountryRow(code, fl.size, fl.sumOf { it.bytesUp + it.bytesDown }) }
            .sortedByDescending { it.bytes }

        return DailyReport(
            windowStart = ctx.windowStart,
            windowEnd = ctx.windowEnd,
            appCount = attributed.map { it.pkg }.distinct().size,
            domainCount = attributed.mapNotNull { it.host }.distinct().size,
            companyCount = companies.map { it.company }.distinct().size,
            trackerContacts = trackerContacts,
            alerts = findings.filter { it.severity == Severity.HIGH || it.severity == Severity.MEDIUM },
            others = findings.filter { it.severity == Severity.LOW || (it.severity == Severity.INFO && it.ruleId != "R-NEW-DOMAIN") },
            background = background,
            companies = companies,
            newToday = findings.filter { it.ruleId == "R-NEW-DOMAIN" },
            scores = findings.groupBy { it.pkg }.mapValues { RiskScorer.score(it.value, engine) },
            appTraffic = traffic,
            hourlyBytes = hourly,
            coverage = CoverageSummary(
                flows = flows.size,
                withPackagePct = if (flows.isEmpty()) 0 else attributed.size * 100 / flows.size,
                measurableTcp = measurable.size,
                withHostnamePct = if (measurable.isEmpty()) 0 else measurable.count { it.host != null } * 100 / measurable.size,
            ),
            countries = countries,
        )
    }
}
