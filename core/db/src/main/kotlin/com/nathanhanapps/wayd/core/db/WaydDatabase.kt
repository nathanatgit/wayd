package com.nathanhanapps.wayd.core.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        Session::class, Flow::class, Message::class, DnsRecord::class, PermEvent::class,
        AlarmInfo::class, AppInfo::class, ApkFact::class, Finding::class, AiAnalysis::class,
        BlockRule::class, ConsentRecord::class, InterceptApp::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class WaydDatabase : RoomDatabase() {
    abstract fun consentDao(): ConsentDao
    abstract fun sessionDao(): SessionDao
    abstract fun flowDao(): FlowDao
    abstract fun dnsDao(): DnsDao
    abstract fun findingDao(): FindingDao
    abstract fun permEventDao(): PermEventDao
    abstract fun messageDao(): MessageDao
    abstract fun interceptAppDao(): InterceptAppDao
    abstract fun aiAnalysisDao(): AiAnalysisDao
    abstract fun apkScanDao(): ApkScanDao
    abstract fun blockRuleDao(): BlockRuleDao

    companion object {
        const val NAME = "wayd.db"
    }
}
