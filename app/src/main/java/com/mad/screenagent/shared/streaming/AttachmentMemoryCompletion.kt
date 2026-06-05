package com.mad.screenagent.shared.streaming

suspend fun completeImageTurnMemoryIfNeeded(
    hasImages: Boolean,
    canHandleImages: Boolean,
    userMessageId: String?,
    prepareMemory: suspend (String) -> Unit
): Boolean {
    val messageId = userMessageId?.takeIf { it.isNotBlank() } ?: return false
    if (!hasImages || !canHandleImages) return false
    prepareMemory(messageId)
    return true
}
