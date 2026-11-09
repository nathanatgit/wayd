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
}

@Dao
interface DnsDao {
    @Insert
    suspend fun insertAll(records: List<DnsRecord>)
}
