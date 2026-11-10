package com.nathanhanapps.wayd.apkscan

import java.net.URI

/** Small, curated prefix list. Matches defined classes, never strings merely mentioning an SDK. */
internal object DexEvidence {
    val signatures = linkedMapOf(
        "com.umeng." to "Umeng", "com.igexin." to "Getui", "cn.jpush." to "JPush",
        "cn.jiguang." to "Jiguang", "com.baidu.location." to "Baidu Location",
        "com.baidu.mobstat." to "Baidu Mobstat", "com.tencent.bugly." to "Tencent Bugly",
        "com.tencent.stat." to "Tencent MTA", "com.huawei.hms.push." to "Huawei Push",
        "com.xiaomi.mipush." to "Xiaomi Push", "com.heytap.msp.push." to "OPPO Push",
        "com.vivo.push." to "vivo Push", "com.cmic.sso." to "China Mobile SSO",
        "com.unicom.xiaowo." to "China Unicom", "cn.com.chinatelecom.account." to "China Telecom",
    )
    val protectors = mapOf("libjiagu" to "360 Jiagu", "libDexHelper" to "SecNeo", "libexec" to "Bangcle")
    data class Result(val sdks: Map<String, String>, val endpoints: Set<String>, val reflection: Boolean, val limited: Boolean)

    fun read(bytes: ByteArray): Result {
        val b = CheckedBytes(bytes)
        require(bytes.size in 112..32 * 1024 * 1024)
        require(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)))
        require(bytes[7] == 0.toByte())
        // Container DEX 041 and reverse-endian files need a different reader; do not silently misparse them.
        require(bytes.copyOfRange(4, 7).toString(Charsets.US_ASCII) in setOf("035", "037", "038", "039", "040"))
        require(b.u32(32) == bytes.size && b.u32(36) == 112 && b.i32(40) == 0x12345678)
        fun table(countAt: Int, offsetAt: Int, stride: Int, max: Int): Pair<Int, Int> {
            val count = b.u32(countAt)
            val offset = b.u32(offsetAt)
            require(count <= max && offset.toLong() + count.toLong() * stride <= bytes.size)
            require(count == 0 || offset >= 112)
            return count to offset
        }
        val (stringCount, strings) = table(56, 60, 4, 500_000)
        val (typeCount, types) = table(64, 68, 4, 65_536)
        val (classCount, classes) = table(96, 100, 32, 65_536)
        var limited = false
        fun string(index: Int): String? {
            require(index in 0 until stringCount)
            var p = b.u32(strings + index * 4)
            var terminated = false
            var chars = 0L
            for (i in 0..4) {
                val n = b.u8(p++)
                chars = chars or ((n and 127).toLong() shl (i * 7))
                if (n and 128 == 0) { terminated = true; break }
            }
            require(terminated && chars <= Int.MAX_VALUE)
            if (chars > 4096) { limited = true; return null }
            val start = p
            while (p < bytes.size && bytes[p] != 0.toByte() && p - start <= 12288) p++
            require(p < bytes.size && p - start <= 12288)
            // ASCII subset is enough for signatures and endpoints. MUTF-8 text is deliberately not interpreted.
            if ((start until p).any { b.u8(it) >= 128 }) return null
            return bytes.copyOfRange(start, p).toString(Charsets.US_ASCII)
        }
        val sdks = linkedMapOf<String, String>()
        repeat(classCount) { i ->
            val type = b.u32(classes + i * 32)
            require(type < typeCount)
            val descriptor = string(b.u32(types + type * 4)) ?: return@repeat
            val name = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
            signatures.forEach { (prefix, sdk) -> if (name.startsWith(prefix)) sdks[prefix] = sdk }
        }
        val endpoints = linkedSetOf<String>()
        var reflection = false
        repeat(stringCount) { i ->
            val text = string(i) ?: return@repeat
            if (text == "Ljava/lang/reflect/Method;" || text == "Ljava/lang/reflect/Field;") reflection = true
            val host = endpoint(text)
            if (host != null) {
                if (endpoints.size < 256) endpoints += host else limited = true
            }
        }
        return Result(sdks, endpoints, reflection, limited)
    }

    /** Keep host only: embedded credentials, paths, API keys and query values are never retained. */
    fun endpoint(value: String): String? {
        if (value.length !in 4..2048 || value.any { it <= ' ' }) return null
        val host = when {
            value.startsWith("https://") || value.startsWith("http://") -> runCatching { URI(value).host }.getOrNull()
            '/' !in value && ':' !in value && '@' !in value -> value
            else -> null
        }?.lowercase()?.trimEnd('.') ?: return null
        if (host.length > 253 || '.' !in host) return null
        if (host.all { it.isDigit() || it == '.' }) {
            return host.takeIf { it.split('.').size == 4 && it.split('.').all { part -> part.toIntOrNull() in 0..255 } }
        }
        val labels = host.split('.')
        if (labels.size < 2 || labels.last().length !in 2..24 || labels.last().any { it !in 'a'..'z' }) return null
        if (labels.any { it.length !in 1..63 || it.first() == '-' || it.last() == '-' || it.any { c -> c !in 'a'..'z' && c !in '0'..'9' && c != '-' } }) return null
        // Common class/file strings are not credible domain literals.
        if (labels.last() in setOf("java", "class", "kt", "xml", "png", "jpg", "so", "dex")) return null
        return host
    }
}
