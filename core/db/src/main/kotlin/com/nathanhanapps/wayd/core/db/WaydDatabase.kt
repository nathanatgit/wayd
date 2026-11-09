package com.nathanhanapps.wayd.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        Session::class, Flow::class, Message::class, DnsRecord::class, PermEvent::class,
        AlarmInfo::class, AppInfo::class, ApkFact::class, Finding::class, AiAnalysis::class,
        BlockRule::class, ConsentRecord::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class WaydDatabase : RoomDatabase() {
    abstract fun consentDao(): ConsentDao
    abstract fun sessionDao(): SessionDao

    companion object {
        const val NAME = "wayd.db"
    }
}
