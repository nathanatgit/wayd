package com.nathanhanapps.wayd.analysis.rules

import com.nathanhanapps.wayd.analysis.FindingText
import com.nathanhanapps.wayd.analysis.Rule
import com.nathanhanapps.wayd.analysis.RuleContext
import com.nathanhanapps.wayd.analysis.UNKNOWN_PKG
import com.nathanhanapps.wayd.analysis.bytes
import com.nathanhanapps.wayd.analysis.finding
import com.nathanhanapps.wayd.collectors.PermissionCategory
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.Tier
import com.nathanhanapps.wayd.enrich.PrivateAddr

private const val FIFTEEN_MIN = 15 * 60_000L

/** R-CLIPBOARD-BG: the app read the clipboard while it was in the background (or cached). */
class ClipboardBackground : Rule {
    override val id = "R-CLIPBOARD-BG"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> =
        ctx.permEvents.filter { it.op == "READ_CLIPBOARD" && it.background && it.pkg != UNKNOWN_PKG }
            .groupBy { it.pkg }.map { (pkg, ev) ->
                val n = ev.sumOf { it.count }
                finding(
                    ctx, this, pkg, Severity.MEDIUM,
                    FindingText.of("Read the clipboard $n time(s) while in the background", n),
                    emptyList(), dataTypes = "CLIPBOARD", perm = ev.sortedByDescending { it.count }.take(10).map { it.id },
                )
            }
}

/** R-SCREEN-CAPTURE: screen projection/capture used while the app was not visible. */
class ScreenCaptureBackground : Rule {
    override val id = "R-SCREEN-CAPTURE"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> =
        ctx.permEvents.filter { it.op in PermissionCategory.SCREEN_CAPTURE.ops && it.background && it.pkg != UNKNOWN_PKG }
            .groupBy { it.pkg }.map { (pkg, ev) ->
                finding(
                    ctx, this, pkg, Severity.HIGH,
                    FindingText.of("Used screen capture ${ev.sumOf { it.count }} time(s) while not visible", ev.sumOf { it.count }),
                    emptyList(), dataTypes = "SCREEN_CONTENT", perm = ev.take(10).map { it.id },
                )
            }
}

/**
 * R-LOC-THEN-UP: location was read in the background and the same app sent data within the same history bucket.
 * Bucket resolution (about 15 min) is much coarser than the 10 s in docs/05, so this reports "same window", not
 * causation, and stays METADATA-tier.
 */
class LocationThenUpload(private val minUpload: Long = 5L * 1024, private val maxBucketMs: Long = 30 * 60_000L) : Rule {
    override val id = "R-LOC-THEN-UP"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val locOps = PermissionCategory.LOCATION.ops
        val out = ArrayList<Finding>()
        ctx.permEvents.filter { it.op in locOps && it.background && it.count > 0 && it.pkg != UNKNOWN_PKG }
            .groupBy { it.pkg }.forEach { (pkg, events) ->
                val flows = ctx.flows.filter { it.pkg == pkg && it.bytesUp > 0 && !PrivateAddr.isPrivate(it.dstIp) }
                val matchedEvents = ArrayList<Long>()
                val matchedFlows = LinkedHashSet<Long>()
                var up = 0L
                for (e in events) {
                    val end = if (e.bucketEnd > e.ts) e.bucketEnd else e.ts + FIFTEEN_MIN
                    if (end - e.ts > maxBucketMs) continue
                    val hit = flows.filter { it.startAt in e.ts..(end + 10_000) }
                    if (hit.isEmpty()) continue
                    matchedEvents += e.id
                    hit.forEach { if (matchedFlows.add(it.id)) up += it.bytesUp }
                }
                if (up >= minUpload) {
                    out += finding(
                        ctx, this, pkg, Severity.MEDIUM,
                        FindingText.of("Read the location in the background and sent ${bytes(up)} in the same time window (${matchedEvents.size} time(s))", bytes(up), matchedEvents.size),
                        matchedFlows.take(8), dataTypes = "LOCATION_PRECISE", perm = matchedEvents.take(8),
                    )
                }
            }
        return out
    }
}
