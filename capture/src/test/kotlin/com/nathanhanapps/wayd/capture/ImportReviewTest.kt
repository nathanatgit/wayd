package com.nathanhanapps.wayd.capture

import com.nathanhanapps.wayd.core.db.BlockRule
import com.nathanhanapps.wayd.core.model.BlockScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportReviewTest {
    @Test fun everyImportedDefinitionMapsToCurrentProfileAndStartsPending() {
        for (enabled in listOf(false, true)) {
            val imported = PortableBlockRule("9ce10f14-7d24-4496-b8b1-d5963450c027", 3, enabled,
                "org.example.app", listOf("a".repeat(64)), "HOST", "example.com")
            val local = imported.pendingRule(10, 1234)
            org.junit.Assert.assertEquals(0L, local.id)
            org.junit.Assert.assertEquals(10, local.userId)
            org.junit.Assert.assertEquals(enabled, local.enabled)
            org.junit.Assert.assertEquals(imported.id, local.portableId)
            org.junit.Assert.assertEquals(3, local.portableRevision)
            org.junit.Assert.assertEquals(1234L, local.createdAt)
            assertFalse(local.canActivateAfterReview())
        }
    }

    @Test fun pendingImportCannotActivateEvenWhenEnabledAndIdentityIsBound() {
        val rule = BlockRule(
            scope = BlockScope.APP,
            pkg = "org.example.app",
            host = "*",
            enabled = true,
            createdAt = 1,
            matchKind = "APP",
            signingSha256 = "a".repeat(64),
            portableId = "9ce10f14-7d24-4496-b8b1-d5963450c027",
            needsImportReview = true,
        )
        assertFalse(rule.canActivateAfterReview())
        assertTrue(rule.copy(needsImportReview = false).canActivateAfterReview())
    }
}
