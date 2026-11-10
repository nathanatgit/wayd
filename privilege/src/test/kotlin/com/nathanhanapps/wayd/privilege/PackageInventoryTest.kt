package com.nathanhanapps.wayd.privilege

import org.junit.Assert.*
import org.junit.Test

class PackageInventoryTest {
    @Test fun acceptsPackagesAndRejectsShellErrorsAndInjection() {
        assertEquals(setOf("android", "com.example.app"), PackageInventory.parse(
            "package:android\npackage:com.example.app\npackage:com.example.app\nError: unavailable\npackage:com.bad;reboot\npackage:../../etc\n"))
    }

    @Test fun capsReplyAndPackageCount() {
        assertTrue(PackageInventory.parse("x".repeat(600 * 1024 + 1)).isEmpty())
        assertEquals(5000, PackageInventory.parse((0..5100).joinToString("\n") { "package:com.example.p$it" }).size)
    }
}
