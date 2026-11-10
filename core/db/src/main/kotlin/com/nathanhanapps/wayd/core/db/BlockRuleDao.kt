package com.nathanhanapps.wayd.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface BlockRuleDao {
    @Query("SELECT * FROM BlockRule ORDER BY id")
    suspend fun all(): List<BlockRule>

    @Insert
    suspend fun insert(rule: BlockRule): Long

    @Update
    suspend fun update(rule: BlockRule)

    @Query("DELETE FROM BlockRule WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM ConsentRecord WHERE feature = :feature")
    suspend fun consent(feature: String): ConsentRecord?
}
