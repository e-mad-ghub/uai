package com.mad.screenagent

import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.MONEY_SAVER_MODEL
import com.mad.screenagent.data.model.buildOpenAiCompatibleChatCompletionsUrl
import com.mad.screenagent.data.model.normalizeOpenAiCompatibleBaseUrl
import com.mad.screenagent.feature.agents.defaultRecommendedModelId
import com.mad.screenagent.feature.agents.recommendedModelChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelRecommendationsTest {

    @Test
    fun openAiDefaultRecommendationStartsWithMoneySaverWhenLiveCatalogIsUnavailable() {
        // Money Saver is pinned as the first choice for all providers so defaultRecommendedModelId
        // returns it regardless of catalog availability.
        val selected = defaultRecommendedModelId(
            provider = AiProviderType.OPENAI
        )

        assertEquals(MONEY_SAVER_MODEL, selected)
    }

    @Test
    fun openAiDefaultRecommendationStartsWithMoneySaverRegardlessOfCatalog() {
        // Money Saver is always the first recommended choice even when the live catalog is present.
        val selected = defaultRecommendedModelId(
            provider = AiProviderType.OPENAI,
            fetchedProviderModels = listOf(
                "gpt-4.1",
                "gpt-4o-mini",
                "gpt-4o",
                "gpt-4-turbo"
            )
        )

        assertEquals(MONEY_SAVER_MODEL, selected)
    }

    @Test
    fun openAiFastRecommendationPrefersMiniVariant() {
        val choices = recommendedModelChoices(
            provider = AiProviderType.OPENAI,
            fetchedProviderModels = listOf(
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4o",
                "gpt-4o-mini"
            )
        )

        val fastChoice = choices.first { it.label == "Fast" }
        assertEquals("gpt-4.1-mini", fastChoice.id)
    }

    @Test
    fun anthropicRecommendationsUseCurrentClaudeFamiliesFromLiveCatalog() {
        val choices = recommendedModelChoices(
            provider = AiProviderType.ANTHROPIC,
            fetchedProviderModels = listOf(
                "claude-sonnet-4-7",
                "claude-haiku-4-6",
                "claude-opus-4-6"
            )
        )

        assertEquals("claude-sonnet-4-7", choices.first { it.label == "Balanced" }.id)
        assertEquals("claude-haiku-4-6", choices.first { it.label == "Fast" }.id)
        assertEquals("claude-opus-4-6", choices.first { it.label == "Best quality" }.id)
    }

    @Test
    fun anthropicDefaultRecommendationStartsWithMoneySaverWhenLiveCatalogIsUnavailable() {
        val selected = defaultRecommendedModelId(
            provider = AiProviderType.ANTHROPIC
        )

        assertEquals(MONEY_SAVER_MODEL, selected)
    }

    @Test
    fun anthropicDefaultRecommendationStartsWithMoneySaverRegardlessOfCatalog() {
        // Money Saver is always the first recommended choice even when live models are provided.
        val selected = defaultRecommendedModelId(
            provider = AiProviderType.ANTHROPIC,
            fetchedProviderModels = listOf(
                "claude-4-0",
                "claude-3-7"
            )
        )

        assertEquals(MONEY_SAVER_MODEL, selected)
    }

    @Test
    fun openAiRecommendationsIncludeMoneySaverPlusThreeModelRolesWhenCatalogHasEnoughChoices() {
        // Money Saver + Balanced + Fast + Detailed = 4 distinct choices when catalog provides
        // enough distinct model IDs to fill all three model roles without duplication.
        val choices = recommendedModelChoices(
            provider = AiProviderType.OPENAI,
            fetchedProviderModels = listOf(
                "gpt-4.1",
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo"
            )
        )

        assertEquals(4, choices.size)
        assertTrue(choices.map { it.id }.distinct().size == choices.size)
    }

    @Test
    fun gpt5ModelsAreTreatedAsVisionCapableForOpenAiAssistants() {
        val agent = AgentConfig(
            provider = AiProviderType.OPENAI,
            model = "gpt-5"
        )

        assertTrue(agent.supportsVision)
    }

    @Test
    fun customProviderDefaultRecommendationFallsBackToManualModelEntry() {
        val selected = defaultRecommendedModelId(
            provider = AiProviderType.CUSTOM
        )

        assertEquals("", selected)
    }

    @Test
    fun openAiCompatibleBaseUrlNormalizationStripsEndpointSuffixes() {
        val normalized = normalizeOpenAiCompatibleBaseUrl(
            "https://api.x.ai/v1/chat/completions/"
        )

        assertEquals("https://api.x.ai/v1", normalized)
        assertEquals(
            "https://api.x.ai/v1/chat/completions",
            buildOpenAiCompatibleChatCompletionsUrl(normalized)
        )
    }
}
