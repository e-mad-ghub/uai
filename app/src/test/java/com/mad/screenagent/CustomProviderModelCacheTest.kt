package com.mad.screenagent

import com.mad.screenagent.feature.agents.CustomModelCatalogCacheKey
import com.mad.screenagent.feature.agents.shouldReuseCustomModelCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomProviderModelCacheTest {

    @Test
    fun customCatalogCache_reusesOnlyWhenKeyMatches() {
        val key = CustomModelCatalogCacheKey(
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = "groq-key"
        )

        assertTrue(
            shouldReuseCustomModelCatalog(
                force = false,
                requestedKey = key,
                cachedKey = key,
                cachedModels = listOf("llama-3.3-70b-versatile")
            )
        )
    }

    @Test
    fun customCatalogCache_doesNotReuseAcrossDifferentEndpoints() {
        val cachedKey = CustomModelCatalogCacheKey(
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = "groq-key"
        )
        val requestedKey = CustomModelCatalogCacheKey(
            baseUrl = "https://integrate.api.nvidia.com/v1",
            apiKey = "nvidia-key"
        )

        assertFalse(
            shouldReuseCustomModelCatalog(
                force = false,
                requestedKey = requestedKey,
                cachedKey = cachedKey,
                cachedModels = listOf("llama-3.3-70b-versatile")
            )
        )
    }

    @Test
    fun customCatalogCache_doesNotReuseWhenInputsAreClearedOrForced() {
        val cachedKey = CustomModelCatalogCacheKey(
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = "groq-key"
        )

        assertFalse(
            shouldReuseCustomModelCatalog(
                force = false,
                requestedKey = null,
                cachedKey = cachedKey,
                cachedModels = listOf("llama-3.3-70b-versatile")
            )
        )
        assertFalse(
            shouldReuseCustomModelCatalog(
                force = true,
                requestedKey = cachedKey,
                cachedKey = cachedKey,
                cachedModels = listOf("llama-3.3-70b-versatile")
            )
        )
    }
}
