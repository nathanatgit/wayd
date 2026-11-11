package com.nathanhanapps.wayd.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin mirror of the native `RouteSelection`, used to decide whether WAYD's own AI call is routed (D-050). */
class RoutingSnapshotTest {
    private val profile = byteArrayOf(1)

    @Test fun whitelistRoutesOnlyNamedOwners() {
        val s = RoutingSnapshot(profile, "0:com.nathanhanapps.wayd\n0:com.browser")
        assertTrue(s.selects("0:com.nathanhanapps.wayd"))
        assertFalse(s.selects("0:com.other"))
        assertFalse(s.selects("10:com.nathanhanapps.wayd"))
    }

    @Test fun blacklistRoutesEveryOwnerOfTheUserExceptExcluded() {
        val s = RoutingSnapshot(profile, "!exclude:0\n0:com.bank")
        assertTrue(s.selects("0:com.nathanhanapps.wayd"))
        assertFalse(s.selects("0:com.bank"))
        assertFalse("other Android users are never routed", s.selects("10:com.nathanhanapps.wayd"))
        assertFalse(RoutingSnapshot(profile, "!exclude:0\n0:com.nathanhanapps.wayd").selects("0:com.nathanhanapps.wayd"))
    }

    @Test fun noProfileOrBadOwnerNeverRoutes() {
        assertFalse(RoutingSnapshot(byteArrayOf(), "!exclude:0").selects("0:com.nathanhanapps.wayd"))
        assertFalse(RoutingSnapshot(profile, "!exclude:0").selects(""))
        assertFalse(RoutingSnapshot(profile, "!exclude:0").selects("0:not a package"))
    }
}
