package com.example.uai

import com.example.uai.data.model.AiProviderType
import com.example.uai.ui.agents.defaultRecommendedModelId
import com.example.uai.ui.agents.recommendedModelChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelRecommendationsTest {

    @Test
    fun openAiDefaultRecommendationPrefersBalancedGeneralModelFromLiveCatalog() {
        val selected = defaultRecommendedModelId(
            provider = AiProviderType.OPENAI,
            fetchedProviderModels = listOf(
                "gpt-4.1",
                "gpt-4o-mini",
                "gpt-4o",
                "gpt-4-turbo"
            )
        )

        assertEquals("gpt-4o", selected)
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
    fun anthropicFallsBackToLatestAvailableModelWhenPreferredFamilyIsMissing() {
        val selected = defaultRecommendedModelId(
            provider = AiProviderType.ANTHROPIC,
            fetchedProviderModels = listOf(
                "claude-4-0",
                "claude-3-7"
            )
        )

        assertEquals("claude-4-0", selected)
    }

    @Test
    fun openAiRecommendationsStayDistinctWhenCatalogHasEnoughChoices() {
        val choices = recommendedModelChoices(
            provider = AiProviderType.OPENAI,
            fetchedProviderModels = listOf(
                "gpt-4.1",
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo"
            )
        )

        assertEquals(3, choices.size)
        assertTrue(choices.map { it.id }.distinct().size == choices.size)
    }
}
