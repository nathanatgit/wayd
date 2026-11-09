package com.nathanhanapps.wayd.feature.analysis

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.nathanhanapps.wayd.analysis.AppReport
import com.nathanhanapps.wayd.analysis.AppReportBuilder
import com.nathanhanapps.wayd.analysis.DailyReport
import com.nathanhanapps.wayd.analysis.DailyReportBuilder
import com.nathanhanapps.wayd.analysis.RuleContext
import com.nathanhanapps.wayd.analysis.RuleEngine
import com.nathanhanapps.wayd.core.db.FindingDao
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.FlowDao
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.db.MessageDao
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.db.PermEventDao
import com.nathanhanapps.wayd.enrich.TrackerDb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Analysis window keys: `today`, `yesterday`, `week` (last 7 local days) or an ISO date `2026-11-09`. */
object Windows {
    const val TODAY = "today"
    const val YESTERDAY = "yesterday"
    const val WEEK = "week"

    fun resolve(key: String, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val today = LocalDate.now(zone)
        val (from, to) = when (key) {
            TODAY -> today to today.plusDays(1)
            YESTERDAY -> today.minusDays(1) to today
            WEEK -> today.minusDays(6) to today.plusDays(1)
            else -> runCatching { LocalDate.parse(key) }.getOrDefault(today).let { it to it.plusDays(1) }
        }
        return from.atStartOfDay(zone).toInstant().toEpochMilli() to to.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

class Evidence(val flows: List<Flow>, val permissions: List<PermEvent>, val messages: List<Message> = emptyList())

class AnalysisResult(val report: DailyReport, val apps: Map<String, AppReport>)

/** Runs enrichment backfill, rules and report builders for a window and stores the findings. */
@Singleton
class AnalysisService @Inject constructor(
    private val flows: FlowDao,
    private val findings: FindingDao,
    private val permEvents: PermEventDao,
    private val permissions: PermissionHistoryService,
    private val messageDao: MessageDao,
) {
    private val engine = RuleEngine()
    private val tracker = TrackerDb()

    suspend fun analyse(windowKey: String): AnalysisResult = withContext(Dispatchers.IO) {
        val (start, end) = Windows.resolve(windowKey)
        // Flows recorded before a hostname/company was known get enriched now (cheap: per distinct host).
        flows.hostsWithoutCompany().forEach { h ->
            tracker.lookup(h)?.let { flows.setEnrichment(h, it.company, it.category) }
        }
        val inWindow = flows.between(start, end)
        // Pull permission history for the apps that were active (best effort; needs Shizuku), then read what is stored.
        runCatching { permissions.sync(inWindow.map { it.pkg }.distinct(), start, end) }
        val known = flows.hostsBefore(start).groupBy({ it.pkg }, { it.host }).mapValues { it.value.toSet() }
        val ctx = RuleContext(inWindow, start, end, known, tracker, System.currentTimeMillis(), permEvents.between(start, end), messageDao.between(start, end))
        val found = engine.run(ctx)
        findings.deleteBetween(start, end)
        findings.insertAll(found)
        AnalysisResult(
            DailyReportBuilder.build(ctx, found, engine, tracker),
            AppReportBuilder.buildAll(ctx, found, engine, tracker),
        )
    }

    suspend fun flowsByIds(ids: List<Long>): List<Flow> = withContext(Dispatchers.IO) { if (ids.isEmpty()) emptyList() else flows.byIds(ids) }

    /** Parses `["f:12","p:3"]` into flow ids. */
    fun evidenceIds(json: String): List<Long> = EVIDENCE.findAll(json).filter { it.groupValues[1] == "f" }.map { it.groupValues[2].toLong() }.toList()

    fun messageIds(json: String): List<Long> = EVIDENCE.findAll(json).filter { it.groupValues[1] == "m" }.map { it.groupValues[2].toLong() }.toList()

    fun permissionEvidenceIds(json: String): List<Long> = EVIDENCE.findAll(json).filter { it.groupValues[1] == "p" }.map { it.groupValues[2].toLong() }.toList()

    suspend fun evidence(json: String): Evidence = withContext(Dispatchers.IO) {
        val f = evidenceIds(json)
        val p = permissionEvidenceIds(json)
        val m = messageIds(json)
        Evidence(
            if (f.isEmpty()) emptyList() else flows.byIds(f),
            if (p.isEmpty()) emptyList() else permEvents.byIds(p),
            if (m.isEmpty()) emptyList() else messageDao.byIds(m),
        )
    }

    private companion object {
        val EVIDENCE = Regex("([fpm]):(\\d+)")
    }
}

/** App names and icons from the package manager (needs QUERY_ALL_PACKAGES, declared by the capture module). */
@Singleton
class AppLabels @Inject constructor(@ApplicationContext private val context: Context) {
    private val pm = context.packageManager
    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, ImageBitmap?>()

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    fun icon(pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching {
            val d: Drawable = pm.getApplicationIcon(pkg)
            d.toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
}
