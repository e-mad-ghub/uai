package com.example.uai.data.model

const val OPENROUTER_FREE_ROUTER_MODEL = "openrouter/free"

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

private fun pickPreferredOpenRouterModel(
    preferredIds: List<String>,
    fetchedOpenRouterModels: List<String>,
    freeModelIds: Set<String>,
    fallback: String = preferredIds.first()
): String {
    if (fetchedOpenRouterModels.isEmpty()) return fallback
    val available = fetchedOpenRouterModels.toSet()
    return preferredIds.firstOrNull { it in available }
        ?: fetchedOpenRouterModels.firstOrNull { isOpenRouterFreeModel(it, freeModelIds) }
        ?: fallback
}

fun isOpenRouterFreeModel(modelId: String, freeModelIds: Set<String> = emptySet()): Boolean {
    return modelId == OPENROUTER_FREE_ROUTER_MODEL ||
        modelId.endsWith(":free") ||
        modelId in freeModelIds
}

fun preferredOpenRouterFastFreeModel(
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): String = pickPreferredOpenRouterModel(
    preferredIds = preferredOpenRouterGeneralFreeModels,
    fetchedOpenRouterModels = fetchedOpenRouterModels,
    freeModelIds = freeModelIds
)

fun preferredOpenRouterReasoningFreeModel(
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): String = pickPreferredOpenRouterModel(
    preferredIds = preferredOpenRouterReasoningFreeModels,
    fetchedOpenRouterModels = fetchedOpenRouterModels,
    freeModelIds = freeModelIds
)

fun preferredOpenRouterVisionFreeModel(
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet()
): String = pickPreferredOpenRouterModel(
    preferredIds = preferredOpenRouterVisionFreeModels,
    fetchedOpenRouterModels = fetchedOpenRouterModels,
    freeModelIds = freeModelIds
)

fun openRouterFreeFallbackModels(
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet(),
    currentModel: String? = null,
    requireVision: Boolean = false
): List<String> {
    val fetchedFreeModels = fetchedOpenRouterModels
        .filter { isOpenRouterFreeModel(it, freeModelIds) }
        .filter { !requireVision || openRouterFreeSupportsVision(it) }

    val rankedModels = if (requireVision) {
        preferredOpenRouterVisionFreeModels +
            preferredOpenRouterGeneralFreeModels +
            preferredOpenRouterReasoningFreeModels
    } else {
        preferredOpenRouterGeneralFreeModels +
            preferredOpenRouterReasoningFreeModels +
            preferredOpenRouterVisionFreeModels
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
