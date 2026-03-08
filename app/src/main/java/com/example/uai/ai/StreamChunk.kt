package com.example.uai.ai

sealed class StreamChunk {
    data class Token(val text: String) : StreamChunk()
    data object Done : StreamChunk()
    data class Error(val cause: Throwable) : StreamChunk()
}
