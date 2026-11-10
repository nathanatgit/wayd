package com.nathanhanapps.wayd.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ApkScanDao {
    @Query("SELECT * FROM AppInfo ORDER BY label COLLATE NOCASE")
    abstract fun observeApps(): Flow<List<AppInfo>>

    @Query("SELECT DISTINCT pkg FROM ApkFact WHERE kind = 'COVERAGE' AND `key` = 'limit'")
    abstract fun observeLimitedPackages(): Flow<List<String>>

    @Query("SELECT * FROM AppInfo WHERE pkg = :pkg")
    abstract suspend fun app(pkg: String): AppInfo?

    @Query("SELECT * FROM ApkFact WHERE pkg = :pkg ORDER BY kind, `key`, id")
    abstract suspend fun facts(pkg: String): List<ApkFact>

    @Query("SELECT * FROM ApkFact WHERE pkg = :pkg ORDER BY kind, `key`, id")
    abstract fun observeFacts(pkg: String): Flow<List<ApkFact>>

    @Query("DELETE FROM ApkFact WHERE pkg = :pkg")
    abstract suspend fun deleteFacts(pkg: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(app: AppInfo)

    @Insert
    abstract suspend fun insert(facts: List<ApkFact>)

    @Transaction
    open suspend fun replace(app: AppInfo, facts: List<ApkFact>) {
        deleteFacts(app.pkg)
        insert(facts)
        upsert(app)
    }

    @Query("SELECT DISTINCT op FROM PermEvent WHERE pkg = :pkg ORDER BY op LIMIT 100")
    abstract suspend fun observedOps(pkg: String): List<String>

    @Query("SELECT DISTINCT host FROM Flow WHERE pkg = :pkg AND host IS NOT NULL ORDER BY host LIMIT 200")
    abstract suspend fun observedHosts(pkg: String): List<String>
}
