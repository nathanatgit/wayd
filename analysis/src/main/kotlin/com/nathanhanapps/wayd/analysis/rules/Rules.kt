package com.nathanhanapps.wayd.analysis.rules

import com.nathanhanapps.wayd.analysis.FindingText
import com.nathanhanapps.wayd.analysis.Rule
import com.nathanhanapps.wayd.analysis.RuleContext
import com.nathanhanapps.wayd.analysis.bytes
import com.nathanhanapps.wayd.analysis.finding
import com.nathanhanapps.wayd.core.db.Finding
import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.model.HostSource
import com.nathanhanapps.wayd.core.model.Proto
import com.nathanhanapps.wayd.core.model.Severity
import com.nathanhanapps.wayd.core.model.Tier
import com.nathanhanapps.wayd.core.model.TrackerCategory
import com.nathanhanapps.wayd.enrich.DohHosts
import com.nathanhanapps.wayd.enrich.PrivateAddr
import kotlin.math.sqrt

private fun Flow.hasData() = bytesUp > 0 || bytesDown > 0
private fun Flow.isPublic() = !PrivateAddr.isPrivate(dstIp)

/** R-BG-UPLOAD: sizeable upload while the screen was off. Foreground time is not yet known, so this is inference only. */
class BackgroundUpload(private val thresholdBytes: Long = 1L shl 20) : Rule {
    override val id = "R-BG-UPLOAD"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> =
        ctx.attributed.filter { !it.screenOn && it.isPublic() }.groupBy { it.pkg }.mapNotNull { (pkg, fl) ->
            val up = fl.sumOf { it.bytesUp }
            if (up < thresholdBytes) null
            else finding(
                ctx, this, pkg, Severity.MEDIUM,
                FindingText.of("Uploaded ${bytes(up)} while the screen was off", bytes(up)),
                fl.sortedByDescending { it.bytesUp }.take(10).map { it.id },
            )
        }
}

/** R-UP-RATIO: sends much more than it receives. */
class UploadRatio(private val minUp: Long = 1L shl 20, private val ratio: Double = 3.0) : Rule {
    override val id = "R-UP-RATIO"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> =
        ctx.attributed.filter { it.isPublic() }.groupBy { it.pkg }.mapNotNull { (pkg, fl) ->
            val up = fl.sumOf { it.bytesUp }
            val down = fl.sumOf { it.bytesDown }
            if (up < minUp || up < ratio * down) null
            else finding(
                ctx, this, pkg, Severity.MEDIUM,
                FindingText.of("Sent ${bytes(up)} but received only ${bytes(down)}", bytes(up), bytes(down)),
                fl.sortedByDescending { it.bytesUp }.take(10).map { it.id },
            )
        }
}

/** R-BEACON: connections to one host at suspiciously regular intervals. */
class Beacon(private val minCount: Int = 6, private val maxCv: Double = 0.2, private val minMeanMs: Long = 5_000) : Rule {
    override val id = "R-BEACON"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val out = ArrayList<Finding>()
        ctx.attributed.filter { it.host != null && it.isPublic() }.groupBy { it.pkg to it.host!! }.forEach { (key, fl) ->
            if (fl.size < minCount) return@forEach
            val sorted = fl.sortedBy { it.startAt }
            val gaps = sorted.zipWithNext { a, b -> (b.startAt - a.startAt).toDouble() }
            val mean = gaps.average()
            if (mean < minMeanMs) return@forEach
            val cv = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size) / mean
            if (cv < maxCv) {
                out += finding(
                    ctx, this, key.first, Severity.LOW,
                    FindingText.of("Contacts ${key.second} every ~${(mean / 1000).toLong()} s (${fl.size} times)", key.second, (mean / 1000).toLong(), fl.size),
                    sorted.take(10).map { it.id },
                )
            }
        }
        return out
    }
}

/** R-NEW-DOMAIN: first contact with a hostname by this app. Aggregated per app so it cannot flood the report. */
class NewDomain : Rule {
    override val id = "R-NEW-DOMAIN"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> =
        ctx.attributed.filter { it.host != null }.groupBy { it.pkg }.mapNotNull { (pkg, fl) ->
            val known = ctx.knownHostsBefore[pkg].orEmpty()
            val firstByHost = fl.sortedBy { it.startAt }.distinctBy { it.host }.filter { it.host !in known }
            if (firstByHost.isEmpty()) null
            else finding(
                ctx, this, pkg, Severity.INFO,
                FindingText.of("${firstByHost.size} new domain(s) contacted for the first time", firstByHost.size),
                firstByHost.take(20).map { it.id },
            )
        }
}

