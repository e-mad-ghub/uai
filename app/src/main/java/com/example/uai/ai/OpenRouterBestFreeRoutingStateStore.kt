package com.example.uai.ai

import com.example.uai.data.model.OpenRouterFreeRoutingBucket
import java.util.concurrent.ConcurrentHashMap

class OpenRouterBestFreeRoutingStateStore(
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {

    data class Entry(
        val modelId: String,
        val lastUsedAt: Long
    )

    private data class Key(
        val assistantId: String,
        val bucket: OpenRouterFreeRoutingBucket
    )

    private val entries = ConcurrentHashMap<Key, Entry>()

    fun lastSuccessfulModelId(
        assistantId: String,
        bucket: OpenRouterFreeRoutingBucket,
        idleTimeoutMs: Long
    ): String? {
        val key = Key(assistantId, bucket)
        val entry = entries[key] ?: return null
        if ((nowMillis() - entry.lastUsedAt) > idleTimeoutMs) {
            entries.remove(key, entry)
            return null
        }
        return entry.modelId
    }

    fun recordSuccess(
        assistantId: String,
        bucket: OpenRouterFreeRoutingBucket,
        modelId: String
    ) {
        entries[Key(assistantId, bucket)] = Entry(
            modelId = modelId,
            lastUsedAt = nowMillis()
        )
    }

    fun clear(
        assistantId: String,
        bucket: OpenRouterFreeRoutingBucket
    ) {
        entries.remove(Key(assistantId, bucket))
    }
}
