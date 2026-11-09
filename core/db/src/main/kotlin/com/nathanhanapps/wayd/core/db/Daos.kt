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

    @Query("SELECT COUNT(*) FROM Session")
    suspend fun count(): Int
}
