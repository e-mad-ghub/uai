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
import com.mad.screenagent.shared.streaming.OnDeviceRuntime
import com.mad.screenagent.shared.streaming.StreamChunk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
            "gemma-3-1b-it-gguf",
            defaultRecommendedModelId(AiProviderType.ON_DEVICE)
        )
        assertEquals(
            listOf("gemma-3-1b-it-gguf", "gemma-3-4b-it-gguf"),
            recommendedModelChoices(AiProviderType.ON_DEVICE).map { it.id }
        )
    }

    @Test
    fun factoryBuildsTheOnDeviceProviderWhenSelected() {
        val provider = AiProviderFactory.create(
            config = AgentConfig(
                provider = AiProviderType.ON_DEVICE,
                model = "gemma-3-1b-it-gguf",
                onDevice = OnDeviceProviderConfig(selectedModelId = "gemma-3-1b-it-gguf")
            ),
            client = OkHttpClient(),
            onDeviceModelRepository = FakeOnDeviceModelSource(),
            onDeviceRuntime = FakeOnDeviceRuntime()
        )

        assertTrue(provider is OnDeviceProvider)
    }

    @Test
    fun onDeviceProviderFailsWhenTheModelIsNotInstalled() = runBlocking {
        val provider = OnDeviceProvider(FakeOnDeviceModelSource(), runtime = FakeOnDeviceRuntime())
        val chunk = provider.streamResponse(
            messages = emptyList(),
            config = AgentConfig(
                provider = AiProviderType.ON_DEVICE,
                model = "gemma-3-1b-it-gguf",
                onDevice = OnDeviceProviderConfig(selectedModelId = "gemma-3-1b-it-gguf")
            )
        ).first()

        assertTrue(chunk is StreamChunk.Error)
        assertTrue((chunk as StreamChunk.Error).cause.message!!.contains("not installed"))
    }

    @Test
    fun onDeviceProviderFailsWhileTheModelIsStillDownloading() = runBlocking {
        val modelFile = tempModelFile()
        val provider = OnDeviceProvider(
            FakeOnDeviceModelSource(
                installed = InstalledOnDeviceModel(
                    modelId = "gemma-3-1b-it-gguf",
                    localPath = modelFile.absolutePath,
                    downloadState = OnDeviceDownloadState.DOWNLOADING,
                    installedAt = 123L
                )
            ),
            runtime = FakeOnDeviceRuntime()
        )
        val chunk = provider.streamResponse(
            messages = emptyList(),
            config = AgentConfig(
                provider = AiProviderType.ON_DEVICE,
                model = "gemma-3-1b-it-gguf",
                onDevice = OnDeviceProviderConfig(selectedModelId = "gemma-3-1b-it-gguf")
            )
        ).first()

        assertTrue(chunk is StreamChunk.Error)
        assertTrue((chunk as StreamChunk.Error).cause.message!!.contains("downloading"))
    }

    @Test
    fun onDeviceProviderFailsWhileTheModelIsValidating() = runBlocking {
        val modelFile = tempModelFile()
        val provider = OnDeviceProvider(
            FakeOnDeviceModelSource(
                installed = InstalledOnDeviceModel(
                    modelId = "gemma-3-1b-it-gguf",
                    localPath = modelFile.absolutePath,
                    downloadState = OnDeviceDownloadState.VALIDATING,
                    installedAt = 123L
                )
            ),
            runtime = FakeOnDeviceRuntime()
        )
        val chunk = provider.streamResponse(
            messages = emptyList(),
            config = AgentConfig(
                provider = AiProviderType.ON_DEVICE,
                model = "gemma-3-1b-it-gguf",
                onDevice = OnDeviceProviderConfig(selectedModelId = "gemma-3-1b-it-gguf")
            )
        ).first()

        assertTrue(chunk is StreamChunk.Error)
        assertTrue((chunk as StreamChunk.Error).cause.message!!.contains("downloading"))
    }

    @Test
    fun onDeviceProviderUsesTheRuntimeSeamAfterInstall() = runBlocking {
        val modelFile = tempModelFile()
        val provider = OnDeviceProvider(
            FakeOnDeviceModelSource(
                installed = InstalledOnDeviceModel(
                    modelId = "gemma-3-1b-it-gguf",
                    localPath = modelFile.absolutePath,
                    downloadState = OnDeviceDownloadState.READY,
                    installedAt = 123L
                )
            ),
            runtime = FakeOnDeviceRuntime()
        )
        val chunks = provider.streamResponse(
            messages = emptyList(),
            config = AgentConfig(
                provider = AiProviderType.ON_DEVICE,
                model = "gemma-3-1b-it-gguf",
                onDevice = OnDeviceProviderConfig(selectedModelId = "gemma-3-1b-it-gguf")
            )
        ).toList()

        assertTrue(chunks.first() is StreamChunk.Token)
        assertTrue(chunks.last() is StreamChunk.Done)
    }

    private class FakeOnDeviceModelSource(
        private val installed: InstalledOnDeviceModel? = null
    ) : OnDeviceModelSource {
        override suspend fun getInstalledModel(modelId: String): InstalledOnDeviceModel? =
            installed?.takeIf { it.modelId == modelId }
    }

    private class FakeOnDeviceRuntime : OnDeviceRuntime {
        override suspend fun validateModel(modelPath: String): String? = null

        override fun streamResponse(
            messages: List<com.mad.screenagent.shared.streaming.ChatMessage>,
            config: AgentConfig,
            modelPath: String
        ) = flow {
            emit(StreamChunk.Token("ok"))
            emit(StreamChunk.Done)
        }
    }

    private fun tempModelFile(): File =
        File.createTempFile("gemma-3-1b-it", ".gguf").apply {
            writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46))
            deleteOnExit()
        }
}
