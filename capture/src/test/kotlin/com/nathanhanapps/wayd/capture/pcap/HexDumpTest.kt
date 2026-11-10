package com.nathanhanapps.wayd.capture.pcap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexDumpTest {
    @Test fun formatsOffsetHexAndAsciiLikeAHexViewer() {
        val text = HexDump.format("GET /index\u0000ÿ HTTP/1.1".toByteArray(Charsets.ISO_8859_1))
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertEquals("00000000  47 45 54 20 2f 69 6e 64  65 78 00 ff 20 48 54 54  |GET /index.. HTT|", lines[0])
        assertTrue(lines[1].startsWith("00000010  50 2f 31 2e 31 "))
        assertTrue(lines[1].endsWith("|P/1.1|"))
    }

    @Test fun cutsAtTheLimitAndHandlesEmptyInput() {
        assertEquals(256, HexDump.format(ByteArray(10_000), max = 4096).lines().size)
        assertEquals("", HexDump.format(ByteArray(0)))
    }
}