/** R-DIRECT-IP: public connections that carried data but have no hostname from DNS, SNI or HTTP Host. */
class DirectIp : Rule {
    override val id = "R-DIRECT-IP"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> =
        ctx.attributed.filter { it.proto == Proto.TCP && it.host == null && it.hasData() && it.isPublic() }
            .groupBy { it.pkg }.mapNotNull { (pkg, fl) ->
                val dsts = fl.map { it.dstIp }.distinct().size
                finding(
                    ctx, this, pkg, Severity.LOW,
                    FindingText.of("${fl.size} connection(s) to $dsts address(es) with no hostname", fl.size, dsts),
                    fl.sortedByDescending { it.bytesUp + it.bytesDown }.take(10).map { it.id },
                )
            }
}

/** R-OWN-DOH: the app talks to a public DNS-over-HTTPS resolver, so its lookups bypass the system resolver. */
class OwnDoh : Rule {
    override val id = "R-OWN-DOH"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> =
        ctx.attributed.filter { DohHosts.isDoh(it.host) }.groupBy { it.pkg }.map { (pkg, fl) ->
            finding(
                ctx, this, pkg, Severity.INFO,
                FindingText.of("Uses its own DNS-over-HTTPS (${fl.map { it.host }.distinct().joinToString()})", fl.map { it.host }.distinct().joinToString()),
                fl.take(10).map { it.id },
            )
        }
}

/** R-TRACKER-CONTACT (added in M2): the app sends data to advertising, analytics or crash-reporting domains. */
class TrackerContact : Rule {
    override val id = "R-TRACKER-CONTACT"
    override val version = 1
    override val tier = Tier.METADATA

    override fun evaluate(ctx: RuleContext): List<Finding> {
        val out = ArrayList<Finding>()
        val tracked = ctx.attributed.filter { it.bytesUp > 0 }.mapNotNull { f ->
            ctx.tracker.lookup(f.host)?.takeIf { it.category in WATCHED }?.let { Triple(f, it.company, it.category) }
        }
        tracked.groupBy { Triple(it.first.pkg, it.second, it.third) }.forEach { (k, rows) ->
            val (pkg, company, cat) = k
            val sev = if (cat == TrackerCategory.ADS) Severity.LOW else Severity.INFO
            val label = when (cat) {
                TrackerCategory.ADS -> "advertising"
                TrackerCategory.ANALYTICS -> "analytics"
                else -> "crash reporting"
            }
            out += finding(
                ctx, this, pkg, sev,
                FindingText.of("Sent ${bytes(rows.sumOf { it.first.bytesUp })} to $company ($label)", bytes(rows.sumOf { it.first.bytesUp }), company, cat.name),
                rows.map { it.first }.sortedByDescending { it.bytesUp }.take(10).map { it.id },
            )
        }
        return out
    }

    private companion object {
        val WATCHED = setOf(TrackerCategory.ADS, TrackerCategory.ANALYTICS, TrackerCategory.CRASH)
    }
}

/** R-CLEARTEXT-HTTP (added in M2): data sent over unencrypted HTTP to a public host (OS connectivity checks excluded). */
class CleartextHttp : Rule {
    override val id = "R-CLEARTEXT-HTTP"
    override val version = 1
    override val tier = Tier.PLAINTEXT

    override fun evaluate(ctx: RuleContext): List<Finding> =
        ctx.attributed.filter {
            it.proto == Proto.TCP && it.dstPort == 80 && it.bytesUp > 0 && it.isPublic() &&
                it.hostSource == HostSource.HTTP_HOST && ctx.tracker.lookup(it.host)?.category != TrackerCategory.OS
        }.groupBy { it.pkg }.map { (pkg, fl) ->
            finding(
                ctx, this, pkg, Severity.LOW,
                FindingText.of("Sent ${bytes(fl.sumOf { it.bytesUp })} over unencrypted HTTP to ${fl.mapNotNull { it.host }.distinct().take(3).joinToString()}", bytes(fl.sumOf { it.bytesUp }), fl.mapNotNull { it.host }.distinct().take(3).joinToString()),
                fl.take(10).map { it.id },
            )
        }
}
