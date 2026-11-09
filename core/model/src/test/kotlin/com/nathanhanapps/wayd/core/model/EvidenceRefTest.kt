package com.nathanhanapps.wayd.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvidenceRefTest {
    @Test fun roundTrips() {
        val ref = EvidenceRef(EvidenceKind.FLOW, 812)
        assertEquals("f:812", ref.toString())
        assertEquals(ref, EvidenceRef.parse("f:812"))
    }

    @Test fun rejectsMalformed() {
        assertNull(EvidenceRef.parse("z:1"))
        assertNull(EvidenceRef.parse("f:"))
        assertNull(EvidenceRef.parse("f:abc"))
        assertNull(EvidenceRef.parse("812"))
    }
}
