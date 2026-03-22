package com.mad.screenagent.data.model

enum class CustomProviderPreset(
    val displayName: String,
    val suggestedBaseUrl: String,
    val description: String
) {
    MANUAL(
        displayName = "Manual",
        suggestedBaseUrl = "",
        description = "Use any OpenAI-compatible endpoint."
    ),
    GROK(
        displayName = "Grok",
        suggestedBaseUrl = "https://api.x.ai/v1",
        description = "Prefills xAI's OpenAI-compatible endpoint."
    ),
    NVIDIA(
        displayName = "NVIDIA",
        suggestedBaseUrl = "https://integrate.api.nvidia.com/v1",
        description = "Prefills NVIDIA NIM's OpenAI-compatible endpoint."
    )
}

fun normalizeOpenAiCompatibleBaseUrl(raw: String): String {
    var normalized = raw.trim().trimEnd('/')
    val lowercase = normalized.lowercase()
    normalized = when {
        lowercase.endsWith("/chat/completions") ->
            normalized.dropLast("/chat/completions".length)
        lowercase.endsWith("/models") ->
            normalized.dropLast("/models".length)
        else -> normalized
    }
    return normalized.trimEnd('/')
}

fun buildOpenAiCompatibleChatCompletionsUrl(baseUrl: String): String =
    "${normalizeOpenAiCompatibleBaseUrl(baseUrl)}/chat/completions"

fun buildOpenAiCompatibleModelsUrl(baseUrl: String): String =
    "${normalizeOpenAiCompatibleBaseUrl(baseUrl)}/models"

fun looksLikeVisionCapableOpenAiCompatibleModel(modelId: String): Boolean {
    val normalized = modelId.lowercase()
    return normalized.contains("gpt-5") ||
        normalized.contains("gpt-4o") ||
        normalized.contains("gpt-4.1") ||
        normalized.contains("chatgpt-4o") ||
        normalized.contains("grok-vision") ||
        normalized.contains("vision") ||
        normalized.contains("-vl") ||
        normalized.contains("gemini") ||
        normalized.contains("gemma-3") ||
        normalized.contains("pixtral") ||
        normalized.contains("llava")
}
