package com.nathanhanapps.wayd.apkscan

/** Bounded Android resource XML reader. No code, entities or external resources are evaluated. */
internal object BinaryXml {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
    data class Attribute(val namespace: String?, val name: String, val value: String, val type: Int, val data: Int)
    data class Element(val name: String, val attributes: List<Attribute>, val start: Boolean) {
        fun android(name: String): Attribute? = attributes.firstOrNull { it.namespace == ANDROID && it.name == name }
    }

    fun read(bytes: ByteArray): List<Element> {
        val b = CheckedBytes(bytes)
        require(bytes.size in 8..4 * 1024 * 1024 && b.u16(0) == 3 && b.u16(2) == 8)
        require(b.u32(4) == bytes.size)
        var strings = emptyList<String>()
        var position = 8
        val result = ArrayList<Element>()
        while (position < bytes.size) {
            val type = b.u16(position)
            val header = b.u16(position + 2)
            val size = b.u32(position + 4)
            require(header >= 8 && size >= header && size <= bytes.size - position)
            val end = position + size
            when (type) {
                1 -> {
                    require(header >= 28)
                    val count = b.u32(position + 8)
                    val styles = b.u32(position + 12)
                    val utf8 = b.u32(position + 16) and 0x100 != 0
                    val dataStart = b.u32(position + 20)
                    require(count <= 100_000 && styles <= 100_000)
                    require(header.toLong() + (count.toLong() + styles) * 4 <= size)
                    require(dataStart >= header + (count + styles) * 4 && dataStart <= size)
                    strings = List(count) { i ->
                        val offset = b.u32(position + header + i * 4)
                        require(offset < size - dataStart)
                        poolString(b, position + dataStart + offset, end, utf8)
                    }
                }
                0x102 -> {
                    require(header >= 16 && size >= 36)
                    val ext = position + header
                    require(ext + 20 <= end)
                    val name = strings[b.u32(ext + 4)]
                    val start = b.u16(ext + 8)
                    val stride = b.u16(ext + 10)
                    val count = b.u16(ext + 12)
                    require(start >= 20 && stride >= 20 && count <= 256)
                    require(ext.toLong() + start + count.toLong() * stride <= end)
                    val attrs = List(count) { i ->
                        val at = ext + start + i * stride
                        val ns = b.i32(at)
                        val key = strings[b.u32(at + 4)]
                        val raw = b.i32(at + 8)
                        val valueType = b.u8(at + 15)
                        val data = b.i32(at + 16)
                        require(b.u16(at + 12) >= 8)
                        val value = when {
                            raw >= 0 -> strings[raw]
                            valueType == 3 -> strings[data]
                            valueType == 0x12 -> (data != 0).toString()
                            valueType == 1 -> "@${data.toUInt().toString(16)}"
                            else -> data.toString()
                        }
                        Attribute(if (ns == -1) null else strings[ns], key, value, valueType, data)
                    }
                    result += Element(name, attrs, true)
                }
                0x103 -> {
                    require(header >= 16 && header + 8 <= size)
                    result += Element(strings[b.u32(position + header + 4)], emptyList(), false)
                }
            }
            require(result.size <= 50_000)
            position = end
        }
        return result
    }

    private fun poolString(b: CheckedBytes, start: Int, end: Int, utf8: Boolean): String {
        var p = start
        fun length(): Int {
            val first = if (utf8) b.u8(p++) else b.u16(p).also { p += 2 }
            val mask = if (utf8) 0x80 else 0x8000
            if (first and mask == 0) return first
            val next = if (utf8) b.u8(p++) else b.u16(p).also { p += 2 }
            return ((first and (mask - 1)) shl if (utf8) 8 else 16) or next
        }
        val chars = length()
        require(chars in 0..4096)
        val size = if (utf8) length() else chars * 2
        val terminator = if (utf8) 1 else 2
        require(size in 0..16384 && p.toLong() + size + terminator <= end)
        require(if (utf8) b.u8(p + size) == 0 else b.u16(p + size) == 0)
        return b.bytes.copyOfRange(p, p + size).toString(if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
    }
}

internal class CheckedBytes(val bytes: ByteArray) {
    fun u8(p: Int): Int = bytes[p].toInt() and 255
    fun u16(p: Int): Int = u8(p) or (u8(p + 1) shl 8)
    fun i32(p: Int): Int = u16(p) or (u16(p + 2) shl 16)
    fun u32(p: Int): Int = i32(p).also { require(it >= 0) }
}
