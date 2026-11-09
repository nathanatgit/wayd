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

    @Query("SELECT * FROM Session ORDER BY id DESC LIMIT 1")
    suspend fun latest(): Session?
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insertAll(messages: List<Message>)

    @Query("SELECT * FROM Message WHERE flowId = :flowId ORDER BY ts, id")
    suspend fun forFlow(flowId: Long): List<Message>

    /** Newest decrypted messages of one app, via its flows. Bodies included; callers should limit. */
    @Query("SELECT m.* FROM Message m JOIN Flow f ON f.id = m.flowId WHERE f.pkg = :pkg ORDER BY m.ts DESC, m.id DESC LIMIT :limit")
    suspend fun recentForApp(pkg: String, limit: Int): List<Message>

    @Query("SELECT COUNT(*) FROM Message")
    suspend fun count(): Long

    @Query("SELECT m.* FROM Message m JOIN Flow f ON f.id = m.flowId WHERE f.startAt >= :start AND f.startAt < :end ORDER BY m.id")
    suspend fun between(start: Long, end: Long): List<Message>

    @Query("SELECT * FROM Message WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Message>

    @Query("SELECT m.* FROM Message m JOIN Flow f ON f.id = m.flowId WHERE f.sessionId = :sessionId ORDER BY m.id")
    suspend fun forSession(sessionId: Long): List<Message>

    @Query("SELECT m.* FROM Message m JOIN Flow f ON f.id = m.flowId WHERE f.pkg = :pkg AND f.startAt >= :start AND f.startAt < :end ORDER BY m.id")
    suspend fun forApp(pkg: String, start: Long, end: Long): List<Message>

    @Query("DELETE FROM Message")
    suspend fun deleteAll()
}

@Dao
interface InterceptAppDao {
    @Query("SELECT pkg FROM InterceptApp")
    suspend fun packages(): List<String>

    @Query("SELECT pkg FROM InterceptApp")
    fun observe(): KFlow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(app: InterceptApp)

    @Query("DELETE FROM InterceptApp WHERE pkg = :pkg")
    suspend fun remove(pkg: String)

    @Query("DELETE FROM InterceptApp")
    suspend fun clear()
}

@Dao
interface FlowDao {
    @Insert
    suspend fun insertAll(flows: List<Flow>)

    @Insert
    suspend fun insert(flow: Flow): Long

    @Query("SELECT * FROM Flow ORDER BY startAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): KFlow<List<Flow>>

    @Query("SELECT COUNT(*) FROM Flow WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    @Query("SELECT * FROM Flow WHERE startAt >= :start AND startAt < :end")
    suspend fun between(start: Long, end: Long): List<Flow>

    @Query("SELECT * FROM Flow WHERE sessionId = :sessionId ORDER BY startAt")
    suspend fun forSession(sessionId: Long): List<Flow>

    @Query("SELECT * FROM Flow WHERE pkg = :pkg AND startAt >= :start AND startAt < :end ORDER BY startAt")
    suspend fun forApp(pkg: String, start: Long, end: Long): List<Flow>

    @Query("SELECT DISTINCT pkg FROM Flow WHERE pkg != '?'")
    suspend fun distinctPackages(): List<String>

    /** Hosts each app contacted before [before]; used to detect first contacts. */
    @Query("SELECT DISTINCT pkg, host FROM Flow WHERE startAt < :before AND host IS NOT NULL")
    suspend fun hostsBefore(before: Long): List<PkgHost>

    /** One row per local calendar day that has flows, newest first. */
    @Query(
        "SELECT strftime('%Y-%m-%d', startAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS flows " +
            "FROM Flow GROUP BY day ORDER BY day DESC LIMIT :limit",
    )
    fun observeDays(limit: Int): KFlow<List<DayRow>>

    @Query("SELECT DISTINCT host FROM Flow WHERE host IS NOT NULL AND company IS NULL")
    suspend fun hostsWithoutCompany(): List<String>

    @Query("UPDATE Flow SET company = :company, category = :category WHERE host = :host AND company IS NULL")
    suspend fun setEnrichment(host: String, company: String, category: com.nathanhanapps.wayd.core.model.TrackerCategory)

    @Query("SELECT * FROM Flow WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Flow>

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

@Dao
interface PermEventDao {
    @Insert
    suspend fun insertAll(events: List<PermEvent>)

    /** AppOps-sourced events that started in the window; used to replace a window on re-sync. */
    @Query("DELETE FROM PermEvent WHERE ts >= :start AND ts < :end AND source LIKE 'appops:%'")
    suspend fun deleteAppOpsBetween(start: Long, end: Long)

    @Query("SELECT * FROM PermEvent WHERE ts >= :start AND ts < :end")
    suspend fun between(start: Long, end: Long): List<PermEvent>

    @Query("SELECT * FROM PermEvent WHERE pkg = :pkg AND ts >= :start AND ts < :end ORDER BY ts")
    suspend fun forApp(pkg: String, start: Long, end: Long): List<PermEvent>

    @Query("SELECT * FROM PermEvent WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<PermEvent>
}

data class PkgHost(val pkg: String, val host: String)

data class DayRow(val day: String, val flows: Int)

@Dao
interface FindingDao {
    @Insert
    suspend fun insertAll(findings: List<Finding>)

    /** Findings are anchored to their window start (createdAt), so a re-run replaces exactly that window. */
    @Query("DELETE FROM Finding WHERE createdAt >= :start AND createdAt < :end")
    suspend fun deleteBetween(start: Long, end: Long)

    @Query("SELECT * FROM Finding WHERE createdAt >= :start AND createdAt < :end")
    suspend fun between(start: Long, end: Long): List<Finding>

    @Query("SELECT * FROM Finding WHERE pkg = :pkg AND createdAt >= :start AND createdAt < :end")
    suspend fun forApp(pkg: String, start: Long, end: Long): List<Finding>
}

data class CoverageCounts(val total: Int, val withPkg: Int, val tcp: Int, val tcpWithHost: Int)

@Dao
interface DnsDao {
    @Insert
    suspend fun insertAll(records: List<DnsRecord>)

    @Query("SELECT * FROM DnsRecord WHERE sessionId = :sessionId ORDER BY ts")
    suspend fun forSession(sessionId: Long): List<DnsRecord>

    @Query("SELECT * FROM DnsRecord WHERE uid IN (:uids) AND ts >= :start AND ts < :end ORDER BY ts")
    suspend fun forUids(uids: List<Int>, start: Long, end: Long): List<DnsRecord>
}

@Dao
interface AiAnalysisDao {
    @Insert
    suspend fun insert(row: AiAnalysis): Long

    @Query("SELECT * FROM AiAnalysis WHERE inputRefsJson = :inputRefsJson AND validated = 1 AND promptVersion = :promptVersion ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestValidFor(inputRefsJson: String, promptVersion: Int): AiAnalysis?

    @Query("SELECT * FROM AiAnalysis ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AiAnalysis>

    @Query("DELETE FROM AiAnalysis")
    suspend fun deleteAll()
}
