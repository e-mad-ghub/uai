package com.mad.screenagent

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.InstalledOnDeviceModel
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceProviderConfig
import com.mad.screenagent.data.repository.OnDeviceModelSource
import com.mad.screenagent.feature.agents.assistantProviderOrder
import com.mad.screenagent.feature.agents.defaultRecommendedModelId
import com.mad.screenagent.feature.agents.providerRequiresApiKey
import com.mad.screenagent.feature.agents.providerUiInfo
import com.mad.screenagent.feature.agents.recommendedModelChoices
import com.mad.screenagent.shared.streaming.AiProviderFactory
import com.mad.screenagent.shared.streaming.OnDeviceProvider
import com.mad.screenagent.shared.streaming.StreamChunk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceProviderFeatureTest {

    @Test
    fun onDeviceProviderAppearsFirstInProviderOrder() {
        assertEquals(AiProviderType.ON_DEVICE, assistantProviderOrder().first())
    }

    @Test
    fun onDeviceProviderUiInfoDoesNotRequireAnApiKey() {
        val info = providerUiInfo(AiProviderType.ON_DEVICE)

        assertTrue(info.apiKeyHint.contains("No API key"))
        assertFalse(providerRequiresApiKey(AiProviderType.ON_DEVICE))
    }

    @Test
    fun onDeviceRecommendedModelsStartWithTheCuratedStarterModel() {
        assertEquals(
            "gemma-3n-e2b-it",
            defaultRecommendedModelId(AiProviderType.ON_DEVICE)
        )
        assertEquals(
            listOf("gemma-3n-e2b-it", "gemma-3-1b-it", "gemma-3-4b-it"),
            recommendedModelChoices(AiProviderType.ON_DEVICE).map { it.id }
        )
    }

    @Test
    fun factoryBuildsTheOnDeviceProviderWhenSelected() {
        val provider = AiProviderFactory.create(
            config = AgentConfig(
                provider = AiProviderType.ON_DEVICE,
                model = "gemma-3n-e2b-it",
                onDevice = OnDeviceProviderConfig(selectedModelId = "gemma-3n-e2b-it")
            ),
            client = OkHttpClient(),
            onDeviceModelRepository = FakeOnDeviceModelSource()
        )

        assertTrue(provider is OnDeviceProvider)
    }

    @Test
    fun onDeviceProviderFailsWhenTheModelIsNotInstalled() = runBlocking {
        val provider = OnDeviceProvider(FakeOnDeviceModelSource())
        val chunk = provider.streamResponse(
            messages = emptyList(),
            config = AgentConfig(
                provider = AiProviderType.ON_DEVICE,
                model = "gemma-3n-e2b-it",
                onDevice = OnDeviceProviderConfig(selectedModelId = "gemma-3n-e2b-it")
            )
        ).first()

        assertTrue(chunk is StreamChunk.Error)
        assertTrue((chunk as StreamChunk.Error).cause.message!!.contains("not installed"))
    }

    @Test
    fun onDeviceProviderUsesTheRuntimeSeamAfterInstall() = runBlocking {
        val provider = OnDeviceProvider(
            FakeOnDeviceModelSource(
                installed = InstalledOnDeviceModel(
                    modelId = "gemma-3n-e2b-it",
                    localPath = File("/tmp/gemma-3n-e2b-it").absolutePath,
                    downloadState = OnDeviceDownloadState.DOWNLOADED,
                    installedAt = 123L
                )
            )
        )
        val chunk = provider.streamResponse(
            messages = emptyList(),
            config = AgentConfig(
                provider = AiProviderType.ON_DEVICE,
                model = "gemma-3n-e2b-it",
                onDevice = OnDeviceProviderConfig(selectedModelId = "gemma-3n-e2b-it")
            )
        ).first()

        assertTrue(chunk is StreamChunk.Error)
        assertTrue((chunk as StreamChunk.Error).cause.message!!.contains("runtime is not wired"))
    }

    private class FakeOnDeviceModelSource(
        private val installed: InstalledOnDeviceModel? = null
    ) : OnDeviceModelSource {
        override suspend fun getInstalledModel(modelId: String): InstalledOnDeviceModel? =
            installed?.takeIf { it.modelId == modelId }
    }
}
