package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.enrich.TrackerDb
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ActivityHeatmapTest {
    @Test fun datesStaySeparateAcrossLocalMidnightAndDirectionsKeepTheirTotals() {
        val before = Instant.parse("2026-11-10T15:59:59Z").toEpochMilli()
        val after = Instant.parse("2026-11-10T16:00:00Z").toEpochMilli()
        val report = AppReportBuilder.build("com.example.app", listOf(
            flow(startAt = before, up = 10, down = 20),
            flow(startAt = after, up = 30, down = 40),
            flow(startAt = after + 1000, up = 50, down = 60),
            flow(startAt = after, dstIp = "192.168.1.2", up = 900, down = 900),
        ), emptyList(), RuleEngine(), TrackerDb(), ZoneId.of("Asia/Shanghai"))
        val first = report.dailyActivity.getValue(LocalDate.of(2026, 11, 10))
        val second = report.dailyActivity.getValue(LocalDate.of(2026, 11, 11))
        assertEquals(2, report.dailyActivity.size)
        assertEquals(10L, first.up[23])
        assertEquals(20L, first.down[23])
        assertEquals(80L, second.up[0])
        assertEquals(100L, second.down[0])
        assertEquals(report.bytesUp, report.dailyActivity.values.sumOf { it.up.sum() })
        assertEquals(report.bytesDown, report.dailyActivity.values.sumOf { it.down.sum() })
        assertEquals(report.bytesUp, report.hourlyUp.sum())
        assertEquals(1, report.localFlows)
    }

    @Test fun noRecordedInternetTrafficDoesNotCreateActivityCells() {
        val report = AppReportBuilder.build("com.example.app", listOf(flow(dstIp = "10.1.1.1")), emptyList(), RuleEngine(), TrackerDb())
        assertTrue(report.dailyActivity.isEmpty())
        assertEquals(0L, report.hourlyUp.sum())
    }

    @Test fun insightsKeepsAllRetainedFlowsIncludingLocalAndUnknownApps() {
        val before = Instant.parse("2026-11-10T15:59:59Z").toEpochMilli()
        val after = Instant.parse("2026-11-10T16:00:00Z").toEpochMilli()
        val flows = listOf(
            flow(startAt = before, up = 1, down = 2),
            flow(startAt = after, dstIp = "192.168.1.2", up = 3, down = 4),
            flow(startAt = after, pkg = UNKNOWN_PKG, up = 5, down = 6),
        )
        val r = DailyReportBuilder.build(ctx(flows), emptyList(), RuleEngine(), TrackerDb(), ZoneId.of("Asia/Shanghai"))
        assertEquals(1L, r.dailyActivity.getValue(LocalDate.of(2026, 11, 10)).up[23])
        assertEquals(8L, r.dailyActivity.getValue(LocalDate.of(2026, 11, 11)).up[0])
        assertEquals(10L, r.dailyActivity.getValue(LocalDate.of(2026, 11, 11)).down[0])
        assertEquals(21L, r.hourlyBytes.sum())
        assertEquals(21L, r.dailyActivity.values.sumOf { it.up.sum() + it.down.sum() })
    }
}
