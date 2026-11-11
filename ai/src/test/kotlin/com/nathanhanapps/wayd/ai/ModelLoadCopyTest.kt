package com.nathanhanapps.wayd.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ModelLoadCopyTest {
    @get:Rule val temp = TemporaryFolder()
    private val name = "gemma-4-E4B-it.litertlm"
    private val bytes = "verified model fixture".toByteArray()
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test fun publishesVerifiedCopyWithRuntimeExtensionAndReusesIt() {
        val dir = temp.newFolder()
        val file = ModelLoadCopy.prepare(dir, name, bytes.size.toLong(), hash(bytes), { bytes.inputStream() })
        assertTrue(file.name.endsWith(".litertlm"))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(file, ModelLoadCopy.prepare(dir, name, bytes.size.toLong(), hash(bytes), { error("must reuse private copy") }))
    }

    @Test fun rejectsChangedBytesEvenWhenSizeMatches() {
        val dir = temp.newFolder()
        assertThrows(IllegalStateException::class.java) {
            ModelLoadCopy.prepare(dir, name, bytes.size.toLong(), hash(bytes), { ByteArray(bytes.size).inputStream() })
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun rejectsTruncatedAndOversizedSources() {
        for (size in listOf(bytes.size - 1L, bytes.size + 1L)) {
            val dir = temp.newFolder()
            assertThrows(IllegalStateException::class.java) {
                ModelLoadCopy.prepare(dir, name, size, hash(bytes), { bytes.inputStream() })
            }
            assertEquals(0, dir.listFiles()!!.size)
        }
    }

    @Test fun cancellationAndReadFailureLeaveNoLoadableFile() {
        val dir = temp.newFolder()
        assertThrows(CancellationException::class.java) {
            ModelLoadCopy.prepare(dir, name, bytes.size.toLong(), hash(bytes), { bytes.inputStream() }) { throw CancellationException() }
        }
        assertThrows(IOException::class.java) {
            ModelLoadCopy.prepare(dir, name, bytes.size.toLong(), hash(bytes), { throw IOException("provider unavailable") })
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun changedAcceptedHashReplacesOldCopy() {
        val dir = temp.newFolder()
        val old = ModelLoadCopy.prepare(dir, name, bytes.size.toLong(), hash(bytes), { bytes.inputStream() })
        val updated = "updated fixture".toByteArray()
        val file = ModelLoadCopy.prepare(dir, name, updated.size.toLong(), hash(updated), { updated.inputStream() })
        assertFalse(old.exists())
        assertArrayEquals(updated, file.readBytes())
    }
}
