package com.nathanhanapps.wayd.enrich

import com.nathanhanapps.wayd.core.model.TrackerCategory

/** Who runs a domain and what it is for. */
data class DomainInfo(val company: String, val category: TrackerCategory)

/**
 * Domain → owner/category lookup by longest matching suffix ("a.hm.baidu.com" -> "hm.baidu.com" -> "baidu.com").
 * Backed by [SeedDomains], a small hand-curated list. It is a *seed*: replace or extend it with a licence-checked
 * dataset (e.g. DuckDuckGo Tracker Radar) before relying on completeness. Unknown domains return null, never a guess.
 */
class TrackerDb(entries: Collection<SeedEntry> = SeedDomains.all) {
    private val map: Map<String, DomainInfo> = entries.associate { it.domain to DomainInfo(it.company, it.category) }

    fun lookup(host: String?): DomainInfo? {
        if (host.isNullOrBlank()) return null
        var h = host.trim().trimEnd('.').lowercase()
        while (true) {
            map[h]?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0) return null
            h = h.substring(dot + 1)
        }
    }

    fun size() = map.size
}

/** Hosts of public DNS-over-HTTPS resolvers: an app contacting one resolves names outside the system resolver. */
object DohHosts {
    private val hosts = setOf(
        "dns.google", "dns.google.com", "cloudflare-dns.com", "one.one.one.one", "mozilla.cloudflare-dns.com",
        "dns.alidns.com", "doh.pub", "dns.pub", "doh.360.cn", "dns.quad9.net", "doh.opendns.com", "dns.adguard.com",
        "dns.nextdns.io",
    )

    fun isDoh(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase().trimEnd('.')
        return hosts.any { h == it || h.endsWith(".$it") }
    }
}

/** True for addresses that never leave the local network or device (hostnames are not expected for them). */
object PrivateAddr {
    fun isPrivate(ip: String): Boolean {
        if (ip.contains(':')) {
            val l = ip.lowercase()
            return l == "::1" || l.startsWith("fe80:") || l.startsWith("fc") || l.startsWith("fd")
        }
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 169 && p[1] == 254) ||
            (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
}
