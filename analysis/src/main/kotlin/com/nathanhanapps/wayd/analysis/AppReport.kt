package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.enrich.PrivateAddr
import com.nathanhanapps.wayd.enrich.TrackerDb
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Who an app talks to: grouped by company when known, otherwise by hostname (or bare address). */
data class Destination(
    val label: String,
    val company: String?,
    val category: TrackerCategory?,
    val hosts: List<String>,
    val flows: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val screenOffBytes: Long,
    val flowIds: List<Long>,
    /** True for the single bucket of connections that have no hostname; [hosts] then holds sample IP addresses. */
    val unnamed: Boolean = false,
    val addressCount: Int = 0,
) {
    val total get() = bytesUp + bytesDown
}

enum class InsightKind { TOP_DESTINATION, BACKGROUND_SHARE, TRACKERS, UPLOAD_HEAVY, UNNAMED_DESTINATIONS, LOCAL_NETWORK }

/** One structured, language-neutral statement about the app; the UI turns it into a sentence. */
data class Insight(val kind: InsightKind, val text: String = "", val number: Long = 0, val percent: Int = 0)

class HourlyActivity(val up: LongArray = LongArray(24), val down: LongArray = LongArray(24))

/** Everything the app-detail screen shows: what this app did in the window. */
class AppReport(
    val pkg: String,
    val flows: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val screenOffBytes: Long,
    val hourlyUp: LongArray,
    val hourlyDown: LongArray,
    val destinations: List<Destination>,
    val localFlows: Int,
    val insights: List<Insight>,
    val findings: List<Finding>,
    val score: RiskScorer.Score,
    val dailyActivity: Map<LocalDate, HourlyActivity> = emptyMap(),
) {
    val total get() = bytesUp + bytesDown
}

object AppReportBuilder {
    private val WATCHED = setOf(TrackerCategory.ADS, TrackerCategory.ANALYTICS, TrackerCategory.CRASH)

    /** Builds a report per attributed app from the window's flows and the findings already produced. */
    fun buildAll(ctx: RuleContext, findings: List<Finding>, engine: RuleEngine, tracker: TrackerDb): Map<String, AppReport> {
        val byApp = ctx.attributed.groupBy { it.pkg }
        val findingsByApp = findings.groupBy { it.pkg }
        return byApp.mapValues { (pkg, fl) -> build(pkg, fl, findingsByApp[pkg].orEmpty(), engine, tracker) }
    }

    fun build(pkg: String, flows: List<Flow>, findings: List<Finding>, engine: RuleEngine, tracker: TrackerDb, zone: ZoneId = ZoneId.systemDefault()): AppReport {
        val (local, remote) = flows.partition { PrivateAddr.isPrivate(it.dstIp) }

        val hourlyUp = LongArray(24)
        val hourlyDown = LongArray(24)
        val dailyActivity = linkedMapOf<LocalDate, HourlyActivity>()
        remote.forEach {
            val time = Instant.ofEpochMilli(it.startAt).atZone(zone)
            val h = time.hour
            hourlyUp[h] += it.bytesUp
            hourlyDown[h] += it.bytesDown
            val day = dailyActivity.getOrPut(time.toLocalDate()) { HourlyActivity() }
            day.up[h] += it.bytesUp
            day.down[h] += it.bytesDown
        }

        // Named destinations are grouped by company AND purpose, so an app's ad / analytics endpoints are not
        // hidden inside a CDN row. Connections without any hostname go into one bucket instead of a wall of IPs.
        fun hasName(f: Flow) = f.host != null || f.company != null
        val (namedFlows, unnamedFlows) = remote.partition(::hasName)

        val named = namedFlows.groupBy { f ->
            val info = tracker.lookup(f.host)
            (f.company ?: info?.company ?: f.host ?: f.dstIp) to (f.category ?: info?.category)
        }.map { (key, fl) ->
            Destination(
                label = key.first,
                company = fl.firstNotNullOfOrNull { it.company ?: tracker.lookup(it.host)?.company },
                category = key.second,
                hosts = fl.mapNotNull { it.host }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key },
                flows = fl.size,
                bytesUp = fl.sumOf { it.bytesUp },
                bytesDown = fl.sumOf { it.bytesDown },
                screenOffBytes = fl.filter { !it.screenOn }.sumOf { it.bytesUp + it.bytesDown },
                flowIds = fl.sortedByDescending { it.bytesUp + it.bytesDown }.take(50).map { it.id },
            )
        }
        val unnamedBucket = if (unnamedFlows.isEmpty()) emptyList() else listOf(
            Destination(
                label = "",
                company = null,
                category = null,
                hosts = unnamedFlows.groupBy { it.dstIp }.entries.sortedByDescending { e -> e.value.sumOf { it.bytesUp + it.bytesDown } }.take(8).map { it.key },
                flows = unnamedFlows.size,
                bytesUp = unnamedFlows.sumOf { it.bytesUp },
                bytesDown = unnamedFlows.sumOf { it.bytesDown },
                screenOffBytes = unnamedFlows.filter { !it.screenOn }.sumOf { it.bytesUp + it.bytesDown },
                flowIds = unnamedFlows.sortedByDescending { it.bytesUp + it.bytesDown }.take(50).map { it.id },
                unnamed = true,
                addressCount = unnamedFlows.map { it.dstIp }.distinct().size,
            ),
        )
        val destinations = (named + unnamedBucket).sortedByDescending { it.total }

        val up = remote.sumOf { it.bytesUp }
        val down = remote.sumOf { it.bytesDown }
        val off = remote.filter { !it.screenOn }.sumOf { it.bytesUp + it.bytesDown }
        val total = up + down

        val insights = ArrayList<Insight>()
        // A headline only helps when one named destination clearly dominates.
        destinations.firstOrNull { !it.unnamed }?.takeIf { total > 0 && it.total * 100 / total >= 20 }?.let {
            insights += Insight(InsightKind.TOP_DESTINATION, it.label, it.total, (it.total * 100 / total).toInt())
        }
        if (total > 0 && off > 0) insights += Insight(InsightKind.BACKGROUND_SHARE, number = off, percent = (off * 100 / total).toInt())
        val tracking = destinations.filter { it.category in WATCHED }
        if (tracking.isNotEmpty()) {
            insights += Insight(InsightKind.TRACKERS, tracking.joinToString { it.label }, tracking.sumOf { it.bytesUp }, tracking.size)
        }
        if (up > (1L shl 20) && up > down * 3) insights += Insight(InsightKind.UPLOAD_HEAVY, number = up, percent = if (down == 0L) 100 else (up * 100 / (up + down)).toInt())
        val unnamed = remote.filter { it.host == null && (it.bytesUp > 0 || it.bytesDown > 0) }
        if (unnamed.isNotEmpty()) insights += Insight(InsightKind.UNNAMED_DESTINATIONS, number = unnamed.map { it.dstIp }.distinct().size.toLong(), percent = unnamed.size)
        if (local.isNotEmpty()) insights += Insight(InsightKind.LOCAL_NETWORK, number = local.map { it.dstIp }.distinct().size.toLong(), percent = local.size)

        return AppReport(
            pkg = pkg, flows = flows.size, bytesUp = up, bytesDown = down, screenOffBytes = off,
            hourlyUp = hourlyUp, hourlyDown = hourlyDown, destinations = destinations, localFlows = local.size,
            insights = insights, findings = findings, score = RiskScorer.score(findings, engine), dailyActivity = dailyActivity,
        )
    }
}
