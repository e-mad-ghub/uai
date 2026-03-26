package com.mad.screenagent

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.MONEY_SAVER_MODEL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [AgentConfig.supportsVision].
 *
 * Feature: agents
 * Risk: If the vision-detection logic is inadvertently changed (e.g. a model name
 * pattern is removed) image messages will silently be sent to providers that reject them,
 * or the attachment UI will be incorrectly hidden.
 */
class AgentConfigVisionTest {

    private fun agent(provider: AiProviderType, model: String) =
        AgentConfig(provider = provider, model = model)

    // ── MONEY_SAVER_MODEL (resolved at runtime, always assume vision capable) ──────

    @Test
    fun moneySaverModel_isAlwaysVisionCapable() {
        // Money-saver resolves at runtime; we conservatively allow vision attachments
        for (provider in AiProviderType.entries) {
            assertTrue(
                "MONEY_SAVER_MODEL should be vision-capable for provider $provider",
                agent(provider, MONEY_SAVER_MODEL).supportsVision
            )
        }
    }

    // ── OpenAI ────────────────────────────────────────────────────────────────────

    @Test
    fun openAi_gpt4o_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENAI, "gpt-4o").supportsVision)
        assertTrue(agent(AiProviderType.OPENAI, "gpt-4o-mini").supportsVision)
        assertTrue(agent(AiProviderType.OPENAI, "chatgpt-4o-latest").supportsVision)
    }

    @Test
    fun openAi_gpt41_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENAI, "gpt-4.1").supportsVision)
        assertTrue(agent(AiProviderType.OPENAI, "gpt-4.1-mini").supportsVision)
    }

    @Test
    fun openAi_gpt5_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENAI, "gpt-5").supportsVision)
        assertTrue(agent(AiProviderType.OPENAI, "gpt-5-mini").supportsVision)
    }

    @Test
    fun openAi_gpt4Turbo_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENAI, "gpt-4-turbo").supportsVision)
        assertTrue(agent(AiProviderType.OPENAI, "gpt-4-turbo-preview").supportsVision)
    }

    @Test
    fun openAi_gpt35_isNotVisionCapable() {
        assertFalse(agent(AiProviderType.OPENAI, "gpt-3.5-turbo").supportsVision)
    }

    @Test
    fun openAi_gpt4_baseWithoutTurbo_isNotVisionCapable() {
        // gpt-4 base (not turbo / not 4o / not 4.1) has no vision in this mapping
        assertFalse(agent(AiProviderType.OPENAI, "gpt-4").supportsVision)
    }

    // ── Anthropic ─────────────────────────────────────────────────────────────────

    @Test
    fun anthropic_claude3_isVisionCapable() {
        assertTrue(agent(AiProviderType.ANTHROPIC, "claude-3-opus-20240229").supportsVision)
        assertTrue(agent(AiProviderType.ANTHROPIC, "claude-3-sonnet-20240229").supportsVision)
        assertTrue(agent(AiProviderType.ANTHROPIC, "claude-3-haiku-20240307").supportsVision)
    }

    @Test
    fun anthropic_claudeSonnet4_isVisionCapable() {
        assertTrue(agent(AiProviderType.ANTHROPIC, "claude-sonnet-4-6").supportsVision)
    }

    @Test
    fun anthropic_claude2_isNotVisionCapable() {
        assertFalse(agent(AiProviderType.ANTHROPIC, "claude-2.1").supportsVision)
        assertFalse(agent(AiProviderType.ANTHROPIC, "claude-2").supportsVision)
    }

    @Test
    fun anthropic_instant_isNotVisionCapable() {
        assertFalse(agent(AiProviderType.ANTHROPIC, "claude-instant-1.2").supportsVision)
    }

    // ── OpenRouter ────────────────────────────────────────────────────────────────

    @Test
    fun openRouter_geminiModels_areVisionCapable() {
        assertTrue(agent(AiProviderType.OPENROUTER, "google/gemini-pro-1.5").supportsVision)
        assertTrue(agent(AiProviderType.OPENROUTER, "google/gemini-flash-1.5").supportsVision)
    }

    @Test
    fun openRouter_gemma3_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENROUTER, "google/gemma-3-27b-it:free").supportsVision)
    }

    @Test
    fun openRouter_gpt4o_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENROUTER, "openai/gpt-4o").supportsVision)
    }

    @Test
    fun openRouter_claude3_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENROUTER, "anthropic/claude-3.5-sonnet").supportsVision)
    }

    @Test
    fun openRouter_visionKeyword_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENROUTER, "some-provider/model-vision").supportsVision)
    }

    @Test
    fun openRouter_vlSuffix_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENROUTER, "qwen/qwen2.5-vl-72b-instruct:free").supportsVision)
    }

    @Test
    fun openRouter_llavaMention_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENROUTER, "mistralai/llava-next").supportsVision)
    }

    @Test
    fun openRouter_pixtral_isVisionCapable() {
        assertTrue(agent(AiProviderType.OPENROUTER, "mistralai/pixtral-large").supportsVision)
    }

    @Test
    fun openRouter_textOnlyFreeModel_isNotVisionCapable() {
        assertFalse(agent(AiProviderType.OPENROUTER, "meta-llama/llama-3.3-70b-instruct:free").supportsVision)
    }

    // ── Custom (OpenAI-compatible) ─────────────────────────────────────────────────

    @Test
    fun custom_gpt4oModel_isVisionCapable() {
        assertTrue(agent(AiProviderType.CUSTOM, "gpt-4o").supportsVision)
    }

    @Test
    fun custom_grokVisionModel_isVisionCapable() {
        assertTrue(agent(AiProviderType.CUSTOM, "grok-vision-beta").supportsVision)
    }

    @Test
    fun custom_groqLlama4ScoutModel_isVisionCapable() {
        assertTrue(
            agent(
                AiProviderType.CUSTOM,
                "meta-llama/llama-4-scout-17b-16e-instruct"
            ).supportsVision
        )
    }

    @Test
    fun custom_unknownModel_isNotVisionCapable() {
        assertFalse(agent(AiProviderType.CUSTOM, "my-custom-text-model").supportsVision)
    }
}
