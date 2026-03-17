package com.example.uai.data.model

import java.util.UUID

enum class AiProviderType(val displayName: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    OPENROUTER("OpenRouter"),
    CUSTOM("Custom")
}

data class AgentConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Agent",
    val provider: AiProviderType = AiProviderType.OPENROUTER,
    val apiKey: String = "",
    val model: String = SIDEAGENT_OPENROUTER_BEST_FREE_MODEL,
    val customPreset: CustomProviderPreset = CustomProviderPreset.MANUAL,
    val customBaseUrl: String = "",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f,
    /** Null = use model default (ScreenAgent Free → on, all others → off). */
    val agentSideInternetAccess: Boolean? = null,
    /** Anthropic / OpenAI native web search — off by default, opt-in per agent. */
    val nativeWebSearchEnabled: Boolean = false,
    /** Tool type sent to the provider. Null = use NativeWebSearchConfig default for the provider. */
    val nativeWebSearchToolType: String? = null
) {
    /** True when the selected model is known to accept image input. */
    val supportsVision: Boolean get() = when (provider) {
        AiProviderType.OPENAI -> {
            val m = model.lowercase()
            m.contains("gpt-5") ||
            m.contains("gpt-4o") ||
                m.contains("chatgpt-4o") ||
                m.contains("gpt-4-turbo") ||
                m.contains("gpt-4.1")
        }
        AiProviderType.ANTHROPIC -> !model.contains("claude-2") && !model.contains("instant")
        AiProviderType.OPENROUTER -> {
            val m = model.lowercase()
            model == OPENROUTER_FREE_ROUTER_MODEL ||
            m.contains("gpt-4o") || m.contains("gpt-4-turbo") ||
            m.contains("claude-3") || m.contains("claude-sonnet") ||
            m.contains("claude-opus") || m.contains("claude-haiku") ||
            m.contains("gemini") || m.contains("gemma-3") ||
            m.contains("vision") || m.contains("-vl") ||
            m.contains("llava") || m.contains("pixtral")
        }
        AiProviderType.CUSTOM -> looksLikeVisionCapableOpenAiCompatibleModel(model)
    }

    /** Files are normalized into text context before they reach the provider. */
    val supportsDocuments: Boolean get() = true

    companion object {
        val defaultModels = mapOf(
            AiProviderType.OPENAI to listOf(
                "gpt-5",
                "gpt-5-mini",
                "gpt-5-nano",
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4o"
            ),
            AiProviderType.ANTHROPIC to listOf(
                "claude-sonnet-4-6",
                "claude-haiku-4-5-20251001",
                "claude-opus-4-6"
            ),
            AiProviderType.OPENROUTER to listOf(
                SIDEAGENT_OPENROUTER_BEST_FREE_MODEL,
                OPENROUTER_FREE_ROUTER_MODEL,
                "meta-llama/llama-3.3-70b-instruct:free",
                "openai/gpt-oss-20b:free",
                "google/gemma-3-12b-it:free",
                "openai/gpt-4o",
                "anthropic/claude-3.5-sonnet"
            ),
            AiProviderType.CUSTOM to emptyList(),
        )
    }
}
