package com.nathanhanapps.wayd.apkscan

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BinaryXmlTest {
    @Test fun readsUtf8NamesAndTypedReference() {
        val node = BinaryXml.read(xml(true)).first()
        assertEquals("application", node.name)
        assertEquals(0x7f010001, node.android("networkSecurityConfig")?.data)
        assertEquals(1, node.android("networkSecurityConfig")?.type)
        assertEquals("true", node.android("usesCleartextTraffic")?.value)
        assertEquals(false, BinaryXml.read(xml(true)).last().start)
    }

    @Test fun readsUtf16StringPool() {
        assertEquals("application", BinaryXml.read(xml(false)).first().name)
    }

    @Test fun everyTruncationIsRejected() {
        val bytes = xml(true)
        for (size in bytes.indices) assertThrows(RuntimeException::class.java) { BinaryXml.read(bytes.copyOf(size)) }
    }

    @Test fun corruptChunkAndStringOffsetsAreRejected() {
        val bytes = xml(true)
        listOf(12 to Int.MAX_VALUE, 16 to 100_001, 36 to Int.MAX_VALUE).forEach { (at, value) ->
            val bad = bytes.copyOf()
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value)
            assertThrows(RuntimeException::class.java) { BinaryXml.read(bad) }
        }
    }

    private fun xml(utf8: Boolean): ByteArray {
        val strings = listOf(BinaryXml.ANDROID, "application", "networkSecurityConfig", "usesCleartextTraffic")
        val data = ByteArrayOutputStream()
        val offsets = strings.map { value ->
            val at = data.size()
            if (utf8) {
                data.write(value.length); data.write(value.length); data.write(value.toByteArray()); data.write(0)
            } else {
                data.write(value.length); data.write(0); data.write(value.toByteArray(Charsets.UTF_16LE)); data.write(byteArrayOf(0, 0))
            }
            at
        }
        while (data.size() % 4 != 0) data.write(0)
        val poolSize = 28 + strings.size * 4 + data.size()
        val start = 8 + poolSize
        val bytes = ByteArray(start + 76 + 24)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, 3); b.putShort(2, 8); b.putInt(4, bytes.size)
        b.putShort(8, 1); b.putShort(10, 28); b.putInt(12, poolSize)
        b.putInt(16, strings.size); b.putInt(24, if (utf8) 0x100 else 0); b.putInt(28, 28 + strings.size * 4)
        offsets.forEachIndexed { i, at -> b.putInt(36 + i * 4, at) }
        data.toByteArray().copyInto(bytes, 8 + 28 + strings.size * 4)
        b.putShort(start, 0x102); b.putShort(start + 2, 16); b.putInt(start + 4, 76)
        b.putInt(start + 20, 1); b.putShort(start + 24, 20); b.putShort(start + 26, 20); b.putShort(start + 28, 2)
        repeat(2) { i ->
            val at = start + 36 + i * 20
            b.putInt(at, 0); b.putInt(at + 4, 2 + i); b.putInt(at + 8, -1)
            b.putShort(at + 12, 8); bytes[at + 15] = (if (i == 0) 1 else 0x12).toByte()
            b.putInt(at + 16, if (i == 0) 0x7f010001 else -1)
        }
        val end = start + 76
        b.putShort(end, 0x103); b.putShort(end + 2, 16); b.putInt(end + 4, 24); b.putInt(end + 20, 1)
        return bytes
    }
}
