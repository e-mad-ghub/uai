package com.mad.screenagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mad.screenagent.data.model.InstalledOnDeviceModel
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceModelCatalog
import com.mad.screenagent.data.model.OnDeviceModelCatalogEntry
import com.mad.screenagent.data.prefs.AppPreferences
import com.mad.screenagent.data.repository.OnDeviceModelRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceModelRepositoryTest {

    @Test
    fun modelMetadataPersistsThroughAppPreferencesAndRepositoryFlows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = AppPreferences(context)
        val repository = OnDeviceModelRepository(prefs, context)

        val catalog = OnDeviceModelCatalog(
            fetchedAt = 1234L,
            models = listOf(
                OnDeviceModelCatalogEntry(
                    id = "gemma-3n-e2b-it",
                    displayName = "Gemma 3n E2B IT",
                    description = "Balanced on-device starter model."
                )
            )
        )
        val installed = InstalledOnDeviceModel(
            modelId = "gemma-3n-e2b-it",
            localPath = "${context.filesDir.absolutePath}/on-device/gemma-3n-e2b-it",
            downloadState = OnDeviceDownloadState.DOWNLOADED,
            installedAt = 5678L
        )

        prefs.saveOnDeviceModelCatalog(catalog)
        prefs.saveInstalledOnDeviceModels(listOf(installed))
        prefs.saveOnDeviceDownloadState(OnDeviceDownloadState.DOWNLOADED)

        assertEquals("gemma-3n-e2b-it", repository.catalogFlow.first().models.first().id)
        assertEquals(
            installed.localPath,
            repository.getInstalledModel("gemma-3n-e2b-it")?.localPath
        )
        assertTrue(repository.libraryFlow.first().first().installRecord != null)
        assertEquals(OnDeviceDownloadState.DOWNLOADED, prefs.onDeviceDownloadStateFlow.first())
    }
}
