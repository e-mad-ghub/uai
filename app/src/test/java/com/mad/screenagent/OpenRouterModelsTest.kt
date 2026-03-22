package com.mad.screenagent

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.OpenRouterFreeRoutingBucket
import com.mad.screenagent.data.model.OPENROUTER_FREE_ROUTER_MODEL
import com.mad.screenagent.data.model.OpenRouterCatalogEntry
import com.mad.screenagent.data.model.SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
import com.mad.screenagent.data.model.canHandleImageRequests
import com.mad.screenagent.data.model.openRouterBestFreeCandidates
import com.mad.screenagent.data.model.openRouterFreeFallbackModels
import com.mad.screenagent.data.model.openRouterFreeSupportsVision
import com.mad.screenagent.data.model.preferredOpenRouterFastFreeModel
import com.mad.screenagent.data.model.shouldRetryOpenRouterFreeFallback
import com.mad.screenagent.feature.agents.assistantProviderOrder
import com.mad.screenagent.feature.agents.defaultRecommendedModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelsTest {

    @Test
    fun newAgentDefaultsToSideAgentBestFreeRoute() {
        val agent = AgentConfig()

        assertEquals(AiProviderType.OPENROUTER, agent.provider)
        assertEquals(SIDEAGENT_OPENROUTER_BEST_FREE_MODEL, agent.model)
    }

    @Test
    fun providerOrderStartsWithOpenRouter() {
        assertEquals(AiProviderType.OPENROUTER, assistantProviderOrder().first())
    }

    @Test
    fun preferredBestFreeModelUsesHighestRankedAvailableCandidate() {
        val fetchedModels = listOf(
            "meta-llama/llama-3.3-70b-instruct:free",
            OPENROUTER_FREE_ROUTER_MODEL,
            "openai/gpt-4o"
        )
        val freeModelIds = setOf(
            "meta-llama/llama-3.3-70b-instruct:free",
            OPENROUTER_FREE_ROUTER_MODEL
        )

        val selected = preferredOpenRouterFastFreeModel(
            fetchedOpenRouterModels = fetchedModels,
            freeModelIds = freeModelIds
        )

        assertEquals("meta-llama/llama-3.3-70b-instruct:free", selected)
    }

    @Test
    fun preferredBestFreeModelFallsBackToLiveCatalogWhenCuratedModelsDisappear() {
        val selected = preferredOpenRouterFastFreeModel(
            catalogEntries = listOf(
                OpenRouterCatalogEntry(
                    id = "mistralai/ministral-8b-instruct:free",
                    name = "Ministral 8B Instruct",
                    contextLength = 128_000,
                    promptPrice = 0.0,
                    completionPrice = 0.0
                ),
                OpenRouterCatalogEntry(
                    id = OPENROUTER_FREE_ROUTER_MODEL,
                    name = "OpenRouter Free Router",
                    contextLength = 128_000,
                    promptPrice = 0.0,
                    completionPrice = 0.0
                )
            )
        )

        assertEquals("mistralai/ministral-8b-instruct:free", selected)
    }

    @Test
    fun defaultOpenRouterRecommendationStartsWithBestAvailableFreePath() {
        val fetchedModels = listOf(
            "google/gemma-3-12b-it:free",
            OPENROUTER_FREE_ROUTER_MODEL,
            "openai/gpt-4o"
        )
        val freeModelIds = setOf(
            "google/gemma-3-12b-it:free",
            OPENROUTER_FREE_ROUTER_MODEL
        )

        val selected = defaultRecommendedModelId(
            provider = AiProviderType.OPENROUTER,
            fetchedProviderModels = fetchedModels,
            freeModelIds = freeModelIds
        )

        assertEquals(SIDEAGENT_OPENROUTER_BEST_FREE_MODEL, selected)
    }

    @Test
    fun freeFallbackCandidatesIncludeNewCatalogModels() {
        val candidates = openRouterFreeFallbackModels(
            catalogEntries = listOf(
                OpenRouterCatalogEntry(
                    id = "mistralai/ministral-8b-instruct:free",
                    name = "Ministral 8B Instruct",
                    contextLength = 128_000,
                    promptPrice = 0.0,
                    completionPrice = 0.0
                ),
                OpenRouterCatalogEntry(
                    id = OPENROUTER_FREE_ROUTER_MODEL,
                    name = "OpenRouter Free Router",
                    contextLength = 128_000,
                    promptPrice = 0.0,
                    completionPrice = 0.0
                )
            )
        )

        assertTrue(candidates.contains("mistralai/ministral-8b-instruct:free"))
        assertTrue(candidates.contains(OPENROUTER_FREE_ROUTER_MODEL))
    }

    @Test
    fun freeFallbackCandidatesKeepCurrentModelFirstAndDeduplicate() {
        val candidates = openRouterFreeFallbackModels(
            fetchedOpenRouterModels = listOf(
                OPENROUTER_FREE_ROUTER_MODEL,
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemma-3-12b-it:free"
            ),
            freeModelIds = setOf(
                OPENROUTER_FREE_ROUTER_MODEL,
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemma-3-12b-it:free"
            ),
            currentModel = "google/gemma-3-12b-it:free"
        )

        assertEquals("google/gemma-3-12b-it:free", candidates.first())
        assertEquals(candidates.size, candidates.distinct().size)
        assertTrue(candidates.contains(OPENROUTER_FREE_ROUTER_MODEL))
    }

    @Test
    fun visionFallbackCandidatesPreferVisionModels() {
        val candidates = openRouterFreeFallbackModels(
            currentModel = "meta-llama/llama-3.3-70b-instruct:free",
            requireVision = true
        )

        assertEquals("google/gemma-3-27b-it:free", candidates.first())
        assertFalse(candidates.contains("meta-llama/llama-3.3-70b-instruct:free"))
        assertFalse(candidates.contains(OPENROUTER_FREE_ROUTER_MODEL))
    }

    @Test
    fun bestFreeCandidatesRotateFromLastSuccessfulModel() {
        val candidates = openRouterBestFreeCandidates(
            bucket = OpenRouterFreeRoutingBucket.GENERAL,
            fetchedOpenRouterModels = listOf(
                OPENROUTER_FREE_ROUTER_MODEL,
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemma-3-12b-it:free"
            ),
            freeModelIds = setOf(
                OPENROUTER_FREE_ROUTER_MODEL,
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemma-3-12b-it:free"
            ),
            startModelId = "google/gemma-3-12b-it:free"
        )

        assertEquals("google/gemma-3-12b-it:free", candidates.first())
        assertEquals(candidates.size, candidates.distinct().size)
        assertTrue(candidates.contains("meta-llama/llama-3.3-70b-instruct:free"))
    }

    @Test
    fun bestFreeVisionCandidatesDoNotSpillIntoGeneralTextModels() {
        val candidates = openRouterBestFreeCandidates(
            bucket = OpenRouterFreeRoutingBucket.VISION,
            fetchedOpenRouterModels = listOf(
                "openrouter/hunter-alpha",
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemma-3-12b-it:free",
                "nvidia/nemotron-nano-12b-v2-vl:free",
                OPENROUTER_FREE_ROUTER_MODEL
            ),
            freeModelIds = setOf(
                "openrouter/hunter-alpha",
                "meta-llama/llama-3.3-70b-instruct:free",
                "google/gemma-3-12b-it:free",
                "nvidia/nemotron-nano-12b-v2-vl:free",
                OPENROUTER_FREE_ROUTER_MODEL
            )
        )

        assertTrue(candidates.contains("google/gemma-3-12b-it:free"))
        assertTrue(candidates.contains("nvidia/nemotron-nano-12b-v2-vl:free"))
        assertFalse(candidates.contains("openrouter/hunter-alpha"))
        assertFalse(candidates.contains("meta-llama/llama-3.3-70b-instruct:free"))
        assertFalse(candidates.contains(OPENROUTER_FREE_ROUTER_MODEL))
    }

    @Test
    fun bestFreeVisionCandidatesIgnoreWeakCatalogOnlyVisionClaims() {
        val candidates = openRouterBestFreeCandidates(
            bucket = OpenRouterFreeRoutingBucket.VISION,
            catalogEntries = listOf(
                OpenRouterCatalogEntry(
                    id = "openrouter/hunter-alpha",
                    name = "Hunter Alpha",
                    description = "General assistant model",
                    promptPrice = 0.0,
                    completionPrice = 0.0,
                    supportsVision = true
                ),
                OpenRouterCatalogEntry(
                    id = "openrouter/healer-alpha",
                    name = "Healer Alpha",
                    description = "Omni-modal reasoning model with vision support",
                    promptPrice = 0.0,
                    completionPrice = 0.0,
                    supportsVision = true
                )
            )
        )

        assertTrue(candidates.contains("openrouter/healer-alpha"))
        assertFalse(candidates.contains("openrouter/hunter-alpha"))
    }

    @Test
    fun visionSupportUsesCatalogMetadataForNewFreeModels() {
        assertTrue(
            openRouterFreeSupportsVision(
                modelId = "openrouter/healer-alpha",
                catalogEntries = listOf(
                    OpenRouterCatalogEntry(
                        id = "openrouter/healer-alpha",
                        name = "Healer Alpha",
                        supportsVision = true
                    )
                )
            )
        )
    }

    @Test
    fun concreteTextOnlyOpenRouterFreeModelDoesNotClaimImageSupport() {
        val agent = AgentConfig(
            provider = AiProviderType.OPENROUTER,
            model = "meta-llama/llama-3.3-70b-instruct:free"
        )

        assertFalse(agent.canHandleImageRequests())
    }

    @Test
    fun sideAgentBestFreeRouteStillClaimsImageSupport() {
        val agent = AgentConfig(
            provider = AiProviderType.OPENROUTER,
            model = SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
        )

        assertTrue(agent.canHandleImageRequests())
    }

    @Test
    fun freeFallbackRetriesOnlyTransientFailures() {
        assertTrue(
            shouldRetryOpenRouterFreeFallback(
                code = 503,
                message = "HTTP 503 · Service unavailable — the provider is temporarily down, try again later"
            )
        )
        assertFalse(
            shouldRetryOpenRouterFreeFallback(
                code = 401,
                message = "HTTP 401 · Invalid API key — check the API key in your agent settings"
            )
        )
    }
}
