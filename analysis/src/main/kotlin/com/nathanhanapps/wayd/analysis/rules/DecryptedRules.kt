package com.nathanhanapps.wayd.analysis.rules

import com.nathanhanapps.wayd.analysis.FindingText
import com.nathanhanapps.wayd.analysis.Rule
import com.nathanhanapps.wayd.analysis.RuleContext
import com.nathanhanapps.wayd.analysis.UNKNOWN_PKG
import com.nathanhanapps.wayd.analysis.finding
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Message
import com.nathanhanapps.wayd.core.model.DataType
import com.nathanhanapps.wayd.core.model.Dir
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.Tier
import com.nathanhanapps.wayd.core.model.TrackerCategory
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Finds personal identifiers in the *content* of decrypted requests (research build). Detection is by field name plus value
 * format, never by guessing at random strings, so a hit means "this request carries a field called android_id with a
 * plausible value". The values themselves are never copied into findings.
 */
object IdentifierScan {
    class Hit(val type: DataType, /** The field that carried it, e.g. `oaid`. */ val field: String)

    private val PAIR = Regex("""["']?([A-Za-z_][A-Za-z0-9_.\-]{1,32})["']?\s*[:=]\s*["']?([^"'&,\s{}\[\];]{1,200})""")
    private val HEX16 = Regex("^[0-9a-fA-F]{16}$")
    private val UUIDISH = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
    private val DIGITS_14_16 = Regex("^[0-9]{14,16}$")
    private val CN_PHONE = Regex("^(?:\\+?86)?1[3-9][0-9]{9}$")
    private val MAC = Regex("^(?:[0-9a-fA-F]{2}[:\\-]){5}[0-9a-fA-F]{2}$")
    private val COORD = Regex("^-?[0-9]{1,3}\\.[0-9]{3,}$")
    private val PACKAGE_NAME = Regex("""\b(?:com|cn|org|net|io|me|tv|app|android)\.[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+\b""")

    private val ANDROID_ID_KEYS = setOf("androidid", "ssaid")
    private val AD_ID_KEYS = setOf("gaid", "aaid", "adid", "advertisingid", "idfa", "oaid", "vaid", "adsid")
    private val IMEI_KEYS = setOf("imei", "meid", "imsi", "deviceimei", "imei1", "imei2")
    private val DEVICE_ID_KEYS = setOf("deviceid", "devid", "udid", "cuid", "deviceuuid", "did")
    private val PHONE_KEYS = setOf("phone", "mobile", "tel", "phonenumber", "mobileno", "msisdn", "cellphone")
    private val MAC_KEYS = setOf("bssid", "mac", "wifimac", "macaddress", "wifibssid")
    private val LAT_KEYS = setOf("lat", "latitude")
    private val LNG_KEYS = setOf("lng", "lon", "long", "longitude")

    /** Identifier-like fields in one request. Responses are not scanned: what a server sends back is not what the app sent out. */
    fun scan(m: Message): List<Hit> {
        if (m.dir != Dir.UP) return emptyList()
        val text = buildString {
            append(m.url.orEmpty()).append('\n')
            append(m.headersJson.orEmpty()).append('\n')
            m.body?.let { b -> utf8OrNull(b)?.let { append(it.take(MAX_SCAN)) } }
        }
        val hits = LinkedHashMap<String, Hit>()
        var lat: String? = null
        var lng: String? = null
        for (mt in PAIR.findAll(text)) {
            val key = mt.groupValues[1].lowercase().replace("_", "").replace("-", "").replace(".", "")
            val value = mt.groupValues[2]
            fun add(type: DataType) { hits.putIfAbsent(type.name, Hit(type, mt.groupValues[1])) }
            when {
                key in ANDROID_ID_KEYS && HEX16.matches(value) -> add(DataType.ANDROID_ID)
                key in AD_ID_KEYS && (UUIDISH.matches(value) || value.length >= 16) -> add(DataType.ADVERTISING_ID)
                key in IMEI_KEYS && DIGITS_14_16.matches(value) -> add(DataType.IMEI_SERIAL)
                key in DEVICE_ID_KEYS && value.length >= 8 -> add(DataType.DEVICE_ID)
                key in PHONE_KEYS && CN_PHONE.matches(value) -> add(DataType.PHONE_NUMBER)
                key in MAC_KEYS && MAC.matches(value) -> add(DataType.WIFI_BSSID)
                key in LAT_KEYS && COORD.matches(value) -> lat = value
                key in LNG_KEYS && COORD.matches(value) -> lng = value
            }
        }
        if (lat != null && lng != null) hits.putIfAbsent(DataType.LOCATION_PRECISE.name, Hit(DataType.LOCATION_PRECISE, "lat/lng"))
        val packages = PACKAGE_NAME.findAll(text).map { it.value }.toSet()
        if (packages.size >= APP_LIST_MIN) hits.putIfAbsent(DataType.APP_LIST.name, Hit(DataType.APP_LIST, "${packages.size} package names"))
        return hits.values.toList()
    }

    private const val MAX_SCAN = 64 * 1024
    const val APP_LIST_MIN = 10

    private fun utf8OrNull(b: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    }.getOrNull()
}

/**
 * R-ID-IN-REQUEST: a decrypted request carried an identifier, a phone number, a precise location or an app list. Severity
 * depends on what it is and who got it: hard identifiers, phone numbers, precise locations and app lists are more serious
 * than a device id, and anything sent to an ads/analytics company is one level above the same data sent elsewhere.
 */
class IdentifiersInRequests : Rule {
    override val id = "R-ID-IN-REQUEST"
    override val version = 1
    override val tier = Tier.DECRYPTED

    private val hard = setOf(DataType.IMEI_SERIAL, DataType.PHONE_NUMBER, DataType.LOCATION_PRECISE, DataType.APP_LIST, DataType.WIFI_BSSID)

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val byFlow = ctx.flowsById
        val out = ArrayList<Finding>()
        val perApp = ctx.messages.mapNotNull { m -> byFlow[m.flowId]?.takeIf { it.pkg != UNKNOWN_PKG }?.let { it to m } }.groupBy { it.first.pkg }
        for ((pkg, pairs) in perApp) {
            // (data type) -> messages that carried it
            val byType = LinkedHashMap<DataType, MutableList<Pair<com.nathanhanapps.wayd.core.db.Flow, Message>>>()
            val fields = HashMap<DataType, String>()
            for (p in pairs) for (h in IdentifierScan.scan(p.second)) {
                byType.getOrPut(h.type) { ArrayList() } += p
                fields.putIfAbsent(h.type, h.field)
            }
            for ((type, items) in byType) {
                val toTracker = items.any { it.first.category in WATCHED }
                val base = if (type in hard) Severity.MEDIUM else Severity.LOW
                val severity = if (toTracker) Severity.entries[minOf(base.ordinal + 1, Severity.HIGH.ordinal)] else base
                val hosts = items.mapNotNull { it.first.host }.distinct()
                out += finding(
                    ctx, this, pkg, severity,
                    FindingText.of("Sent ${type.name} (${fields[type]}) in ${items.size} request(s) to ${hosts.take(3).joinToString()}", type.name, items.size, hosts.take(3).joinToString(", "), fields[type].orEmpty()),
                    evidence = items.map { it.first.id }.distinct().take(10),
                    dataTypes = type.name,
                    msgs = items.map { it.second.id }.take(10),
                )
            }
        }
        return out
    }

    private companion object {
        val WATCHED = setOf(TrackerCategory.ADS, TrackerCategory.ANALYTICS, TrackerCategory.CRASH)
    }
}
