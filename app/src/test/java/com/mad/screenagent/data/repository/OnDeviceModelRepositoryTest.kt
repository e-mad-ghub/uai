package com.mad.screenagent.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.OnDeviceFailureKind
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceModelAccessState
import com.mad.screenagent.data.model.OnDeviceModelCapability
import com.mad.screenagent.data.model.OnDeviceModelCatalog
import com.mad.screenagent.data.model.OnDeviceModelCatalogEntry
import com.mad.screenagent.data.model.allOnDeviceCatalogEntries
import com.mad.screenagent.data.model.defaultOnDeviceCatalogEntries
import com.mad.screenagent.data.model.nonPublicOnDeviceCatalogEntries
import com.mad.screenagent.data.prefs.AppPreferences
import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.OnDeviceRuntime
import com.mad.screenagent.shared.streaming.OnDeviceValidationResult
import com.mad.screenagent.shared.streaming.StreamChunk
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnDeviceModelRepositoryTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        clearOnDeviceStorage()
    }

    @After
    fun tearDown() {
        clearOnDeviceStorage()
    }

    @Test
    fun defaultCatalogOnlyContainsPublicEntries() {
        val publicEntries = defaultOnDeviceCatalogEntries()
        val nonPublicEntries = nonPublicOnDeviceCatalogEntries()

        assertEquals(2, publicEntries.size)
        assertTrue(nonPublicEntries.isEmpty())
        assertTrue(publicEntries.all { it.accessState == OnDeviceModelAccessState.PUBLIC })
        assertTrue(publicEntries.all { it.capability == OnDeviceModelCapability.LOCAL_TEXT })
        assertTrue(publicEntries.all { it.fileName.endsWith(".gguf") })
        assertTrue(nonPublicEntries.all { it.accessState != OnDeviceModelAccessState.PUBLIC })
        assertTrue(publicEntries.map { it.id }.intersect(nonPublicEntries.map { it.id }.toSet()).isEmpty())
        assertEquals(
            allOnDeviceCatalogEntries().size,
            publicEntries.size + nonPublicEntries.size
        )
    }

    @Test
    fun downloadModelDowngrades401ResponseToFailed() = runBlocking {
        assertAccessBlockedResponseDowngrades(401)
    }

    @Test
    fun downloadModelDowngrades403ResponseToFailed() = runBlocking {
        assertAccessBlockedResponseDowngrades(403)
    }

    @Test
    fun staleExistingTargetFileForcesCleanDownload() = runBlocking {
        val prefs = AppPreferences(context)
        val repository = OnDeviceModelRepository(
            prefs = prefs,
            context = context,
            client = OkHttpClient(),
            runtime = FakeOnDeviceRuntime()
        )
        val entry = defaultOnDeviceCatalogEntries().first()
        val targetFile = File(repository.modelFilePath(entry.id)).apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        val partialFile = File(targetFile.parentFile, "${targetFile.name}.download")

        assertTrue(
            repository.shouldDiscardExistingArtifact(
                existingInstall = null,
                targetFile = targetFile,
                downloadFile = partialFile
            )
        )
    }

    @Test
    fun readyInstalledArtifactWithMatchingSizeIsReusable() = runBlocking {
        val prefs = AppPreferences(context)
        val repository = OnDeviceModelRepository(
            prefs = prefs,
            context = context,
            client = OkHttpClient(),
            runtime = FakeOnDeviceRuntime()
        )
        val entry = defaultOnDeviceCatalogEntries().first()
        val targetFile = File(repository.modelFilePath(entry.id)).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(16))
        }
        val partialFile = File(targetFile.parentFile, "${targetFile.name}.download")

        assertFalse(
            repository.shouldDiscardExistingArtifact(
                existingInstall = com.mad.screenagent.data.model.InstalledOnDeviceModel(
                    modelId = entry.id,
                    localPath = targetFile.absolutePath,
                    downloadState = OnDeviceDownloadState.READY,
                    downloadedBytes = targetFile.length(),
                    totalBytes = targetFile.length()
                ),
                targetFile = targetFile,
                downloadFile = partialFile
            )
        )
    }

    @Test
    fun runtimeValidationFailurePersistsStructuredFailureKind() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    okio.Buffer().write(
                        byteArrayOf(
                            0x47, 0x47, 0x55, 0x46, // GGUF
                            0x03, 0x00, 0x00, 0x00, // version 3
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // tensor count
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // kv count
                        )
                    )
                )
        )
        server.start()
        try {
            val entry = OnDeviceModelCatalogEntry(
                id = "runtime-incompatible",
                displayName = "Runtime Incompatible",
                description = "Test GGUF entry",
                downloadUrl = server.url("/model.gguf").toString(),
                fileName = "model.gguf",
                accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
                capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
                sourceTypeKey = com.mad.screenagent.data.model.OnDeviceModelSourceType.CATALOG.name
            )
            val prefs = AppPreferences(context)
            prefs.saveOnDeviceModelCatalog(OnDeviceModelCatalog(models = listOf(entry)))
            val repository = OnDeviceModelRepository(
                prefs = prefs,
                context = context,
                client = OkHttpClient(),
                runtime = FakeOnDeviceRuntime(
                    validationResult = OnDeviceValidationResult.failure(
                        OnDeviceFailureKind.RUNTIME_INCOMPATIBLE,
                        "The selected GGUF model could not be opened by the on-device llama runtime."
                    )
                )
            )

            val result = runCatching { repository.downloadModel(entry.id) }

            assertTrue(result.isFailure)
            val installed = repository.getInstalledModel(entry.id)
            assertEquals(OnDeviceDownloadState.FAILED, installed?.downloadState)
            assertEquals(OnDeviceFailureKind.RUNTIME_INCOMPATIBLE, installed?.failureKind)
            assertEquals("llama.android-af76639-arm64-v8a-kleidiai-openmp", installed?.validatedRuntimeProfileId)
        } finally {
            server.shutdown()
        }
    }

    private suspend fun assertAccessBlockedResponseDowngrades(code: Int) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(code).setBody("denied"))
        server.start()
        try {
            val entry = OnDeviceModelCatalogEntry(
                id = "public-false-positive",
                displayName = "Public False Positive",
                description = "Deliberately public-marked entry for access-blocked regression coverage.",
                downloadUrl = server.url("/model.gguf").toString(),
                fileName = "model.gguf",
                accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
                capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
                sourceTypeKey = com.mad.screenagent.data.model.OnDeviceModelSourceType.CATALOG.name
            )
            val prefs = AppPreferences(context)
            prefs.saveOnDeviceModelCatalog(
                OnDeviceModelCatalog(models = listOf(entry))
            )
            val repository = OnDeviceModelRepository(
                prefs = prefs,
                context = context,
                client = OkHttpClient(),
                runtime = FakeOnDeviceRuntime()
            )

            val error = try {
                repository.downloadModel(entry.id)
                null
            } catch (t: Throwable) {
                t
            }

            assertNotNull(error)
            assertNotNull(error!!.message)
            assertTrue(error.message!!.isNotBlank())

            assertEquals(OnDeviceModelAccessState.GATED, repository.catalogFlow.first().models.first().accessState)
        } finally {
            server.shutdown()
        }
    }

    private fun clearOnDeviceStorage() {
        File(context.filesDir, "datastore").deleteRecursively()
        File(context.filesDir, "on-device").deleteRecursively()
    }

    private class FakeOnDeviceRuntime(
        private val validationResult: OnDeviceValidationResult = OnDeviceValidationResult.success()
    ) : OnDeviceRuntime {
        override val runtimeProfileId: String = "llama.android-af76639-arm64-v8a-kleidiai-openmp"

        override suspend fun validateModel(
            modelPath: String,
            visionProjectorPath: String?
        ): OnDeviceValidationResult = validationResult

        override fun streamResponse(
            messages: List<ChatMessage>,
            config: AgentConfig,
            modelPath: String,
            visionProjectorPath: String?
        ) = flow<StreamChunk> { error("Not used in repository tests") }
    }
}
