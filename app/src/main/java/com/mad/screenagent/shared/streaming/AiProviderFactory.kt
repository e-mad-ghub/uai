package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.normalizeOpenAiCompatibleBaseUrl
import com.mad.screenagent.data.repository.OpenRouterCatalogRepository
import com.mad.screenagent.data.repository.OnDeviceModelSource
import okhttp3.OkHttpClient

object AiProviderFactory {
    fun create(
        config: AgentConfig,
        client: OkHttpClient,
        openRouterCatalogRepository: OpenRouterCatalogRepository? = null,
        openRouterBestFreeRoutingStateStore: OpenRouterBestFreeRoutingStateStore? = null,
        onDeviceModelRepository: OnDeviceModelSource? = null,
        onDeviceRuntime: OnDeviceRuntime? = null
    ): AiProvider = when (config.provider) {
        AiProviderType.ON_DEVICE_GEMMA3,
        AiProviderType.ON_DEVICE -> OnDeviceProvider(
            modelRepository = onDeviceModelRepository ?: error("On-Device model repository is required"),
            runtime = onDeviceRuntime ?: error("On-Device runtime is required")
        )
        AiProviderType.OPENAI -> OpenAiProvider(client)
        AiProviderType.ANTHROPIC -> AnthropicProvider(client)
        AiProviderType.OPENROUTER -> OpenRouterProvider(
            client = client,
            openRouterCatalogRepository = openRouterCatalogRepository,
            bestFreeRoutingStateStore = openRouterBestFreeRoutingStateStore
        )
        AiProviderType.CUSTOM -> OpenAiProvider(
            client = client,
            baseUrl = normalizeOpenAiCompatibleBaseUrl(config.customBaseUrl)
        )
    }
}
