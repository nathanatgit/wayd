package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.ApkFact
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.db.PermEvent
import com.nathanhanapps.wayd.core.model.AdviceReason
import com.nathanhanapps.wayd.core.model.ApkFactKind
import com.nathanhanapps.wayd.core.model.BlockAdvice
import com.nathanhanapps.wayd.core.model.ConnectionRole
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.PermissionMeaning
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.core.model.RoleSource
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.enrich.SuffixLookup
import com.nathanhanapps.wayd.enrich.TrackerDb
import java.util.Locale

data class RoleMatch(val role: ConnectionRole, val source: RoleSource)

/**
 * Algorithm view of one destination host for one app, aggregated over every supplied record of that host.
 * The block target is a host, so advice must not differ between two records of the same host.
 */
data class HostSummary(
    val subject: String,
    val host: String?,
    val role: RoleMatch,
    val advice: BlockAdvice,
    val reasons: List<AdviceReason>,
    val flows: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val screenOffBytes: Long,
    /** True when at least one record has attributed TCP SNI/HTTP-Host evidence and recorded bytes. */
    val domainBlockAvailable: Boolean,
)

data class RankedOp(val event: PermEvent, val overlapsTarget: Boolean)

/** Read-only descriptive rules. APK presence is never a permission grant or a causal network attribution. */
object LabelRules {
    internal val tracker = TrackerDb()

    // Specific service endpoints precede the broader company/category list. Suffix-matched on whole labels; no requests are made to these hosts.
    private val curated = SuffixLookup(buildMap {
        listOf("apilocate.amap.com", "cnloc.map.baidu.com", "loc.map.baidu.com", "lbs.map.qq.com", "rgeorest.map.qq.com").forEach { put(it, ConnectionRole.LOCATION) }
        listOf("account.xiaomi.com", "passport.baidu.com", "accounts.google.com", "graph.qq.com", "open.weixin.qq.com").forEach { put(it, ConnectionRole.LOGIN) }
    })

    /** Words in non-registrable host labels. Exact token match only, first label first, so "catalog" never reads as "log". */
    private val hostWords: Map<String, ConnectionRole> = buildMap {
        fun add(role: ConnectionRole, vararg words: String) = words.forEach { put(it, role) }
        add(ConnectionRole.ANALYTICS, "log", "logs", "applog", "stat", "stats", "track", "tracking", "tracker", "analytics", "beacon", "metrics", "telemetry")
        add(ConnectionRole.DIAGNOSTICS, "crash", "crashlytics", "apm", "bugly", "sentry")
        add(ConnectionRole.ADS, "ad", "ads", "adx", "adserver", "adservice")
        add(ConnectionRole.PUSH, "push", "mipush")
        add(ConnectionRole.UPDATE, "update", "upgrade", "ota")
        add(ConnectionRole.LOGIN, "passport", "login", "auth", "oauth", "sso", "account", "accounts")
        add(ConnectionRole.LOCATION, "loc", "lbs", "geo", "location", "apilocate")
        add(ConnectionRole.CONTENT, "cdn", "img", "image", "images", "static", "pic", "pics", "vod")
    }

    /** Words for ranking components/SDKs against a role. Broader than [hostWords] because they only order context, never label. */
    internal val roleWords: Map<ConnectionRole, Set<String>> = mapOf(
        ConnectionRole.LOCATION to setOf("location", "loc", "lbs", "geo", "map", "maps", "amap", "gps", "apilocate", "geofence"),
        ConnectionRole.LOGIN to setOf("login", "auth", "oauth", "account", "passport", "sso", "signin", "wxentry", "authorize", "authenticator"),
        ConnectionRole.PUSH to setOf("push", "mipush", "hms", "notification", "notify", "mcs", "jpush", "getui", "messaging", "fcm", "c2dm"),
        ConnectionRole.ANALYTICS to setOf("analytics", "stat", "stats", "statistic", "track", "tracker", "tracking", "applog", "umeng", "beacon", "metrics", "telemetry"),
        ConnectionRole.DIAGNOSTICS to setOf("crash", "bugly", "apm", "anr", "diagnostic", "diagnostics", "monitor", "sentry", "crashlytics"),
        ConnectionRole.ADS to setOf("ad", "ads", "adx", "advert", "advertising", "splash", "reward", "banner", "pangle", "gdt", "mediation", "interstitial"),
        ConnectionRole.MEDIA to setOf("video", "player", "media", "audio", "live", "camera"),
        ConnectionRole.CONTENT to setOf("cdn", "image", "img", "static", "pic", "resource", "assets"),
        ConnectionRole.UPDATE to setOf("update", "upgrade", "ota", "patch", "hotfix", "tinker", "install"),
    )

