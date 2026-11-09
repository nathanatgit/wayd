package com.nathanhanapps.wayd.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFilterTest {
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
              FINE_LOCATION:
                [top-s] = access=46
        Uid u0a999:
          Package com.other.app:
            Attribution null:
              CAMERA:
                [top-s] = access=99
      snapshot:
        begin = 2026-11-09 18:44:30.000  (-29m18s795ms)
        end = 2026-11-09 18:59:30.000  (-14m18s795ms)
        Uid u0a999:
          Package com.other.app:
            Attribution null:
              CAMERA:
                [top-s] = access=7
        Uid u0a517:
          Package com.example.app:
            Attribution null:
              CAMERA:
                [fgsvc-s] = access=1
""".trimIndent()

    @Test fun keepsOnlyBucketHeadersAndTheTargetPackage() {
        val f = HistoryFilter("com.example.app")
        val kept = dump.lines().filter { f.accept(it) }
        assertTrue(kept.any { it.contains("FINE_LOCATION") })
        assertTrue(kept.any { it.contains("fgsvc-s") })
        assertEquals(2, kept.count { it.trim().startsWith("begin = ") })
        assertEquals(2, kept.count { it.trim().startsWith("end = ") })
        assertTrue(kept.none { it.contains("com.other.app") || it.contains("access=99") || it.contains("access=7") || it.contains("BLUETOOTH_SCAN") })
    }

    @Test fun reducesALargeDumpDramatically() {
        val big = buildString {
            repeat(50) { b ->
                append("        begin = 2026-11-09 18:00:00.000  (-${b}m1s1ms)\n        end = 2026-11-09 18:15:00.000  (0)\n")
                repeat(300) { u ->
                    append("        Uid u0a$u:\n          Package com.app$u.x:\n            Attribution null:\n              CAMERA:\n                [top-s] = access=1\n")
                }
                append("        Uid u0a1:\n          Package com.example.app:\n            Attribution null:\n              CAMERA:\n                [top-s] = access=2\n")
            }
        }
        val f = HistoryFilter("com.example.app")
        val out = big.lines().filter { f.accept(it) }.joinToString("\n")
        assertTrue("reduced from ${big.length} to ${out.length}", out.length * 20 < big.length)
        assertEquals(50, out.lines().count { it.contains("access=2") })
    }
}
