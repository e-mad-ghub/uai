package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.repository.OpenRouterCatalogRepository
import okhttp3.OkHttpClient

object AiProviderFactory {
    fun create(
        config: AgentConfig,
        client: OkHttpClient,
        openRouterCatalogRepository: OpenRouterCatalogRepository? = null
    ): AiProvider = when (config.provider) {
        AiProviderType.OPENAI -> OpenAiProvider(client)
        AiProviderType.ANTHROPIC -> AnthropicProvider(client)
        AiProviderType.OPENROUTER -> OpenRouterProvider(client, openRouterCatalogRepository)
    }
}
