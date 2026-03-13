package com.example.uai.data.model

const val OPENROUTER_FREE_ROUTER_MODEL = "openrouter/free"

data class OpenRouterCatalogEntry(
    val id: String,
    val name: String = id,
    val description: String = "",
    val contextLength: Long = 0L,
    val promptPrice: Double? = null,
    val completionPrice: Double? = null,
    val supportsVision: Boolean = false
) {
    val isFree: Boolean
        get() = id == OPENROUTER_FREE_ROUTER_MODEL ||
            id.endsWith(":free") ||
            ((promptPrice ?: 1.0) == 0.0 && (completionPrice ?: 1.0) == 0.0)
}

data class OpenRouterCatalog(
    val fetchedAt: Long = 0L,
    val models: List<OpenRouterCatalogEntry> = emptyList()
)

private val preferredOpenRouterGeneralFreeModels = listOf(
    "openai/gpt-oss-120b:free",
    "meta-llama/llama-3.3-70b-instruct:free",
    "qwen/qwen3-next-80b-a3b-instruct:free",
    "nvidia/nemotron-3-super-120b-a12b:free",
    "openai/gpt-oss-20b:free",
    "google/gemma-3-27b-it:free",
    "google/gemma-3-12b-it:free",
    "nvidia/nemotron-nano-9b-v2:free",
    "stepfun/step-3.5-flash:free",
    OPENROUTER_FREE_ROUTER_MODEL
)

private val preferredOpenRouterReasoningFreeModels = listOf(
    "openai/gpt-oss-120b:free",
    "qwen/qwen3-next-80b-a3b-instruct:free",
    "meta-llama/llama-3.3-70b-instruct:free",
    "qwen/qwen3-coder:free",
    "openai/gpt-oss-20b:free",
    OPENROUTER_FREE_ROUTER_MODEL
)

private val preferredOpenRouterVisionFreeModels = listOf(
    "google/gemma-3-27b-it:free",
    "google/gemma-3-12b-it:free",
    "nvidia/nemotron-nano-12b-v2-vl:free",
    OPENROUTER_FREE_ROUTER_MODEL
)

private enum class OpenRouterRankingUseCase {
    GENERAL,
    REASONING,
    VISION
}

private data class OpenRouterRankingProfile(
    val preferredIds: List<String>,
    val preferredIdBoost: Int,
    val preferredIdStepPenalty: Int,
    val modelSizeWeight: Int,
    val contextWeightPer4k: Int,
    val visionSupportBonus: Int = 0,
    val reasoningKeywordBonus: Int = 0,
    val multimodalKeywordBonus: Int = 0,
    val codingKeywordBonus: Int = 0,
    val flashPenalty: Int = 0,
    val nanoPenalty: Int = 0,
    val routerPenalty: Int = 0
)

private data class OpenRouterMetadataSignals(
    val preferredIndex: Int,
    val modelSizeBillions: Int,
    val contextBlocks: Int,
    val supportsVision: Boolean,
    val hasReasoningKeywords: Boolean,
    val hasMultimodalKeywords: Boolean,
    val hasCodingKeywords: Boolean,
    val isFlashVariant: Boolean,
    val isNanoVariant: Boolean,
    val isRouterFallback: Boolean
)

fun isOpenRouterFreeModel(modelId: String, freeModelIds: Set<String> = emptySet()): Boolean {
    return modelId == OPENROUTER_FREE_ROUTER_MODEL ||
        modelId.endsWith(":free") ||
        modelId in freeModelIds
}

fun preferredOpenRouterFastFreeModel(
    catalogEntries: List<OpenRouterCatalogEntry> = emptyList(),
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): String = rankedOpenRouterFreeModels(
    useCase = OpenRouterRankingUseCase.GENERAL,
    catalogEntries = catalogEntries,
    fetchedOpenRouterModels = fetchedOpenRouterModels,
    freeModelIds = freeModelIds
).firstOrNull() ?: preferredOpenRouterGeneralFreeModels.first()

fun preferredOpenRouterReasoningFreeModel(
    catalogEntries: List<OpenRouterCatalogEntry> = emptyList(),
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): String = rankedOpenRouterFreeModels(
    useCase = OpenRouterRankingUseCase.REASONING,
    catalogEntries = catalogEntries,
    fetchedOpenRouterModels = fetchedOpenRouterModels,
    freeModelIds = freeModelIds
).firstOrNull() ?: preferredOpenRouterReasoningFreeModels.first()

fun preferredOpenRouterVisionFreeModel(
    catalogEntries: List<OpenRouterCatalogEntry> = emptyList(),
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): String = rankedOpenRouterFreeModels(
    useCase = OpenRouterRankingUseCase.VISION,
    catalogEntries = catalogEntries,
    fetchedOpenRouterModels = fetchedOpenRouterModels,
    freeModelIds = freeModelIds
).firstOrNull() ?: preferredOpenRouterVisionFreeModels.first()

