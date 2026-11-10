package com.nathanhanapps.wayd.ai

import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Answers through the user's OpenAI-compatible server. Only reachable once the user chose it and acknowledged the risk. */
@Singleton
class ExternalAiEngine @Inject constructor(
    private val settings: ExternalAiSettings,
    private val client: OpenAiCompatibleClient,
) : AiEngine {
    private val _phase = MutableStateFlow(EnginePhase.IDLE)
    override val phase: StateFlow<EnginePhase> = _phase.asStateFlow()

    override suspend fun isReady() = settings.usableEndpoint() != null

    override suspend fun modelId() = "ext:" + settings.state.value.model

    override suspend fun externalDestination(): String? = settings.state.value.host.ifEmpty { "?" }

    override suspend fun generate(system: String, user: String, jsonSchema: String?): String {
        val endpoint = settings.usableEndpoint() ?: throw ExternalAiException("ext_not_configured")
        _phase.value = EnginePhase.GENERATING
        try {
            return client.chat(endpoint, system, user, jsonSchema)
        } finally {
            _phase.value = EnginePhase.IDLE
        }
    }
}

/** Routes each request to the engine of the provider the user selected in Settings → AI. */
@Singleton
class ProviderEngine @Inject constructor(
    private val local: LiteRtEngine,
    private val external: ExternalAiEngine,
    private val settings: ExternalAiSettings,
) : AiEngine {
    private val _phase = MutableStateFlow(EnginePhase.IDLE)
    override val phase: StateFlow<EnginePhase> = _phase.asStateFlow()

    private fun current(): AiEngine = if (settings.state.value.provider == AiProvider.EXTERNAL) external else local

    override suspend fun isReady() = current().isReady()

    override suspend fun modelId() = current().modelId()

    override suspend fun externalDestination() = current().externalDestination()

    override suspend fun generate(system: String, user: String, jsonSchema: String?): String {
        val engine = current()
        // Mirror the chosen engine's phase for the UI while it works.
        return coroutineScope {
            val mirror = launch { engine.phase.collect { _phase.value = it } }
            try {
                engine.generate(system, user, jsonSchema)
            } finally {
                mirror.cancel()
                _phase.value = EnginePhase.IDLE
            }
        }
    }
}

