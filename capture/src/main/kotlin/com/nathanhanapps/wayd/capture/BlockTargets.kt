package com.nathanhanapps.wayd.capture

import java.net.IDN

/** Typed rule targets; never interpret a host as a regex, URL, shell command or DNS lookup. */
object BlockTargets {
    fun httpHost(value: String): String? {
        if (':' !in value) return host(value)
        val parts = value.split(':')
        return if (parts.size == 2 && parts[1].isNotEmpty() && parts[1].all { it in '0'..'9' } &&
            (parts[1].toIntOrNull() ?: 0) in 1..65535) host(parts[0]) else null
    }

    fun host(value: String): String? = runCatching {
        val ascii = IDN.toASCII(value.removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(java.util.Locale.ROOT)
        ascii.takeIf { it.isNotEmpty() && it.length <= 253 && !it.all { c -> c.isDigit() || c == '.' } &&
            it.split('.').all { label -> label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
                label.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } } }
    }.getOrNull()

    fun ip(value: String): String? {
        if (value.contains('%') || value.length > 45) return null
        if (':' in value) {
            if (!value.all { it in "0123456789abcdefABCDEF:." }) return null
            return runCatching { java.net.InetAddress.getByName(value).hostAddress }.getOrNull()
        }
        val parts = value.split('.')
        return value.takeIf { parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 &&
            p.all { it in '0'..'9' } && (p == "0" || !p.startsWith('0')) && (p.toIntOrNull() ?: -1) in 0..255 } }
    }

    fun packageName(value: String): Boolean = value.isNotEmpty() && value.length <= 255 &&
        value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' }
}