fun openRouterFreeFallbackModels(
    catalogEntries: List<OpenRouterCatalogEntry> = emptyList(),
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet(),
    currentModel: String? = null,
    requireVision: Boolean = false
): List<String> {
    val fetchedFreeModels = fetchedOpenRouterModels
        .filter { isOpenRouterFreeModel(it, freeModelIds) }
        .filter { !requireVision || openRouterFreeSupportsVision(it) }

    val rankedModels = if (catalogEntries.isNotEmpty()) {
        if (requireVision) {
            rankedOpenRouterFreeModels(
                useCase = OpenRouterRankingUseCase.VISION,
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            ) + rankedOpenRouterFreeModels(
                useCase = OpenRouterRankingUseCase.GENERAL,
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            ) + rankedOpenRouterFreeModels(
                useCase = OpenRouterRankingUseCase.REASONING,
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            )
        } else {
            rankedOpenRouterFreeModels(
                useCase = OpenRouterRankingUseCase.GENERAL,
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            ) + rankedOpenRouterFreeModels(
                useCase = OpenRouterRankingUseCase.REASONING,
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            ) + rankedOpenRouterFreeModels(
                useCase = OpenRouterRankingUseCase.VISION,
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedOpenRouterModels,
                freeModelIds = freeModelIds
            )
        }
    } else {
        if (requireVision) {
            preferredOpenRouterVisionFreeModels +
                preferredOpenRouterGeneralFreeModels +
                preferredOpenRouterReasoningFreeModels
        } else {
            preferredOpenRouterGeneralFreeModels +
                preferredOpenRouterReasoningFreeModels +
                preferredOpenRouterVisionFreeModels
        }
    }

    return buildList {
        if (
            currentModel != null &&
            isOpenRouterFreeModel(currentModel, freeModelIds) &&
            (!requireVision || openRouterFreeSupportsVision(currentModel))
        ) {
            add(currentModel)
        }
        addAll(rankedModels.filter { !requireVision || openRouterFreeSupportsVision(it) })
        addAll(fetchedFreeModels)
    }.distinct()
}

private fun rankedOpenRouterFreeModels(
    useCase: OpenRouterRankingUseCase,
    catalogEntries: List<OpenRouterCatalogEntry>,
    fetchedOpenRouterModels: List<String>,
    freeModelIds: Set<String>
): List<String> {
    val availableEntries = if (catalogEntries.isNotEmpty()) {
        catalogEntries
            .filter { it.isFree }
            .filter { useCase != OpenRouterRankingUseCase.VISION || it.supportsVision }
    } else {
        emptyList()
    }

    if (availableEntries.isNotEmpty()) {
        return availableEntries
            .sortedByDescending { entry ->
                scoreOpenRouterFreeModel(entry, useCase)
            }
            .map { it.id }
    }

    val preferredIds = preferredIdsFor(useCase)
    if (fetchedOpenRouterModels.isEmpty()) return preferredIds

    val available = fetchedOpenRouterModels.toSet()
    val preferredAvailable = preferredIds.filter { it in available }
    val otherAvailable = fetchedOpenRouterModels
        .filter { isOpenRouterFreeModel(it, freeModelIds) }
        .filter { it !in preferredAvailable }
        .filter { useCase != OpenRouterRankingUseCase.VISION || openRouterFreeSupportsVision(it) }

    return (preferredAvailable + otherAvailable).ifEmpty { preferredIds }
}

private fun preferredIdsFor(useCase: OpenRouterRankingUseCase): List<String> = when (useCase) {
    OpenRouterRankingUseCase.GENERAL -> preferredOpenRouterGeneralFreeModels
    OpenRouterRankingUseCase.REASONING -> preferredOpenRouterReasoningFreeModels
    OpenRouterRankingUseCase.VISION -> preferredOpenRouterVisionFreeModels
}