    /** Permission capabilities that plausibly relate to a role. NETWORK is omitted: nearly every app declares it. */
    internal val roleMeanings: Map<ConnectionRole, Set<PermissionMeaning>> = mapOf(
        ConnectionRole.LOCATION to setOf(PermissionMeaning.LOCATION, PermissionMeaning.MEDIA_LOCATION, PermissionMeaning.WIFI, PermissionMeaning.BLUETOOTH),
        ConnectionRole.LOGIN to setOf(PermissionMeaning.ACCOUNTS, PermissionMeaning.PHONE, PermissionMeaning.SMS),
        ConnectionRole.PUSH to setOf(PermissionMeaning.PUSH, PermissionMeaning.NOTIFICATIONS, PermissionMeaning.BOOT),
        ConnectionRole.ADS to setOf(PermissionMeaning.AD_ID, PermissionMeaning.PHONE, PermissionMeaning.APP_LIST, PermissionMeaning.LOCATION),
        ConnectionRole.ANALYTICS to setOf(PermissionMeaning.AD_ID, PermissionMeaning.PHONE, PermissionMeaning.APP_LIST, PermissionMeaning.USAGE_STATS, PermissionMeaning.LOCATION, PermissionMeaning.WIFI),
        ConnectionRole.DIAGNOSTICS to setOf(PermissionMeaning.PHONE, PermissionMeaning.STORAGE),
        ConnectionRole.MEDIA to setOf(PermissionMeaning.MEDIA, PermissionMeaning.CAMERA, PermissionMeaning.MICROPHONE, PermissionMeaning.STORAGE),
        ConnectionRole.CONTENT to setOf(PermissionMeaning.MEDIA, PermissionMeaning.STORAGE),
        ConnectionRole.UPDATE to setOf(PermissionMeaning.INSTALL_APPS, PermissionMeaning.STORAGE),
    )

    private val stopWords = setOf("com", "cn", "net", "org", "www", "api", "apis", "app", "apps", "android", "service", "services", "activity", "receiver",
        "provider", "main", "ui", "impl", "sdk", "base", "core", "common", "google", "androidx", "support", "internal", "mobile", "client", "server")

