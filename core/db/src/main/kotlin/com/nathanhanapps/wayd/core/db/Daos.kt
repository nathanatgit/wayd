package com.nathanhanapps.wayd.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow as KFlow

@Dao
interface ConsentDao {
    @Query("SELECT * FROM ConsentRecord")
    fun observeAll(): KFlow<List<ConsentRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ConsentRecord)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: Session): Long

    @Query("UPDATE Session SET endedAt = :endedAt WHERE id = :id")
    suspend fun end(id: Long, endedAt: Long)

    @Query("SELECT COUNT(*) FROM Session")
    suspend fun count(): Int
}

@Dao
interface FlowDao {
    @Insert
    suspend fun insertAll(flows: List<Flow>)

    @Query("SELECT * FROM Flow ORDER BY startAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): KFlow<List<Flow>>

    @Query("SELECT COUNT(*) FROM Flow WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    /** Live attribution coverage over all recorded flows (M1 acceptance metrics). */
    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN pkg != '?' THEN 1 ELSE 0 END), 0) AS withPkg, " +
            "COALESCE(SUM(CASE WHEN $MEASURABLE THEN 1 ELSE 0 END), 0) AS tcp, " +
            "COALESCE(SUM(CASE WHEN $MEASURABLE AND host IS NOT NULL THEN 1 ELSE 0 END), 0) AS tcpWithHost " +
            "FROM Flow",
    )
    fun observeCoverage(): KFlow<CoverageCounts>
}

/**
 * TCP flows that can meaningfully have a hostname: they carried data and went to a public address.
 * LAN probes and connections that never exchanged a byte are excluded from the hostname metric.
 */
private const val MEASURABLE =
    "proto = 'TCP' AND (bytesUp > 0 OR bytesDown > 0) AND dstIp NOT LIKE '10.%' AND dstIp NOT LIKE '192.168.%' " +
        "AND dstIp NOT GLOB '172.1[6-9].*' AND dstIp NOT GLOB '172.2[0-9].*' AND dstIp NOT GLOB '172.3[01].*' " +
        "AND dstIp NOT LIKE '127.%' AND dstIp NOT LIKE '169.254.%' AND dstIp NOT LIKE 'fe80:%' " +
        "AND dstIp NOT LIKE 'fd%' AND dstIp NOT LIKE 'fc%' AND dstIp != '::1'"

data class CoverageCounts(val total: Int, val withPkg: Int, val tcp: Int, val tcpWithHost: Int)

@Dao
interface DnsDao {
    @Insert
    suspend fun insertAll(records: List<DnsRecord>)
}
