package com.nathanhanapps.wayd.core.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DbModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): WaydDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(DbKeyProvider(context).passphrase())
        return Room.databaseBuilder(context, WaydDatabase::class.java, WaydDatabase.NAME)
            .openHelperFactory(factory)
            .build()
    }

    @Provides fun consentDao(db: WaydDatabase): ConsentDao = db.consentDao()

    @Provides fun sessionDao(db: WaydDatabase): SessionDao = db.sessionDao()

    @Provides
    @Singleton
    fun dbHealth(db: WaydDatabase): DbHealth = DbHealth(db)
}

/** Reports whether the opened database is really SQLCipher-encrypted (M0 acceptance check). */
class DbHealth(private val db: WaydDatabase) {
    /** SQLCipher version string, or null if the open helper is not SQLCipher. */
    fun cipherVersion(): String? = runCatching {
        db.openHelper.readableDatabase.query("PRAGMA cipher_version").use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}
