package com.nathanhanapps.wayd.capture

import java.net.URI

/** An explicit mirror choice only applies to public, token-free GitHub raw URLs. */
object RoutingResourceUrls {
    fun forDownload(address: String, mirror: Boolean): String {
        if (!mirror) return address
        val uri = URI(address)
        if (uri.scheme != "https" || uri.host != "raw.githubusercontent.com" || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port != -1) return address
        val parts = uri.rawPath.removePrefix("/").split("/")
        if (parts.size < 4 || parts.any { it.isEmpty() || it == "." || it == ".." || '%' in it }) return address
        return "https://cdn.jsdelivr.net/gh/${parts[0]}/${parts[1]}@${parts[2]}/${parts.drop(3).joinToString("/")}"
    }
}
