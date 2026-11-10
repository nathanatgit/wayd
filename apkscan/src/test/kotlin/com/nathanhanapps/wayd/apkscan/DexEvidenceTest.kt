package com.nathanhanapps.wayd.apkscan

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DexEvidenceTest {
    @Test fun definedSdkAndHostOnly() {
        val result = DexEvidence.read(dex(listOf("Lcom/umeng/analytics/Test;", "https://user:secret@api.example.com/path?token=secret"), listOf(0)))
        assertEquals(mapOf("com.umeng." to "Umeng"), result.sdks)
        assertEquals(setOf("api.example.com"), result.endpoints)
    }

    @Test fun referencedSdkIsNotDefinedSdk() {
        val result = DexEvidence.read(dex(listOf("Lcom/umeng/analytics/Test;", "Ljava/lang/reflect/Method;")))
        assertTrue(result.sdks.isEmpty())
        assertTrue(result.reflection)
    }

    @Test fun invalidOffsetsAndIndicesAreRejected() {
        val bytes = dex(listOf("Ltest/App;"), listOf(0))
        val variants = listOf(60 to Int.MAX_VALUE, 56 to 500_001, 40 to 0x78563412, 100 to 110)
        variants.forEach { (offset, value) ->
            val bad = bytes.copyOf()
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertThrows(RuntimeException::class.java) { DexEvidence.read(bad) }
        }
    }

    @Test fun everyTruncatedPrefixIsRejected() {
        val bytes = dex(listOf("Ltest/App;"), listOf(0))
        for (size in bytes.indices) assertThrows(RuntimeException::class.java) { DexEvidence.read(bytes.copyOf(size)) }
    }

    @Test fun unsupportedContainerDexIsRejected() {
        val bytes = dex(emptyList())
        bytes[6] = '1'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { DexEvidence.read(bytes) }
    }

    @Test fun overlongStringIsSkippedAndReported() {
        val result = DexEvidence.read(dex(listOf("a".repeat(4097))))
        assertTrue(result.limited)
        assertTrue(result.endpoints.isEmpty())
    }

    @Test fun endpointsAreConservativeAndBounded() {
        assertEquals("example.com", DexEvidence.endpoint("https://EXAMPLE.COM:443/a?b=c"))
        assertEquals("192.0.2.3", DexEvidence.endpoint("192.0.2.3"))
        listOf("1.2.3.999", "com.example.Test.class", "file:///etc/passwd", "https://bad host.com/", "bad..example.com", "a".repeat(2050)).forEach {
            assertNull(it, DexEvidence.endpoint(it))
        }
        val result = DexEvidence.read(dex((0..300).map { "https://host$it.example.com/" }))
        assertEquals(256, result.endpoints.size)
        assertTrue(result.limited)
    }

    private fun dex(strings: List<String>, defined: List<Int> = emptyList()): ByteArray {
        val stringsAt = 112
        val typesAt = stringsAt + strings.size * 4
        val classesAt = typesAt + strings.size * 4
        val dataAt = classesAt + defined.size * 32
        val data = ArrayList<Byte>()
        val offsets = strings.map { text ->
            val at = dataAt + data.size
            var length = text.length
            do {
                val next = length and 127
                length = length ushr 7
                data += (next or if (length > 0) 128 else 0).toByte()
            } while (length > 0)
            data += text.toByteArray().toList()
            data += 0.toByte()
            at
        }
        val bytes = ByteArray(dataAt + data.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        "dex\n039\u0000".toByteArray().copyInto(bytes)
        b.putInt(32, bytes.size); b.putInt(36, 112); b.putInt(40, 0x12345678)
        b.putInt(56, strings.size); b.putInt(60, stringsAt)
        b.putInt(64, strings.size); b.putInt(68, typesAt)
        b.putInt(96, defined.size); b.putInt(100, classesAt)
        offsets.forEachIndexed { i, at -> b.putInt(stringsAt + i * 4, at); b.putInt(typesAt + i * 4, i) }
        defined.forEachIndexed { i, type -> b.putInt(classesAt + i * 32, type) }
        data.toByteArray().copyInto(bytes, dataAt)
        return bytes
    }
}
