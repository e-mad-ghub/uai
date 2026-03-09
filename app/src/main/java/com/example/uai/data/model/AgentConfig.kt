package com.example.uai.data.model

import java.util.UUID

enum class AiProviderType(val displayName: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    OPENROUTER("OpenRouter")
}

data class AgentConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Agent",
    val provider: AiProviderType = AiProviderType.OPENAI,
    val apiKey: String = "",
    val model: String = "gpt-4o",
    val systemPrompt: String = "You are a helpful assistant.",
    val temperature: Float = 0.7f
) {
    /** True when the selected model is known to accept image input. */
    val supportsVision: Boolean get() = when (provider) {
        AiProviderType.OPENAI -> model in setOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo")
        AiProviderType.ANTHROPIC -> !model.contains("claude-2") && !model.contains("instant")
        AiProviderType.OPENROUTER -> {
            val m = model.lowercase()
            m.contains("gpt-4o") || m.contains("gpt-4-turbo") ||
            m.contains("claude-3") || m.contains("claude-sonnet") ||
            m.contains("claude-opus") || m.contains("claude-haiku") ||
            m.contains("gemini") || m.contains("vision") ||
            m.contains("llava") || m.contains("pixtral")
        }
    }

    /** True when the selected model supports PDF/document upload. */
    val supportsDocuments: Boolean get() = when (provider) {
        AiProviderType.ANTHROPIC -> true
        AiProviderType.OPENAI -> false
        AiProviderType.OPENROUTER -> {
            val m = model.lowercase()
            m.startsWith("anthropic/") || m.contains("claude-3") ||
            m.contains("claude-sonnet") || m.contains("claude-opus") || m.contains("claude-haiku")
        }
    }

    companion object {
        val defaultModels = mapOf(
            AiProviderType.OPENAI to listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo"),
            AiProviderType.ANTHROPIC to listOf(
                "claude-opus-4-6",
                "claude-sonnet-4-6",
                "claude-haiku-4-5-20251001"
            ),
            AiProviderType.OPENROUTER to listOf(
                "openai/gpt-4o",
                "anthropic/claude-3.5-sonnet",
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemini-2.0-flash-exp:free",
                "deepseek/deepseek-r1:free"
            ),
        )
    }
}
