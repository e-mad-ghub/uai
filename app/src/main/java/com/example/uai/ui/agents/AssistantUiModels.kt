package com.example.uai.ui.agents

import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.model.OPENROUTER_FREE_ROUTER_MODEL
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.model.isOpenRouterFreeModel
import com.example.uai.data.model.preferredOpenRouterFastFreeModel
import com.example.uai.data.model.preferredOpenRouterReasoningFreeModel
import com.example.uai.data.model.preferredOpenRouterVisionFreeModel

data class ProviderUiInfo(
    val label: String,
    val description: String,
    val apiKeyHint: String,
    val apiKeyPlaceholder: String
)

data class RecommendedModelChoice(
    val id: String,
    val label: String,
    val description: String,
    val isRecommended: Boolean = false,
    val isFree: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsDocuments: Boolean = false
)

data class AssistantPreset(
    val title: String,
    val subtitle: String,
    val suggestedName: String,
    val systemPrompt: String,
    val temperature: Float,
    val recommendedModelId: String
)

fun providerUiInfo(provider: AiProviderType): ProviderUiInfo = when (provider) {
    AiProviderType.OPENAI -> ProviderUiInfo(
        label = "OpenAI",
        description = "Best all-round setup for fast onboarding, strong chat quality, and vision support.",
        apiKeyHint = "Paste an API key from your OpenAI account to power this assistant.",
        apiKeyPlaceholder = "sk-..."
    )
    AiProviderType.ANTHROPIC -> ProviderUiInfo(
        label = "Anthropic",
        description = "Great for careful writing, long-form reasoning, and document-heavy tasks.",
        apiKeyHint = "Paste an Anthropic API key. Claude models are especially strong for documents.",
        apiKeyPlaceholder = "sk-ant-..."
    )
    AiProviderType.OPENROUTER -> ProviderUiInfo(
        label = "OpenRouter",
        description = "Best first-time setup when you want free options, flexible routing, and one key for many models.",
        apiKeyHint = "Paste an OpenRouter API key to unlock the free starter path and the wider OpenRouter catalog.",
        apiKeyPlaceholder = "sk-or-..."
    )
}

fun assistantProviderOrder(): List<AiProviderType> = listOf(
    AiProviderType.OPENROUTER,
    AiProviderType.OPENAI,
    AiProviderType.ANTHROPIC
)

fun recommendedModelChoices(
    provider: AiProviderType,
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet(),
    currentModel: String? = null
): List<RecommendedModelChoice> {
    val baseChoices = when (provider) {
        AiProviderType.OPENAI -> listOf(
            RecommendedModelChoice(
                id = "gpt-4o",
                label = "Balanced",
                description = "Best default for general chat and image-aware tasks.",
                supportsVision = true
            ),
            RecommendedModelChoice(
                id = "gpt-4o-mini",
                label = "Fast",
                description = "Lower cost and quick replies for everyday questions.",
                supportsVision = true
            ),
            RecommendedModelChoice(
                id = "gpt-4-turbo",
                label = "Detailed",
                description = "Useful when you want more deliberate answers and vision support.",
                supportsVision = true
            )
        )
        AiProviderType.ANTHROPIC -> listOf(
            RecommendedModelChoice(
                id = "claude-sonnet-4-6",
                label = "Balanced",
                description = "Best default for writing, coding, and structured reasoning.",
                supportsVision = true,
                supportsDocuments = true
            ),
            RecommendedModelChoice(
                id = "claude-haiku-4-5-20251001",
                label = "Fast",
                description = "Quick, lightweight replies with strong everyday performance.",
                supportsVision = true,
                supportsDocuments = true
            ),
            RecommendedModelChoice(
                id = "claude-opus-4-6",
                label = "Best quality",
                description = "Use when answer quality matters more than speed or cost.",
                supportsVision = true,
                supportsDocuments = true
            )
        )
        AiProviderType.OPENROUTER -> {
            val fastFreeModel = preferredOpenRouterFastFreeModel(
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            )
            val reasoningFreeModel = preferredOpenRouterReasoningFreeModel(
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            )
            val visionFreeModel = preferredOpenRouterVisionFreeModel(
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            )
            val knownChoices = listOf(
                RecommendedModelChoice(
                    id = fastFreeModel,
                    label = "Best free",
                    description = "Best zero-cost option for chat and images. SideAgent automatically uses the best available free model for the request.",
                    isRecommended = true,
                    isFree = true,
                    supportsVision = AgentConfig(provider = provider, model = fastFreeModel).canHandleImageRequests()
                ),
                RecommendedModelChoice(
                    id = reasoningFreeModel,
                    label = "Reasoning free",
                    description = "Best for step-by-step thinking when you want a free model.",
                    isFree = true
                ),
                RecommendedModelChoice(
                    id = visionFreeModel,
                    label = "Vision free",
                    description = "Prioritizes free vision models first for screenshots and photos.",
                    isFree = true,
                    supportsVision = AgentConfig(provider = provider, model = visionFreeModel).canHandleImageRequests()
                ),
                RecommendedModelChoice(
                    id = "openai/gpt-4o",
                    label = "Balanced",
                    description = "Reliable all-round choice with image support.",
                    supportsVision = true
                ),
                RecommendedModelChoice(
                    id = "anthropic/claude-3.5-sonnet",
                    label = "Documents",
                    description = "Strong option for file-heavy work through OpenRouter.",
                    supportsVision = true,
                    supportsDocuments = true
                )
            )
            if (fetchedOpenRouterModels.isEmpty()) {
                knownChoices
            } else {
                val available = fetchedOpenRouterModels.toSet()
                knownChoices.filter { it.id in available }.ifEmpty {
                    knownChoices
                }
            }
        }
    }

    val customModel = currentModel
        ?.takeIf { selected -> baseChoices.none { it.id == selected } }
        ?.let { selected ->
            val config = AgentConfig(provider = provider, model = selected)
            RecommendedModelChoice(
                id = selected,
                label = "Current custom model",
                description = "Keeps the existing model ID that was entered manually.",
                isFree = provider == AiProviderType.OPENROUTER &&
                    isOpenRouterFreeModel(selected, freeModelIds),
                supportsVision = config.canHandleImageRequests(),
                supportsDocuments = config.supportsDocuments
            )
        }

    return listOfNotNull(customModel) + baseChoices
}

