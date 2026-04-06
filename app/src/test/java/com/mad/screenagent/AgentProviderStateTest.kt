package com.mad.screenagent

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.feature.agents.forProviderSwitch
import com.mad.screenagent.feature.agents.supportsNativeWebSearch
import com.mad.screenagent.shared.streaming.shouldUseOpenAiResponsesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProviderStateTest {

    @Test
    fun providerSwitch_clearsNativeWebSearchState() {
        val draft = AgentConfig(
            provider = AiProviderType.OPENAI,
            model = "gpt-4.1",
            apiKey = "secret",
            nativeWebSearchEnabled = true,
            nativeWebSearchToolType = "web_search_preview"
        )

        val switched = draft.forProviderSwitch(
            provider = AiProviderType.CUSTOM,
            defaultModel = "llama-3.3-70b-versatile"
        )

        assertEquals(AiProviderType.CUSTOM, switched.provider)
        assertFalse(switched.nativeWebSearchEnabled)
        assertEquals(null, switched.nativeWebSearchToolType)
    }

    @Test
    fun providerSwitch_toOnDeviceClearsModelSelection() {
        val draft = AgentConfig(
            provider = AiProviderType.OPENAI,
            model = "gpt-4.1",
            apiKey = "secret"
        )

        val switched = draft.forProviderSwitch(
            provider = AiProviderType.ON_DEVICE,
            defaultModel = "gemma-3-1b-it-gguf"
        )

        assertEquals(AiProviderType.ON_DEVICE, switched.provider)
        assertEquals("", switched.model)
        assertEquals("", switched.onDevice.selectedModelId)
    }

    @Test
    fun supportsNativeWebSearch_onlyAllowsOpenAiAndAnthropic() {
        assertTrue(supportsNativeWebSearch(AiProviderType.OPENAI))
        assertTrue(supportsNativeWebSearch(AiProviderType.ANTHROPIC))
        assertFalse(supportsNativeWebSearch(AiProviderType.OPENROUTER))
        assertFalse(supportsNativeWebSearch(AiProviderType.CUSTOM))
    }

    @Test
    fun openAiResponsesPath_isOnlyUsedForOpenAiAgents() {
        val openAi = AgentConfig(
            provider = AiProviderType.OPENAI,
            nativeWebSearchEnabled = true
        )
        val custom = AgentConfig(
            provider = AiProviderType.CUSTOM,
            nativeWebSearchEnabled = true
        )

        assertTrue(shouldUseOpenAiResponsesApi(openAi))
        assertFalse(shouldUseOpenAiResponsesApi(custom))
    }
}
