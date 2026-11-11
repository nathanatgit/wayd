package com.nathanhanapps.wayd.ai

import com.nathanhanapps.wayd.core.db.AiAnalysis
import com.nathanhanapps.wayd.core.db.AiAnalysisDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Stores analyses in the encrypted database. */
class RoomAnalysisStore(private val dao: AiAnalysisDao) : AnalysisStore {
    override suspend fun cached(refsKey: String, promptVersion: Int): String? = dao.latestValidFor(refsKey, promptVersion)?.resultJson

    override suspend fun save(refsKey: String, model: String, promptVersion: Int, resultJson: String, validated: Boolean, durationMs: Long) {
        dao.insert(
            AiAnalysis(
                inputRefsJson = refsKey,
                model = model,
                promptVersion = promptVersion,
                resultJson = resultJson,
                validated = validated,
                createdAt = System.currentTimeMillis(),
                durationMs = durationMs,
            ),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides
    @Singleton
    fun engine(impl: ProviderEngine): AiEngine = impl

    @Provides
    @Singleton
    fun externalClient(connector: ExternalConnector): OpenAiCompatibleClient {
        val verifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
        return OpenAiCompatibleClient(connector = connector, secure = { s, host, port ->
            EngineTls(host, port, s.input, s.output, verifier = verifier).run { handshake(); input to output }
        })
    }

    @Provides
    @Singleton
    fun analyzer(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context, engine: AiEngine, dao: AiAnalysisDao): AiAnalyzer {
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        return AiAnalyzer(engine, RoomAnalysisStore(dao), debugLog = if (debuggable) { m -> android.util.Log.i("WAYD-AI", m) } else null)
    }
}
