package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.db.Flow
import com.nathanhanapps.wayd.core.model.Proto
import java.util.Locale

/** Presentation-only proximity groups. These are separate records, never asserted to be the same TCP session. */
object RelatedTcp {
    private data class Endpoint(val session: Long, val user: Int, val uid: Int, val pkg: String, val ip: String, val port: Int, val host: String?, val source: String)

    fun groups(flows: List<Flow>): List<List<Flow>> {
        val result = flows.filter { it.proto != Proto.TCP }.map { listOf(it) }.toMutableList()
        flows.filter { it.proto == Proto.TCP }.groupBy {
            Endpoint(it.sessionId, it.userId, it.uid, it.pkg, it.dstIp, it.dstPort, it.host?.lowercase(Locale.ROOT), it.source.name)
        }.values.forEach { sameEndpoint ->
            var cluster = mutableListOf<Flow>()
            sameEndpoint.sortedBy { it.startAt }.forEach { flow ->
                // Bound the whole group, rather than chaining 30-second gaps into an arbitrarily long session.
                if (cluster.isNotEmpty() && flow.startAt - cluster.first().startAt > 30_000) {
                    result += cluster.sortedByDescending { it.startAt }
                    cluster = mutableListOf()
                }
                cluster += flow
            }
            if (cluster.isNotEmpty()) result += cluster.sortedByDescending { it.startAt }
        }
        return result.sortedByDescending { it.first().startAt }
    }
}
