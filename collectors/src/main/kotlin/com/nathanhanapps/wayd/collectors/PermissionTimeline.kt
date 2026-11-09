package com.nathanhanapps.wayd.collectors

/** Sensitive capabilities worth showing; many raw ops map onto one category. Everything else is noise and ignored. */
enum class PermissionCategory(val ops: Set<String>) {
    LOCATION(setOf("FINE_LOCATION", "COARSE_LOCATION", "GPS", "MONITOR_LOCATION", "MONITOR_HIGH_POWER_LOCATION")),
    CAMERA(setOf("CAMERA")),
    MICROPHONE(setOf("RECORD_AUDIO", "RECORD_AUDIO_HOTWORD", "RECORD_AUDIO_OUTPUT", "RECORD_AUDIO_INPUT")),
    CLIPBOARD(setOf("READ_CLIPBOARD")),
    WIFI_SCAN(setOf("WIFI_SCAN", "NEIGHBORING_CELLS")),
    BLUETOOTH(setOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE")),
    CONTACTS_CALENDAR(setOf("READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR", "GET_ACCOUNTS")),
    SMS_CALLS(setOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE", "READ_PHONE_STATE", "READ_PHONE_NUMBERS")),
    STORAGE_MEDIA(setOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_AUDIO", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "MANAGE_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION")),
    APP_USAGE(setOf("GET_USAGE_STATS")),
    ACCESSIBILITY(setOf("BIND_ACCESSIBILITY_SERVICE")),
    SCREEN_CAPTURE(setOf("PROJECT_MEDIA", "CAPTURE_AUDIO_OUTPUT")),
    OVERLAY(setOf("SYSTEM_ALERT_WINDOW", "TOAST_WINDOW")),
    ;

    companion object {
        private val byOp: Map<String, PermissionCategory> = entries.flatMap { c -> c.ops.map { it to c } }.toMap()
        fun of(op: String): PermissionCategory? = byOp[op]
    }
}

/** How one category was used by one app inside a window. */
class PermissionUse(
    val category: PermissionCategory,
    val accesses: Int,
    val visibleAccesses: Int,
    val backgroundAccesses: Int,
    val rejects: Int,
    val durationMs: Long,
    /** Accesses per hour of the day (0..23) in local time of [zoneOffsetMs]; background part separately. */
    val hourly: IntArray,
    val hourlyBackground: IntArray,
    /** End time of the most recent bucket that had an access; bucket-resolution, not exact. */
    val lastBucketEnd: Long?,
    /** True when some counted buckets were longer than an hour, so hourly placement is approximate. */
    val coarse: Boolean,
)

object PermissionTimeline {
    /**
     * Aggregates [buckets] into per-category use for `[windowStart, windowEnd)`.
     * A bucket partly inside the window contributes proportionally, so coarse old buckets do not overcount.
     */
    fun build(buckets: List<OpBucket>, windowStart: Long, windowEnd: Long, zoneOffsetMs: Long): List<PermissionUse> {
        val perCategory = buckets.mapNotNull { b -> PermissionCategory.of(b.op)?.let { it to b } }.groupBy({ it.first }, { it.second })
        return perCategory.mapNotNull { (cat, list) ->
            var acc = 0.0
            var vis = 0.0
            var bg = 0.0
            var rej = 0.0
            var dur = 0.0
            var last: Long? = null
            var coarse = false
            val hourly = DoubleArray(24)
            val hourlyBg = DoubleArray(24)
            for (b in list) {
                val bStart = b.start
                val bEnd = maxOf(b.end, b.start + 1)
                val from = maxOf(bStart, windowStart)
                val to = minOf(bEnd, windowEnd)
                if (to <= from) continue
                val frac = (to - from).toDouble() / (bEnd - bStart)
                acc += b.access * frac
                rej += b.reject * frac
                dur += b.durationMs * frac
                if (b.state.visible) vis += b.access * frac
                if (b.state.background) bg += b.access * frac
                if (b.access > 0) last = maxOf(last ?: 0L, bEnd)
                if (bEnd - bStart > 3_600_000L) coarse = true
                // spread over the hours the overlapping part touches
                var t = from
                while (t < to) {
                    val hour = (((t + zoneOffsetMs) / 3_600_000L) % 24).toInt().let { if (it < 0) it + 24 else it }
                    val hourEnd = ((t + zoneOffsetMs) / 3_600_000L + 1) * 3_600_000L - zoneOffsetMs
                    val seg = minOf(to, hourEnd)
                    val share = b.access * frac * (seg - t).toDouble() / (to - from)
                    hourly[hour] += share
                    if (b.state.background) hourlyBg[hour] += share
                    t = seg
                }
            }
            if (acc < 0.5 && rej < 0.5) null
            else PermissionUse(
                cat, Math.round(acc).toInt(), Math.round(vis).toInt(), Math.round(bg).toInt(), Math.round(rej).toInt(), dur.toLong(),
                IntArray(24) { Math.round(hourly[it]).toInt() }, IntArray(24) { Math.round(hourlyBg[it]).toInt() }, last, coarse,
            )
        }.sortedByDescending { it.accesses }
    }
}
