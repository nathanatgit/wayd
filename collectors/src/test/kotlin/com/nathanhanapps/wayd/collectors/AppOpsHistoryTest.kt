package com.nathanhanapps.wayd.collectors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture is a trimmed copy of real `dumpsys appops --history` output from a Nubia NX733J (Android 16). */
class AppOpsHistoryTest {
    private val now = 1_000_000_000_000L // arbitrary fixed "now"
    private val min = 60_000L

    private val dump = """
Current AppOps Service state:
  History:  mode=HISTORICAL_MODE_ENABLED_ACTIVE

      snapshot:
        begin = 2026-11-09 18:59:30.594  (-14m18s795ms)
        end = 2026-11-09 19:13:49.389  (0)
            Attribution null:
              BLUETOOTH_SCAN:
                [pers-s] = access=2
        Uid u0a517:
          Package com.example.app:
            Attribution null:
              MONITOR_LOCATION:
                [top-s] = access=4, duration=+30s10ms
              WIFI_SCAN:
                [top-s] = access=11
              CONTROL_AUDIO_PARTIAL:
                [top-s] = access=56, duration=+15m21s528ms
                [bg-s] = access=2, duration=+3ms
              GET_USAGE_STATS:
                [top-s] = reject=10
                [bg-s] = reject=2
              FINE_LOCATION:
                [top-s] = access=46
                [bg-s] = access=6
              READ_CLIPBOARD:
                [cch-s] = access=3
        Uid u0a999:
          Package com.other.app:
            Attribution null:
              CAMERA:
                [top-s] = access=99
      snapshot:
        begin = 2026-11-09 18:44:30.000  (-29m18s795ms)
        end = 2026-11-09 18:59:30.000  (-14m18s795ms)
        Uid u0a517:
          Package com.example.app:
            Attribution null:
              CAMERA:
                [fgsvc-s] = access=1, duration=+2s374ms
              FINE_LOCATION:
                [top-s] = access=10
""".trimIndent()

    @Test fun parsesBucketsStatesAndIgnoresOtherPackages() {
        val b = AppOpsHistoryParser.parse(dump, "com.example.app", now)
        assertTrue(b.none { it.op == "BLUETOOTH_SCAN" }) // before any Package header
        assertTrue(b.none { it.access == 99 }) // other package
        val loc = b.filter { it.op == "FINE_LOCATION" }
        assertEquals(3, loc.size)
        val bg = loc.first { it.state == ProcState.BACKGROUND }
        assertEquals(6, bg.access)
        assertEquals(now, bg.end)
        assertEquals(now - (14 * min + 18_795), bg.start)
        val cam = b.single { it.op == "CAMERA" }
        assertEquals(ProcState.FOREGROUND_SERVICE, cam.state)
        assertEquals(2_374L, cam.durationMs)
        assertEquals(30_010L, b.single { it.op == "MONITOR_LOCATION" }.durationMs)
        val usage = b.filter { it.op == "GET_USAGE_STATS" }
        assertEquals(listOf(10, 2), usage.map { it.reject })
        assertEquals(ProcState.CACHED, b.single { it.op == "READ_CLIPBOARD" }.state)
    }

    @Test fun relativeTimes() {
        assertEquals(0L, AppOpsHistoryParser.relativeMs("0"))
        assertEquals(1L * 86_400_000 + 5 * 3_600_000 + 30 * 60_000 + 5_000 + 559, AppOpsHistoryParser.relativeMs("-1d5h30m5s559ms"))
        assertEquals(15 * min + 21_528, AppOpsHistoryParser.relativeMs("+15m21s528ms"))
        assertEquals(0L, AppOpsHistoryParser.relativeMs("garbage"))
    }

    @Test fun hostileInputDoesNotThrow() {
        val junk = "\u0000\n[top-s] = access=99999999999999999999\nPackage com.example.app:\nFOO:\n[x-s] = access=1\n" + "A".repeat(100_000)
        AppOpsHistoryParser.parse(junk, "com.example.app", now)
        AppOpsHistoryParser.parse("", "com.example.app", now)
    }

    @Test fun timelineGroupsOpsIntoCategoriesWithVisibleAndBackgroundSplit() {
        val b = AppOpsHistoryParser.parse(dump, "com.example.app", now)
        val uses = PermissionTimeline.build(b, now - 60 * min, now, zoneOffsetMs = 0)
        val loc = uses.first { it.category == PermissionCategory.LOCATION }
        // 4 (MONITOR) + 46 + 6 + 10 location accesses, 6 of them in the background
        assertEquals(66, loc.accesses)
        assertEquals(6, loc.backgroundAccesses)
        assertEquals(60, loc.visibleAccesses)
        val clip = uses.first { it.category == PermissionCategory.CLIPBOARD }
        assertEquals(3, clip.backgroundAccesses) // cached counts as background
        assertEquals(12, uses.first { it.category == PermissionCategory.APP_USAGE }.rejects)
        assertNotNull(uses.first { it.category == PermissionCategory.CAMERA }.lastBucketEnd)
        assertTrue(uses.none { it.category == PermissionCategory.MICROPHONE }) // not present
        assertEquals(loc.accesses, loc.hourly.sum())
    }

    @Test fun windowClippingIsProportional() {
        val bucket = OpBucket("FINE_LOCATION", start = 0, end = 4 * 3_600_000L, state = ProcState.TOP, access = 40, reject = 0, durationMs = 0)
        val uses = PermissionTimeline.build(listOf(bucket), windowStart = 3_600_000L, windowEnd = 2 * 3_600_000L, zoneOffsetMs = 0)
        val loc = uses.single()
        assertEquals(10, loc.accesses) // one quarter of the 4-hour bucket
        assertTrue(loc.coarse)
        assertEquals(10, loc.hourly[1])
    }
}

class ParseAllTest {
    @Test fun parsesSeveralPackagesInOnePass() {
        val text = """
            begin = 2026-11-09 18:59:30.594  (-14m18s795ms)
            end = 2026-11-09 19:13:49.389  (0)
            Uid u0a1:
              Package com.a.app:
                READ_CLIPBOARD:
                  [cch-s] = access=3
            Uid u0a2:
              Package com.b.app:
                CAMERA:
                  [fg-s] = access=2
        """.trimIndent()
        val all = AppOpsHistoryParser.parseAll(text, now = 1_000_000_000L)
        assertEquals(setOf("com.a.app", "com.b.app"), all.keys)
        assertEquals(ProcState.CACHED, all.getValue("com.a.app").single().state)
        assertEquals(setOf("com.a.app"), AppOpsHistoryParser.parseAll(text, 1_000_000_000L) { it == "com.a.app" }.keys)
        assertTrue(PermissionCategory.watchedOps.contains("READ_CLIPBOARD"))
    }
}
