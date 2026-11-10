package com.nathanhanapps.wayd.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BlockRuleDao {
    @Query("SELECT * FROM BlockRule ORDER BY id")
    suspend fun all(): List<BlockRule>

    @Insert
    suspend fun insert(rule: BlockRule): Long

    @Query("DELETE FROM BlockRule WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM ConsentRecord WHERE feature = :feature")
    suspend fun consent(feature: String): ConsentRecord?
}
