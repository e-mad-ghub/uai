package com.example.uai.shared.streaming

import com.example.uai.data.model.AiProviderType

/**
 * Centralised constants for provider-native web search tool types.
 * Update these when a provider releases a new tool version.
 */
object NativeWebSearchConfig {
    const val ANTHROPIC_DEFAULT_TOOL_TYPE = "web_search_20250305"
    const val ANTHROPIC_MAX_USES = 5

    const val OPENAI_DEFAULT_TOOL_TYPE = "web_search_preview"

    val anthropicPresets = listOf(ANTHROPIC_DEFAULT_TOOL_TYPE)
    val openAiPresets = listOf(OPENAI_DEFAULT_TOOL_TYPE)

    fun defaultToolTypeFor(provider: AiProviderType): String = when (provider) {
        AiProviderType.ANTHROPIC -> ANTHROPIC_DEFAULT_TOOL_TYPE
        AiProviderType.OPENAI -> OPENAI_DEFAULT_TOOL_TYPE
        else -> ""
    }

    fun presetsFor(provider: AiProviderType): List<String> = when (provider) {
        AiProviderType.ANTHROPIC -> anthropicPresets
        AiProviderType.OPENAI -> openAiPresets
        else -> emptyList()
    }
}
