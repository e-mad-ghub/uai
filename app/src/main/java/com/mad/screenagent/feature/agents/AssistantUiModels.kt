package com.mad.screenagent.feature.agents

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.MONEY_SAVER_MODEL
import com.mad.screenagent.data.model.OPENROUTER_FREE_ROUTER_MODEL
import com.mad.screenagent.data.model.OpenRouterCatalogEntry
import com.mad.screenagent.data.model.SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
import com.mad.screenagent.data.model.canHandleImageRequests
import com.mad.screenagent.data.model.hasInternetAccess
import com.mad.screenagent.data.model.isOpenRouterFreeModel
import com.mad.screenagent.data.model.preferredOpenRouterReasoningFreeModel

fun resolvedMoneySaverModelId(provider: AiProviderType, catalog: List<String>): String = when (provider) {
    AiProviderType.ANTHROPIC -> catalog.minByOrNull { id ->
        val n = id.lowercase()
        when { n.contains("haiku") -> 0; n.contains("sonnet") -> 1; n.contains("opus") -> 2; else -> 3 }
    } ?: "claude-haiku-4-5-20251001"
    AiProviderType.OPENAI -> catalog.minByOrNull { id ->
        val n = id.lowercase()
        when { n.contains("nano") -> 0; n.contains("mini") -> 1; n.contains("4o") -> 2; n.contains("4.1") -> 3; n.contains("gpt-5") -> 4; else -> 5 }
    } ?: "gpt-4o-mini"
    else -> ""
}

private fun pickScoredProviderModel(
    fetchedModels: List<String>,
    fallback: String,
    score: (String) -> Int
): String {
    if (fetchedModels.isEmpty()) return fallback
    return fetchedModels.maxByOrNull(score) ?: fallback
}

private fun openAiFamilyScore(modelId: String): Int {
    val normalized = modelId.lowercase()
    return when {
        normalized.startsWith("gpt-5") -> 120
        normalized.startsWith("gpt-4.5") -> 115
        normalized.startsWith("gpt-4.1") -> 110
        normalized.startsWith("gpt-4o") -> 105
        normalized.startsWith("chatgpt-4o") -> 100
        normalized.startsWith("gpt-4-turbo") -> 96
        normalized.startsWith("gpt-4") -> 90
        normalized.startsWith("gpt-3.5") -> 50
        normalized.startsWith("chatgpt-") -> 45
        else -> 30
    }
}

private fun preferredOpenAiBalancedModel(fetchedModels: List<String>): String =
    pickScoredProviderModel(fetchedModels, fallback = "gpt-5") { modelId ->
        val normalized = modelId.lowercase()
        openAiFamilyScore(modelId) * 10 +
            (if (normalized.contains("4o")) 140 else 0) +
            (if (normalized.contains("4.1")) -20 else 0) +
            (if (normalized.contains("mini")) -25 else 0) +
            (if (normalized.contains("nano")) -40 else 0) +
            (if (normalized.contains("preview")) -10 else 0)
    }

private fun preferredOpenAiFastModel(fetchedModels: List<String>): String =
    pickScoredProviderModel(fetchedModels, fallback = "gpt-5-mini") { modelId ->
        val normalized = modelId.lowercase()
        openAiFamilyScore(modelId) * 4 +
            (if (normalized.contains("mini")) 220 else 0) +
            (if (normalized.contains("nano")) 180 else 0) +
            (if (normalized.contains("flash")) 120 else 0)
    }

private fun preferredOpenAiDetailedModel(fetchedModels: List<String>): String =
    pickScoredProviderModel(fetchedModels, fallback = "gpt-4.1") { modelId ->
        val normalized = modelId.lowercase()
        openAiFamilyScore(modelId) * 10 +
            (if (normalized.contains("mini")) -40 else 0) +
            (if (normalized.contains("nano")) -60 else 0) +
            (if (normalized.contains("preview")) -10 else 0) +
            (if (normalized.contains("turbo")) 8 else 0)
    }

private fun preferredAnthropicBalancedModel(fetchedModels: List<String>): String =
    pickScoredProviderModel(fetchedModels, fallback = "claude-sonnet-4-6") { modelId ->
        val normalized = modelId.lowercase()
        when {
            normalized.contains("sonnet") -> 220
            normalized.contains("opus") -> 180
            normalized.contains("haiku") -> 120
            else -> 100
        }
    }

