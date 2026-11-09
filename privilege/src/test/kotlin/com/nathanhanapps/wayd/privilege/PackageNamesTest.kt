package com.nathanhanapps.wayd.privilege

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamesTest {
    @Test fun acceptsRealPackageNames() {
        listOf("com.ss.android.ugc.aweme", "com.tencent.mm", "com.example.app_1", "tv.danmaku.bili", "a.b").forEach { assertTrue(it, PackageNames.isValid(it)) }
    }

    @Test fun rejectsAnythingThatCouldInjectIntoACommand() {
        listOf(
            null, "", "com", "com..x", ".com.x", "com.x.", "com.x; rm -rf /", "com.x && id", "com.x|cat", "com.x`id`", "com.x$(id)",
            "com.x\nid", "com.x id", "--user 0", "../x.y", "1com.x", "com.x'", "com.x\"", "a".repeat(300) + ".b",
        ).forEach { assertFalse(it ?: "null", PackageNames.isValid(it)) }
    }
}
