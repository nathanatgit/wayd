package com.nathanhanapps.wayd.apkscan

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** API-29-compatible read. Keep one extra byte so unknown ZIP sizes cannot bypass the cap. */
internal fun InputStream.readBounded(limit: Int): ByteArray {
    require(limit in 0..32 * 1024 * 1024)
    val output = ByteArrayOutputStream(minOf(limit + 1, 8192))
    val buffer = ByteArray(8192)
    while (output.size() <= limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
        if (count == -1) break
        if (count == 0) {
            val next = read()
            if (next == -1) break
            output.write(next)
        } else output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
