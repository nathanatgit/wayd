package com.nathanhanapps.wayd.analysis

import com.nathanhanapps.wayd.core.model.Proto
import org.junit.Assert.*
import org.junit.Test

class RelatedTcpTest {
    @Test fun nearbySameEndpointRecordsShareAGroupButKeepEveryRecord() {
        val first = flow(startAt = 1000)
        val second = flow(startAt = 31_000).copy(host = first.host!!.uppercase())
        val groups = RelatedTcp.groups(listOf(first, second))
        assertEquals(listOf(second.id, first.id), groups.single().map { it.id })
    }

    @Test fun differentIdentitySessionEndpointOrProtocolNeverPair() {
        val f = flow()
        val others = listOf(
            f.copy(id = f.id + 1, sessionId = 2), f.copy(id = f.id + 2, userId = 1),
            f.copy(id = f.id + 3, uid = 20_000), f.copy(id = f.id + 4, pkg = "another.app"),
            f.copy(id = f.id + 5, dstPort = 80), f.copy(id = f.id + 6, dstIp = "8.8.8.8"),
            f.copy(id = f.id + 7, host = "other.example.com"), f.copy(id = f.id + 8, proto = Proto.UDP),
        )
        assertTrue(RelatedTcp.groups(listOf(f) + others).all { it.size == 1 })
    }

    @Test fun timeWindowDoesNotChainForeverAndUdpRecordsStaySeparate() {
        val records = listOf(flow(startAt = 0), flow(startAt = 30_000), flow(startAt = 60_000), flow(proto = Proto.UDP), flow(proto = Proto.UDP))
        val groups = RelatedTcp.groups(records)
        assertEquals(listOf(1, 1, 1, 2), groups.map { it.size }.sorted())
        assertEquals(records.map { it.id }.sorted(), groups.flatten().map { it.id }.sorted())
    }
}