fun defaultRecommendedModelId(
    provider: AiProviderType,
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): String {
    return recommendedModelChoices(
        provider = provider,
        fetchedOpenRouterModels = fetchedOpenRouterModels,
        freeModelIds = freeModelIds
    ).firstOrNull()?.id ?: AgentConfig.defaultModels[provider]?.first().orEmpty()
}

fun assistantPresets(
    provider: AiProviderType,
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): List<AssistantPreset> {
    val recommendedModels = recommendedModelChoices(
        provider = provider,
        fetchedOpenRouterModels = fetchedOpenRouterModels,
        freeModelIds = freeModelIds
    )
    val fallbackModel = recommendedModels.firstOrNull()?.id.orEmpty()
    val visionModel = recommendedModels.firstOrNull { it.supportsVision }?.id ?: fallbackModel
    val fastModel = recommendedModels
        .firstOrNull { it.label.contains("Fast", ignoreCase = true) }
        ?.id ?: fallbackModel

    return listOf(
        AssistantPreset(
            title = "General",
            subtitle = "Everyday questions and quick help",
            suggestedName = "General Assistant",
            systemPrompt = "You are a helpful assistant. Be clear, direct, and practical.",
            temperature = 0.6f,
            recommendedModelId = fallbackModel
        ),
        AssistantPreset(
            title = "Research",
            subtitle = "Structured summaries and careful comparisons",
            suggestedName = "Research Assistant",
            systemPrompt = "You are a careful research assistant. Summarize clearly, compare options fairly, and call out uncertainty when details are missing.",
            temperature = 0.4f,
            recommendedModelId = fallbackModel
        ),
        AssistantPreset(
            title = "Writing",
            subtitle = "Drafting, rewriting, and tone polishing",
            suggestedName = "Writing Assistant",
            systemPrompt = "You are a writing assistant. Improve clarity, structure, and tone while preserving the user's intent.",
            temperature = 0.8f,
            recommendedModelId = fastModel
        ),
        AssistantPreset(
            title = "Vision",
            subtitle = "Screenshots, photos, and visual questions",
            suggestedName = "Vision Assistant",
            systemPrompt = "You are a precise visual assistant. When an image is provided, describe what you see, extract useful details, and answer the user's question directly.",
            temperature = 0.3f,
            recommendedModelId = visionModel
        )
    )
}

fun assistantSummary(agent: AgentConfig): String = when {
    agent.canHandleImageRequests() && agent.supportsDocuments -> "Ready for chat, images, and documents."
    agent.supportsDocuments -> "Strong for chat and document-based tasks."
    agent.canHandleImageRequests() -> "Great for chat, screenshots, and photos."
    else -> "A good everyday assistant for text conversations."
}

fun assistantCapabilities(agent: AgentConfig): List<String> = buildList {
    add(agent.provider.displayName)
    if (agent.canHandleImageRequests()) add("Images")
    if (agent.supportsDocuments) add("Documents")
    if (agent.provider == AiProviderType.OPENROUTER && isOpenRouterFreeModel(agent.model)) add("Free")
    if (agent.provider == AiProviderType.OPENROUTER && agent.model == OPENROUTER_FREE_ROUTER_MODEL) add("Auto route")
}
