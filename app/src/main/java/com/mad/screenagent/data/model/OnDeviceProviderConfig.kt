package com.mad.screenagent.data.model

data class OnDeviceProviderConfig(
    val selectedModelId: String = "",
    val maxOutputTokens: Int = 256
)

fun looksLikeVisionCapableOnDeviceModel(modelId: String): Boolean {
    val normalized = modelId.lowercase()
    return normalized.contains("vision") ||
        normalized.contains("-vl") ||
        normalized.contains("gemma") ||
        normalized.contains("pixtral") ||
        normalized.contains("llava")
}
