package com.example.uai.shared.streaming

class ThrottledStreamingMessageWriter(
    private val minUpdateIntervalMs: Long = 0L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sink: suspend (content: String, isStreaming: Boolean) -> Unit
) {
    private var lastEmittedContent: String? = null
    private var lastEmittedStreaming: Boolean? = null
    private var lastEmitAtMs: Long = Long.MIN_VALUE

    suspend fun emitStreaming(content: String) {
        if (content == lastEmittedContent && lastEmittedStreaming == true) return
        val now = nowMs()
        if (lastEmitAtMs == Long.MIN_VALUE || now - lastEmitAtMs >= minUpdateIntervalMs) {
            emit(content = content, isStreaming = true, timestampMs = now)
        }
    }

    suspend fun emitFinal(content: String) {
        if (content == lastEmittedContent && lastEmittedStreaming == false) return
        emit(content = content, isStreaming = false, timestampMs = nowMs())
    }

    private suspend fun emit(
        content: String,
        isStreaming: Boolean,
        timestampMs: Long
    ) {
        sink(content, isStreaming)
        lastEmittedContent = content
        lastEmittedStreaming = isStreaming
        lastEmitAtMs = timestampMs
    }
}
