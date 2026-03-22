package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.normalizeOpenAiCompatibleBaseUrl
import com.mad.screenagent.data.repository.OpenRouterCatalogRepository
import okhttp3.OkHttpClient

object AiProviderFactory {
    fun create(
        config: AgentConfig,
        client: OkHttpClient,
        openRouterCatalogRepository: OpenRouterCatalogRepository? = null,
        openRouterBestFreeRoutingStateStore: OpenRouterBestFreeRoutingStateStore? = null
    ): AiProvider = when (config.provider) {
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
