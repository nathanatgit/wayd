package com.nathanhanapps.wayd.apkscan

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BoundedInputTest {
    @Test fun retainsExtraByteToDetectOverflow() {
        val stream = ByteArrayInputStream(ByteArray(100))
        assertEquals(11, stream.readBounded(10).size)
        assertEquals(89, stream.available())
    }

    @Test fun exactAndEmptyInputs() {
        assertEquals(10, ByteArrayInputStream(ByteArray(10)).readBounded(10).size)
        assertEquals(0, ByteArrayInputStream(byteArrayOf()).readBounded(10).size)
    }
}
