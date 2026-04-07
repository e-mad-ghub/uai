package com.mad.screenagent.data.model

data class OnDeviceProviderConfig(
    val selectedModelId: String = "",
    val selectedModelSupportsVision: Boolean = false,
    val maxOutputTokens: Int = 256
)

fun looksLikeVisionCapableOnDeviceModel(modelId: String): Boolean {
    val normalized = modelId.lowercase()
    return normalized == "gemma-3-4b-it-gguf" ||
        normalized.contains("vision") ||
        normalized.contains("-vl") ||
        normalized.contains("pixtral") ||
        normalized.contains("llava")
}