    fun host(value: String?): String? = value?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)?.takeIf { it.length in 1..253 }

    /** Approximate registrable domain (no public-suffix list): last two labels, three for common second-level country suffixes. */
    fun site(value: String?): String? {
        val labels = host(value)?.split('.')?.takeIf { it.size >= 2 && it.all(String::isNotEmpty) } ?: return null
        val n = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in setOf("com", "net", "org", "gov", "edu", "co", "ac")) 3 else 2
        return labels.takeLast(n).joinToString(".")
    }

    fun match(host: String?, category: TrackerCategory?): RoleMatch {
        val h = host(host) ?: return RoleMatch(ConnectionRole.UNKNOWN, RoleSource.NONE)
        curated.lookup(h)?.let { return RoleMatch(it, RoleSource.CURATED) }
        val cat = category?.takeUnless { it == TrackerCategory.UNKNOWN } ?: tracker.lookup(h)?.category?.takeUnless { it == TrackerCategory.UNKNOWN }
        val listed = when (cat) {
            TrackerCategory.ADS -> ConnectionRole.ADS
            TrackerCategory.ANALYTICS -> ConnectionRole.ANALYTICS
            TrackerCategory.CRASH -> ConnectionRole.DIAGNOSTICS
            TrackerCategory.PUSH -> ConnectionRole.PUSH
            TrackerCategory.CDN -> ConnectionRole.CONTENT
            TrackerCategory.OS -> ConnectionRole.SYSTEM
            TrackerCategory.CARRIER -> ConnectionRole.CARRIER
            TrackerCategory.FIRST_PARTY -> null // first-party hosts still carry telemetry; let the name decide, else FUNCTIONAL
            else -> null
        }
        if (listed != null) return RoleMatch(listed, RoleSource.TRACKER_LIST)
        nameRole(h)?.let { return RoleMatch(it, RoleSource.NAME_PATTERN) }
        if (cat == TrackerCategory.FIRST_PARTY) return RoleMatch(ConnectionRole.FUNCTIONAL, RoleSource.TRACKER_LIST)
        return RoleMatch(ConnectionRole.UNKNOWN, RoleSource.NONE)
    }

    fun role(host: String?, category: TrackerCategory?): ConnectionRole = match(host, category).role

    private fun nameRole(h: String): ConnectionRole? {
        val siteLabels = site(h)?.count { it == '.' }?.plus(1) ?: return null
        val labels = h.split('.').dropLast(siteLabels)
        for (label in labels) for (token in label.split('-')) {
            hostWords[token.trimEnd { it.isDigit() }]?.let { return it }
        }
        return null
    }

    fun permission(name: String): PermissionMeaning {
        when {
            name == "com.google.android.gms.permission.AD_ID" -> return PermissionMeaning.AD_ID
            name == "com.google.android.gms.permission.ACTIVITY_RECOGNITION" -> return PermissionMeaning.ACTIVITY_RECOGNITION
            name == "com.google.android.c2dm.permission.RECEIVE" -> return PermissionMeaning.PUSH
            name == "com.android.permission.GET_INSTALLED_APPS" -> return PermissionMeaning.APP_LIST
            name.endsWith(".permission.MIPUSH_RECEIVE") || listOf("com.huawei.android.push.permission.", "com.meizu.flyme.push.permission.",
                "com.coloros.mcs.permission.", "com.heytap.mcs.permission.").any(name::startsWith) -> return PermissionMeaning.PUSH
        }
        if (!name.startsWith("android.permission.")) return PermissionMeaning.UNKNOWN
        return when (name.removePrefix("android.permission.")) {
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "LOCATION_HARDWARE" -> PermissionMeaning.LOCATION
            "READ_CONTACTS", "WRITE_CONTACTS" -> PermissionMeaning.CONTACTS
            "GET_ACCOUNTS" -> PermissionMeaning.ACCOUNTS
            "READ_PHONE_STATE", "READ_PRIVILEGED_PHONE_STATE", "READ_PHONE_NUMBERS" -> PermissionMeaning.PHONE
            "CALL_PHONE", "ANSWER_PHONE_CALLS" -> PermissionMeaning.CALLS
            "READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS" -> PermissionMeaning.CALL_LOG
            "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH" -> PermissionMeaning.SMS
            "READ_CALENDAR", "WRITE_CALENDAR" -> PermissionMeaning.CALENDAR
            "CAMERA" -> PermissionMeaning.CAMERA
            "RECORD_AUDIO" -> PermissionMeaning.MICROPHONE
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED" -> PermissionMeaning.MEDIA
            "ACCESS_MEDIA_LOCATION" -> PermissionMeaning.MEDIA_LOCATION
            "BODY_SENSORS", "BODY_SENSORS_BACKGROUND", "HIGH_SAMPLING_RATE_SENSORS" -> PermissionMeaning.SENSORS
            "ACTIVITY_RECOGNITION" -> PermissionMeaning.ACTIVITY_RECOGNITION
            "BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE" -> PermissionMeaning.BLUETOOTH
            "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "NEARBY_WIFI_DEVICES" -> PermissionMeaning.WIFI
            "NFC" -> PermissionMeaning.NFC
            "POST_NOTIFICATIONS" -> PermissionMeaning.NOTIFICATIONS
            "BIND_NOTIFICATION_LISTENER_SERVICE" -> PermissionMeaning.NOTIFICATION_ACCESS
            "BIND_ACCESSIBILITY_SERVICE" -> PermissionMeaning.ACCESSIBILITY
            "SYSTEM_ALERT_WINDOW" -> PermissionMeaning.OVERLAY
            "QUERY_ALL_PACKAGES" -> PermissionMeaning.APP_LIST
            "PACKAGE_USAGE_STATS" -> PermissionMeaning.USAGE_STATS
            "INSTALL_PACKAGES", "REQUEST_INSTALL_PACKAGES" -> PermissionMeaning.INSTALL_APPS
            "DELETE_PACKAGES", "REQUEST_DELETE_PACKAGES" -> PermissionMeaning.REMOVE_APPS
            "REBOOT" -> PermissionMeaning.REBOOT
            "RECEIVE_BOOT_COMPLETED" -> PermissionMeaning.BOOT
            "INTERNET", "ACCESS_NETWORK_STATE", "CHANGE_NETWORK_STATE" -> PermissionMeaning.NETWORK
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE" -> PermissionMeaning.STORAGE
            else -> PermissionMeaning.UNKNOWN
        }
    }

    fun exactEndpoints(flow: Flow, facts: List<ApkFact>): List<ApkFact> = facts.filter {
        it.pkg == flow.pkg && it.kind == ApkFactKind.ENDPOINT && host(flow.host) != null && host(it.key) == host(flow.host)
    }

    fun blockable(flow: Flow): Boolean {
        val h = host(flow.host) ?: return false
        val labels = h.split('.')
        return flow.proto == Proto.TCP && attributed(flow.pkg) &&
            flow.hostSource in setOf(HostSource.SNI, HostSource.HTTP_HOST) && labels.size >= 2 && h.any { it in 'a'..'z' } &&
            labels.all { it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() && it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' } }
    }

    private fun attributed(pkg: String) = pkg != "?" && !pkg.startsWith("uid:")

    /** Stable key for labels and advice: the normalized host when known, else the single record. */
    fun subject(flow: Flow): String = host(flow.host)?.let { "h:$it" } ?: "f:${flow.id}"

    /** Groups records by [subject]; one pass, so callers can index once instead of re-filtering per row. */
    fun hostSummaries(flows: List<Flow>): Map<String, HostSummary> = flows.groupBy(::subject).mapValues { (subject, group) -> summarize(subject, group) }

    fun summary(flows: List<Flow>): HostSummary = summarize(subject(flows.first()), flows)

    private fun summarize(subject: String, group: List<Flow>): HostSummary {
        val first = group.first()
        val role = match(first.host, group.firstNotNullOfOrNull { it.category?.takeUnless { c -> c == TrackerCategory.UNKNOWN } })
        val up = group.sumOf { it.bytesUp }
        val down = group.sumOf { it.bytesDown }
        val off = group.filter { !it.screenOn }.sumOf { it.bytesUp + it.bytesDown }
        val usable = group.filter { blockable(it) && it.bytesUp + it.bytesDown > 0 }
        val reasons = ArrayList<AdviceReason>()
        if (group.maxBy { it.startAt }.policyOutcome == "BLOCKED") reasons += AdviceReason.ALREADY_BLOCKED
        val advice = when {
            group.none { attributed(it.pkg) } -> BlockAdvice.INSUFFICIENT.also { reasons += AdviceReason.UNATTRIBUTED }
            group.none(::blockable) -> BlockAdvice.INSUFFICIENT.also { reasons += AdviceReason.NO_HOST_EVIDENCE }
            usable.isEmpty() -> BlockAdvice.INSUFFICIENT.also { reasons += AdviceReason.NO_BYTES }
            role.role in setOf(ConnectionRole.ADS, ConnectionRole.ANALYTICS, ConnectionRole.DIAGNOSTICS) -> BlockAdvice.TRY_TEMPORARY.also {
                reasons += AdviceReason.TRACKING_ROLE
                if (role.source == RoleSource.NAME_PATTERN) reasons += AdviceReason.NAME_ONLY
            }
            role.role in setOf(ConnectionRole.SYSTEM, ConnectionRole.CARRIER) -> BlockAdvice.INSUFFICIENT.also { reasons += AdviceReason.PLATFORM_ROLE }
            role.role == ConnectionRole.UNKNOWN -> BlockAdvice.INSUFFICIENT.also { reasons += AdviceReason.UNKNOWN_ROLE }
            else -> BlockAdvice.MAY_AFFECT_FUNCTION.also {
                reasons += AdviceReason.FEATURE_ROLE
                if (role.source == RoleSource.NAME_PATTERN) reasons += AdviceReason.NAME_ONLY
            }
        }
        val total = up + down
        if (total > 0 && off * 2 >= total) reasons += AdviceReason.SCREEN_OFF
        if (up >= 1024 && up >= down) reasons += AdviceReason.UPLOAD_HEAVY
        return HostSummary(subject, host(first.host), role, advice, reasons, group.size, up, down, off, usable.isNotEmpty())
    }

    fun advice(flow: Flow): BlockAdvice = summary(listOf(flow)).advice

    // ---- Context ranking for AI requests: deterministic, bounded, and only ordering. Links are still the model's hypothesis. ----

    fun tokens(text: String): Set<String> = text.split(Regex("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+"))
        .map { it.lowercase(Locale.ROOT).trimEnd { c -> c.isDigit() } }.filter { it.length >= 2 && it !in stopWords }.toSet()

    /** Words of the non-registrable host labels, minus the app's own package words (they would match every component). */
    fun hostTokens(host: String?, pkg: String): Set<String> {
        val h = host(host) ?: return emptySet()
        val siteLabels = site(h)?.count { it == '.' }?.plus(1) ?: 0
        return h.split('.').dropLast(siteLabels).flatMap { tokens(it) }.toSet() - tokens(pkg)
    }

    /** Roles a target suggests: its host role, the roles related to a permission, or the roles a component name hints at. */
    fun targetRoles(fact: ApkFact?, hostRole: ConnectionRole?): Set<ConnectionRole> = buildSet {
        hostRole?.takeIf { it != ConnectionRole.UNKNOWN }?.let(::add)
        when (fact?.kind) {
            ApkFactKind.PERMISSION -> permission(fact.key).let { m -> roleMeanings.filterValues { m in it }.keys.forEach(::add) }
            ApkFactKind.ACTIVITY, ApkFactKind.SERVICE, ApkFactKind.RECEIVER, ApkFactKind.PROVIDER, ApkFactKind.SDK ->
                tokens(fact.key).let { t -> roleWords.filterValues { words -> words.any { it in t } }.keys.forEach(::add) }
            else -> Unit
        }
    }

    fun rankPermissions(facts: List<ApkFact>, roles: Set<ConnectionRole>, limit: Int): List<ApkFact> {
        val wanted = roles.flatMap { roleMeanings[it].orEmpty() }.toSet()
        return facts.filter { it.kind == ApkFactKind.PERMISSION }.map { it to permission(it.key) }
            .sortedWith(compareBy({ (_, m) -> if (m in wanted) 0 else if (m == PermissionMeaning.UNKNOWN || m == PermissionMeaning.NETWORK) 2 else 1 }, { it.first.key }))
            .take(limit).map { it.first }
    }

    private fun score(fact: ApkFact, roles: Set<ConnectionRole>, hostTokens: Set<String>, company: String?): Int {
        val t = tokens(fact.key + " " + fact.value.orEmpty())
        val roleHits = roles.sumOf { r -> roleWords[r].orEmpty().count { it in t } }
        val companyHit = company?.let { c -> tokens(c).any { it in t } } == true
        return roleHits * 2 + hostTokens.count { it in t } + if (companyHit) 3 else 0
    }

    /** Components with no word in common with the target are left out: listing them only invites unsupported links. */
    fun rankComponents(facts: List<ApkFact>, roles: Set<ConnectionRole>, hostTokens: Set<String>, limit: Int): List<ApkFact> =
        facts.filter { it.kind in setOf(ApkFactKind.ACTIVITY, ApkFactKind.SERVICE, ApkFactKind.RECEIVER, ApkFactKind.PROVIDER) }
            .map { it to score(it, roles, hostTokens, null) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ApkFact, Int>> { it.second }.thenBy { it.first.key }).take(limit).map { it.first }

    /** SDK presence is useful context even without a word match, so matching SDKs come first and others fill the slots. */
    fun rankSdks(facts: List<ApkFact>, roles: Set<ConnectionRole>, hostTokens: Set<String>, host: String?, limit: Int): List<ApkFact> {
        val company = tracker.lookup(host(host))?.company
        return facts.filter { it.kind == ApkFactKind.SDK }.map { it to score(it, roles, hostTokens, company) }
            .sortedWith(compareByDescending<Pair<ApkFact, Int>> { it.second }.thenBy { it.first.key }).take(limit).map { it.first }
    }

    /** Same-site APK endpoints, weaker than an exact host match; never used as a block target. */
    fun siteEndpoints(facts: List<ApkFact>, host: String?, limit: Int): List<ApkFact> {
        val h = host(host) ?: return emptyList()
        val s = site(h) ?: return emptyList()
        return facts.filter { it.kind == ApkFactKind.ENDPOINT && host(it.key) != h && site(it.key) == s }.sortedBy { it.key }.take(limit)
    }

    /** AppOps buckets overlapping the target's record times first (co-occurrence, not causation), then the most recent. */
    fun rankOps(ops: List<PermEvent>, targets: List<Flow>, limit: Int): List<RankedOp> {
        val spans = targets.map { it.startAt to (it.endAt ?: it.startAt) }
        fun overlaps(e: PermEvent): Boolean {
            val end = maxOf(e.bucketEnd, e.ts + (e.durationMs ?: 0))
            return spans.any { (s, t) -> e.ts <= t && end >= s }
        }
        return ops.map { RankedOp(it, overlaps(it)) }.sortedWith(compareByDescending<RankedOp> { it.overlapsTarget }.thenByDescending { it.event.ts }).take(limit)
    }

    /** Deterministic round-robin by kind: an endpoint-heavy APK cannot crowd permissions/SDKs/components out of an export. */
    fun exportFacts(facts: List<ApkFact>, limit: Int): List<ApkFact> {
        val groups = facts.groupBy { it.kind }.values.map { it.iterator() }
        val out = ArrayList<ApkFact>()
        while (out.size < limit) {
            var added = false
            for (group in groups) if (group.hasNext() && out.size < limit) { out += group.next(); added = true }
            if (!added) break
        }
        return out
    }
}
