package com.mad.screenagent

import com.mad.screenagent.data.model.buildOpenAiCompatibleChatCompletionsUrl
import com.mad.screenagent.data.model.buildOpenAiCompatibleModelsUrl
import com.mad.screenagent.data.model.looksLikeVisionCapableOpenAiCompatibleModel
import com.mad.screenagent.data.model.normalizeOpenAiCompatibleBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for OpenAI-compatible URL normalization and vision detection helpers.
 *
 * Feature: agents (custom provider configuration)
 * Risk: If URL normalization changes, custom provider connection tests and API calls
 * will hit wrong endpoints without any visible error at the point of change.
 */
class OpenAiCompatibleConfigTest {

    // ── normalizeOpenAiCompatibleBaseUrl ──────────────────────────────────────────

    @Test
    fun normalizeBaseUrl_stripsTrailingSlash() {
        assertEquals("https://api.example.com/v1", normalizeOpenAiCompatibleBaseUrl("https://api.example.com/v1/"))
    }

    @Test
    fun normalizeBaseUrl_stripsChatCompletionsSuffix() {
        assertEquals(
            "https://api.example.com/v1",
            normalizeOpenAiCompatibleBaseUrl("https://api.example.com/v1/chat/completions")
        )
    }

    @Test
    fun normalizeBaseUrl_stripsModelsSuffix() {
        assertEquals(
            "https://api.example.com/v1",
            normalizeOpenAiCompatibleBaseUrl("https://api.example.com/v1/models")
        )
    }

    @Test
    fun normalizeBaseUrl_trimsLeadingAndTrailingWhitespace() {
        assertEquals("https://api.example.com/v1", normalizeOpenAiCompatibleBaseUrl("  https://api.example.com/v1  "))
    }

    @Test
    fun normalizeBaseUrl_isCaseInsensitiveForSuffix() {
        assertEquals(
            "https://api.example.com/v1",
            normalizeOpenAiCompatibleBaseUrl("https://api.example.com/v1/Chat/Completions")
        )
    }

    @Test
    fun normalizeBaseUrl_leavesCleanUrlUnchanged() {
        assertEquals("https://api.example.com/v1", normalizeOpenAiCompatibleBaseUrl("https://api.example.com/v1"))
    }

    // ── URL builders ──────────────────────────────────────────────────────────────

    @Test
    fun buildChatCompletionsUrl_appendsCorrectPath() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            buildOpenAiCompatibleChatCompletionsUrl("https://api.example.com/v1")
        )
    }

    @Test
    fun buildModelsUrl_appendsCorrectPath() {
        assertEquals(
            "https://api.example.com/v1/models",
            buildOpenAiCompatibleModelsUrl("https://api.example.com/v1")
        )
    }

    @Test
    fun buildChatCompletionsUrl_normalizesInputFirst() {
        // If user pasted the full URL by mistake, builder should still work
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            buildOpenAiCompatibleChatCompletionsUrl("https://api.example.com/v1/chat/completions")
        )
    }

    // ── looksLikeVisionCapableOpenAiCompatibleModel ───────────────────────────────

    @Test
    fun visionDetection_gpt4o_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("gpt-4o"))
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("GPT-4O")) // case-insensitive
    }

    @Test
    fun visionDetection_gpt41_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("gpt-4.1"))
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("gpt-4.1-mini"))
    }

    @Test
    fun visionDetection_gpt5_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("gpt-5"))
    }

    @Test
    fun visionDetection_grokVision_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("grok-vision-beta"))
    }

    @Test
    fun visionDetection_modelWithVisionKeyword_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("my-company/vision-model"))
    }

    @Test
    fun visionDetection_vlSuffix_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("qwen-vl-chat"))
    }

    @Test
    fun visionDetection_gemini_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("gemini-pro"))
    }

    @Test
    fun visionDetection_gemma3_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("gemma-3-27b-it"))
    }

    @Test
    fun visionDetection_llava_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("llava-1.6"))
    }

    @Test
    fun visionDetection_pixtral_isVision() {
        assertTrue(looksLikeVisionCapableOpenAiCompatibleModel("pixtral-large"))
    }

    @Test
    fun visionDetection_gpt35_isNotVision() {
        assertFalse(looksLikeVisionCapableOpenAiCompatibleModel("gpt-3.5-turbo"))
    }

    @Test
    fun visionDetection_unknownTextModel_isNotVision() {
        assertFalse(looksLikeVisionCapableOpenAiCompatibleModel("my-custom-llm"))
    }
}