private fun preferredAnthropicFastModel(fetchedModels: List<String>): String =
    pickScoredProviderModel(fetchedModels, fallback = "claude-haiku-4-5") { modelId ->
        val normalized = modelId.lowercase()
        when {
            normalized.contains("haiku") -> 240
            normalized.contains("sonnet") -> 160
            normalized.contains("opus") -> 110
            else -> 90
        }
    }

private fun preferredAnthropicBestModel(fetchedModels: List<String>): String =
    pickScoredProviderModel(fetchedModels, fallback = "claude-opus-4-6") { modelId ->
        val normalized = modelId.lowercase()
        when {
            normalized.contains("opus") -> 250
            normalized.contains("sonnet") -> 180
            normalized.contains("haiku") -> 120
            else -> 100
        }
    }

private fun distinctRecommendedChoices(
    choices: List<RecommendedModelChoice>
): List<RecommendedModelChoice> = choices.distinctBy { it.id }

data class ProviderUiInfo(
    val provider: AiProviderType,
    val label: String,
    val description: String,
    val apiKeyHint: String,
    val apiKeyPlaceholder: String,
    val apiKeyCalloutTitle: String,
    val apiKeyCalloutBody: String,
    val apiKeyActionLabel: String? = null,
    val apiKeyActionUrl: String? = null
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

fun providerUiInfo(provider: AiProviderType): ProviderUiInfo = when (provider) {
    AiProviderType.OPENAI -> ProviderUiInfo(
        provider = provider,
        label = "OpenAI",
        description = "Best all-round setup for fast onboarding, strong chat quality, and vision support.",
        apiKeyHint = "Paste an API key from your OpenAI account to power this assistant.",
        apiKeyPlaceholder = "sk-...",
        apiKeyCalloutTitle = "API key required",
        apiKeyCalloutBody = "If you do not have an OpenAI API key yet, create one in your OpenAI account before testing availability or using this assistant.",
        apiKeyActionLabel = "Get OpenAI API key",
        apiKeyActionUrl = "https://platform.openai.com/api-keys"
    )
    AiProviderType.ANTHROPIC -> ProviderUiInfo(
        provider = provider,
        label = "Anthropic",
        description = "Great for careful writing, long-form reasoning, and document-heavy tasks.",
        apiKeyHint = "Paste an Anthropic API key. Claude models are especially strong for documents.",
        apiKeyPlaceholder = "sk-ant-...",
        apiKeyCalloutTitle = "API key required",
        apiKeyCalloutBody = "If you do not have an Anthropic API key yet, create one in your Anthropic console before testing availability or using this assistant.",
        apiKeyActionLabel = "Get Anthropic API key",
        apiKeyActionUrl = "https://console.anthropic.com/settings/keys"
    )
    AiProviderType.OPENROUTER -> ProviderUiInfo(
        provider = provider,
        label = "OpenRouter",
        description = "Best first-time setup when you want free options, flexible routing, and one key for many models.",
        apiKeyHint = "Paste an OpenRouter API key to unlock the free starter path and the wider OpenRouter catalog.",
        apiKeyPlaceholder = "sk-or-...",
        apiKeyCalloutTitle = "No API key yet? Start free.",
        apiKeyCalloutBody = "Create a free OpenRouter API key and start with OpenRouter's zero-cost free models. You do not need paid settings just to get started.",
        apiKeyActionLabel = "Get free API key",
        apiKeyActionUrl = "https://openrouter.ai/keys"
    )
    AiProviderType.CUSTOM -> ProviderUiInfo(
        provider = provider,
        label = "Custom",
        description = "For Groq, Grok, NVIDIA, and other compatible providers.",
        apiKeyHint = "Paste the API key for Groq, Grok, NVIDIA, or another compatible provider.",
        apiKeyPlaceholder = "Paste API key",
        apiKeyCalloutTitle = "Custom provider setup",
        apiKeyCalloutBody = "Choose a preset or enter a manual base URL for Groq, Grok, NVIDIA, or another compatible provider."
    )
    AiProviderType.ON_DEVICE -> ProviderUiInfo(
        provider = provider,
        label = "On-Device",
        description = "Run GGUF models directly on the device with no API key.",
        apiKeyHint = "No API key needed. Download or import a GGUF model and use it on the device.",
        apiKeyPlaceholder = "Not required",
        apiKeyCalloutTitle = "On-device models",
        apiKeyCalloutBody = "ScreenAgent runs GGUF models locally. Download a curated model or import your own GGUF file, then pick a ready model from the installed list."
    )
}

fun assistantProviderOrder(): List<AiProviderType> = listOf(
    AiProviderType.ON_DEVICE,
    AiProviderType.OPENROUTER,
    AiProviderType.ANTHROPIC,
    AiProviderType.OPENAI,
    AiProviderType.CUSTOM
)

fun recommendedModelChoices(
    provider: AiProviderType,
    openRouterCatalogEntries: List<OpenRouterCatalogEntry> = emptyList(),
    fetchedProviderModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet(),
    currentModel: String? = null
): List<RecommendedModelChoice> {
    val baseChoices = when (provider) {
        AiProviderType.ON_DEVICE -> listOf(
            RecommendedModelChoice(
                id = "gemma-3-1b-it-gguf",
                label = "Gemma 3 1B IT",
                description = "Public GGUF starter model for local chat on phones.",
                supportsDocuments = true
            ),
            RecommendedModelChoice(
                id = "gemma-3-4b-it-gguf",
                label = "Gemma 3 4B IT",
                description = "Higher-quality GGUF model for stronger devices.",
                supportsDocuments = true
            )
        )
        AiProviderType.OPENAI -> {
            val balancedModel = preferredOpenAiBalancedModel(fetchedProviderModels)
            val fastModel = preferredOpenAiFastModel(fetchedProviderModels)
            val detailedModel = preferredOpenAiDetailedModel(fetchedProviderModels)
            val balancedVision = AgentConfig(provider = provider, model = balancedModel).canHandleImageRequests()
            val fastVision = AgentConfig(provider = provider, model = fastModel).canHandleImageRequests()
            val detailedVision = AgentConfig(provider = provider, model = detailedModel).canHandleImageRequests()
            listOf(
                RecommendedModelChoice(
                    id = MONEY_SAVER_MODEL,
                    label = "Money Saver",
                    description = "Automatically picks the least costly available OpenAI model.",
                    supportsVision = true,
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = balancedModel,
                    label = "Balanced",
                    description = if (balancedVision) {
                        "Best default for general chat and image-aware tasks."
                    } else {
                        "Best default for general chat."
                    },
                    supportsVision = balancedVision,
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = fastModel,
                    label = "Fast",
                    description = if (fastVision) {
                        "Lower cost and quick replies for everyday questions and images."
                    } else {
                        "Lower cost and quick replies for everyday questions."
                    },
                    supportsVision = fastVision,
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = detailedModel,
                    label = "Detailed",
                    description = if (detailedVision) {
                        "Useful when you want more deliberate answers and vision support."
                    } else {
                        "Useful when you want more deliberate answers."
                    },
                    supportsVision = detailedVision,
                    supportsDocuments = true
                )
            )
        }
        AiProviderType.ANTHROPIC -> {
            val balancedModel = preferredAnthropicBalancedModel(fetchedProviderModels)
            val fastModel = preferredAnthropicFastModel(fetchedProviderModels)
            val bestModel = preferredAnthropicBestModel(fetchedProviderModels)
            listOf(
                RecommendedModelChoice(
                    id = MONEY_SAVER_MODEL,
                    label = "Money Saver",
                    description = "Automatically picks the least costly available Anthropic model.",
                    supportsVision = true,
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = balancedModel,
                    label = "Balanced",
                    description = "Best default for writing, coding, and structured reasoning.",
                    supportsVision = AgentConfig(
                        provider = provider,
                        model = balancedModel
                    ).canHandleImageRequests(),
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = fastModel,
                    label = "Fast",
                    description = "Quick, lightweight replies with strong everyday performance.",
                    supportsVision = AgentConfig(
                        provider = provider,
                        model = fastModel
                    ).canHandleImageRequests(),
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = bestModel,
                    label = "Best quality",
                    description = "Use when answer quality matters more than speed or cost.",
                    supportsVision = AgentConfig(
                        provider = provider,
                        model = bestModel
                    ).canHandleImageRequests(),
                    supportsDocuments = true
                )
            )
        }
        AiProviderType.OPENROUTER -> {
            val reasoningFreeModel = preferredOpenRouterReasoningFreeModel(
                catalogEntries = openRouterCatalogEntries,
                fetchedOpenRouterModels = fetchedProviderModels,
                freeModelIds = freeModelIds
            )
            val knownChoices = listOf(
                RecommendedModelChoice(
                    id = SIDEAGENT_OPENROUTER_BEST_FREE_MODEL,
                    label = "ScreenAgent Free",
                    description = "Best available free model per request, with built-in internet access, adaptive vision, and file routing.",
                    isRecommended = true,
                    isFree = true,
                    supportsVision = true,
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = OPENROUTER_FREE_ROUTER_MODEL,
                    label = "OpenRouter router",
                    description = "Lets OpenRouter choose a compatible free model from its own free router.",
                    isFree = true,
                    supportsVision = true,
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = reasoningFreeModel,
                    label = "Reasoning free",
                    description = "Best for step-by-step thinking when you want a free model.",
                    isFree = true,
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = "openai/gpt-4o",
                    label = "Balanced",
                    description = "Reliable all-round choice with image support.",
                    supportsVision = true,
                    supportsDocuments = true
                ),
                RecommendedModelChoice(
                    id = "anthropic/claude-3.5-sonnet",
                    label = "Files",
                    description = "Strong option for file-heavy work through OpenRouter.",
                    supportsVision = true,
                    supportsDocuments = true
                )
            )
            if (fetchedProviderModels.isEmpty()) {
                knownChoices
            } else {
                val available = fetchedProviderModels.toSet()
                knownChoices.filter {
                    it.id in available ||
                        it.id == SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
                }.ifEmpty {
                    knownChoices
                }
            }
        }
        AiProviderType.CUSTOM -> {
            val detectedChoices = fetchedProviderModels.take(3).mapIndexed { index, modelId ->
                RecommendedModelChoice(
                    id = modelId,
                    label = if (index == 0) "Detected from endpoint" else modelId,
                    description = "Available from this custom endpoint.",
                    supportsVision = AgentConfig(provider = provider, model = modelId).canHandleImageRequests(),
                    supportsDocuments = true
                )
            }
            if (detectedChoices.isNotEmpty()) detectedChoices else emptyList()
        }
    }
    val curatedChoices = distinctRecommendedChoices(baseChoices)

    val customModel = currentModel
        ?.takeIf { selected -> curatedChoices.none { it.id == selected } }
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

    return listOfNotNull(customModel) + curatedChoices
}

fun defaultRecommendedModelId(
    provider: AiProviderType,
    openRouterCatalogEntries: List<OpenRouterCatalogEntry> = emptyList(),
    fetchedProviderModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): String {
    return recommendedModelChoices(
        provider = provider,
        openRouterCatalogEntries = openRouterCatalogEntries,
        fetchedProviderModels = fetchedProviderModels,
        freeModelIds = freeModelIds
    ).firstOrNull()?.id ?: AgentConfig.defaultModels[provider]?.firstOrNull().orEmpty()
}

fun assistantSummary(agent: AgentConfig): String = when {
    agent.canHandleImageRequests() && agent.supportsDocuments -> "Ready for chat, images, and files."
    agent.supportsDocuments -> "Strong for chat and file-based tasks."
    agent.canHandleImageRequests() -> "Great for chat, screenshots, and photos."
    else -> "A good everyday assistant for text conversations."
}

fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

fun assistantCapabilities(agent: AgentConfig): List<String> = buildList {
    if (agent.canHandleImageRequests()) add("Images")
    if (agent.supportsDocuments) add("Documents")
    if (agent.provider == AiProviderType.OPENROUTER && isOpenRouterFreeModel(agent.model)) add("Free")
    if (agent.provider == AiProviderType.OPENROUTER && agent.model == SIDEAGENT_OPENROUTER_BEST_FREE_MODEL) add("Adaptive")
    if (agent.provider == AiProviderType.OPENROUTER && agent.model == OPENROUTER_FREE_ROUTER_MODEL) add("Auto route")
    if (agent.hasInternetAccess || agent.nativeWebSearchEnabled) add("Internet")
}
