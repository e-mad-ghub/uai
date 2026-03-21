package com.example.uai.shared.streaming

sealed class StreamChunk {
    data class Token(val text: String) : StreamChunk()
    data class ModelSelection(
        val modelId: String,
        val viaFallback: Boolean = false
    ) : StreamChunk()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : StreamChunk()
    data object Done : StreamChunk()
    data class Error(val cause: Throwable) : StreamChunk()
}