private fun rankingProfileFor(useCase: OpenRouterRankingUseCase): OpenRouterRankingProfile = when (useCase) {
    OpenRouterRankingUseCase.GENERAL -> OpenRouterRankingProfile(
        preferredIds = preferredOpenRouterGeneralFreeModels,
        preferredIdBoost = 4_000,
        preferredIdStepPenalty = 220,
        modelSizeWeight = 10,
        contextWeightPer4k = 10,
        visionSupportBonus = 80,
        reasoningKeywordBonus = 60,
        multimodalKeywordBonus = 40,
        codingKeywordBonus = 40,
        flashPenalty = 40,
        nanoPenalty = 220,
        routerPenalty = 20_000
    )
    OpenRouterRankingUseCase.REASONING -> OpenRouterRankingProfile(
        preferredIds = preferredOpenRouterReasoningFreeModels,
        preferredIdBoost = 4_400,
        preferredIdStepPenalty = 240,
        modelSizeWeight = 12,
        contextWeightPer4k = 12,
        visionSupportBonus = 40,
        reasoningKeywordBonus = 1_000,
        multimodalKeywordBonus = 20,
        codingKeywordBonus = 180,
        flashPenalty = 120,
        nanoPenalty = 260,
        routerPenalty = 20_000
    )
    OpenRouterRankingUseCase.VISION -> OpenRouterRankingProfile(
        preferredIds = preferredOpenRouterVisionFreeModels,
        preferredIdBoost = 4_600,
        preferredIdStepPenalty = 260,
        modelSizeWeight = 11,
        contextWeightPer4k = 10,
        visionSupportBonus = 1_300,
        reasoningKeywordBonus = 40,
        multimodalKeywordBonus = 260,
        codingKeywordBonus = 0,
        flashPenalty = 120,
        nanoPenalty = 220,
        routerPenalty = 20_000
    )
}

private fun scoreOpenRouterFreeModel(
    entry: OpenRouterCatalogEntry,
    useCase: OpenRouterRankingUseCase
): Int {
    val profile = rankingProfileFor(useCase)
    val signals = extractMetadataSignals(entry, profile.preferredIds)

    var score = 0
    if (signals.preferredIndex >= 0) {
        score += profile.preferredIdBoost - (signals.preferredIndex * profile.preferredIdStepPenalty)
    }

    score += signals.modelSizeBillions * profile.modelSizeWeight
    score += signals.contextBlocks * profile.contextWeightPer4k
    if (signals.supportsVision) score += profile.visionSupportBonus
    if (signals.hasReasoningKeywords) score += profile.reasoningKeywordBonus
    if (signals.hasMultimodalKeywords) score += profile.multimodalKeywordBonus
    if (signals.hasCodingKeywords) score += profile.codingKeywordBonus
    if (signals.isFlashVariant) score -= profile.flashPenalty
    if (signals.isNanoVariant) score -= profile.nanoPenalty
    if (signals.isRouterFallback) score -= profile.routerPenalty

    return score
}

private fun extractMetadataSignals(
    entry: OpenRouterCatalogEntry,
    preferredIds: List<String>
): OpenRouterMetadataSignals {
    val searchText = "${entry.id} ${entry.name} ${entry.description}".lowercase()
    return OpenRouterMetadataSignals(
        preferredIndex = preferredIds.indexOf(entry.id),
        modelSizeBillions = extractLargestBillionsValue(searchText),
        contextBlocks = (entry.contextLength.coerceAtMost(1_048_576L) / 4_096L).toInt().coerceAtMost(400),
        supportsVision = entry.supportsVision,
        hasReasoningKeywords = looksLikeReasoningModel(searchText),
        hasMultimodalKeywords = listOf("vision", "multimodal", "-vl", "vl:", "image").any(searchText::contains),
        hasCodingKeywords = listOf("coder", "code", "coding").any(searchText::contains),
        isFlashVariant = searchText.contains("flash"),
        isNanoVariant = searchText.contains("nano"),
        isRouterFallback = entry.id == OPENROUTER_FREE_ROUTER_MODEL
    )
}

private fun extractLargestBillionsValue(text: String): Int {
    val regex = Regex("(\\d+(?:\\.\\d+)?)b")
    return regex.findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }
        .maxOrNull()
        ?.toInt()
        ?: 0
}

private fun looksLikeReasoningModel(searchText: String): Boolean {
    return listOf(
        "reason",
        "thinking",
        "think",
        "r1",
        "gpt-oss",
        "qwen3",
        "deepseek",
        "chain-of-thought"
    ).any(searchText::contains)
}

fun openRouterFreeSupportsVision(modelId: String): Boolean {
    return modelId == OPENROUTER_FREE_ROUTER_MODEL ||
        AgentConfig(provider = AiProviderType.OPENROUTER, model = modelId).supportsVision
}

fun AgentConfig.canHandleImageRequests(): Boolean {
    return supportsVision ||
        (provider == AiProviderType.OPENROUTER && isOpenRouterFreeModel(model))
}

fun shouldRetryOpenRouterFreeFallback(code: Int?, message: String): Boolean {
    val normalized = message.lowercase()
    return code in setOf(400, 404, 408, 409, 429, 500, 502, 503, 504, 529) ||
        normalized.contains("model not found") ||
        normalized.contains("temporarily down") ||
        normalized.contains("service unavailable") ||
        normalized.contains("provider overloaded") ||
        normalized.contains("bad gateway") ||
        normalized.contains("rate limit") ||
        normalized.contains("no endpoints") ||
        normalized.contains("not available") ||
        normalized.contains("unavailable")
}
