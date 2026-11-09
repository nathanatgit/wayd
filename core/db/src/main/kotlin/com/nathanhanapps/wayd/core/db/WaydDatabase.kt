package com.nathanhanapps.wayd.core.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        Session::class, Flow::class, Message::class, DnsRecord::class, PermEvent::class,
        AlarmInfo::class, AppInfo::class, ApkFact::class, Finding::class, AiAnalysis::class,
        BlockRule::class, ConsentRecord::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class WaydDatabase : RoomDatabase() {
    abstract fun consentDao(): ConsentDao
    abstract fun sessionDao(): SessionDao
    abstract fun flowDao(): FlowDao
    abstract fun dnsDao(): DnsDao
    abstract fun findingDao(): FindingDao
    abstract fun permEventDao(): PermEventDao

    companion object {
        const val NAME = "wayd.db"
    }
}
