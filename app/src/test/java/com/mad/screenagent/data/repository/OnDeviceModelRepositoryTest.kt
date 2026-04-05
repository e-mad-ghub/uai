package com.mad.screenagent.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceModelAccessState
import com.mad.screenagent.data.model.OnDeviceModelCapability
import com.mad.screenagent.data.model.OnDeviceModelCatalog
import com.mad.screenagent.data.model.OnDeviceModelCatalogEntry
import com.mad.screenagent.data.model.allOnDeviceCatalogEntries
import com.mad.screenagent.data.model.defaultOnDeviceCatalogEntries
import com.mad.screenagent.data.model.nonPublicOnDeviceCatalogEntries
import com.mad.screenagent.data.prefs.AppPreferences
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        assertEquals(3, publicEntries.size)
        assertTrue(nonPublicEntries.isEmpty())
        assertTrue(publicEntries.all { it.accessState == OnDeviceModelAccessState.PUBLIC })
        assertTrue(publicEntries.all { it.capability == OnDeviceModelCapability.LOCAL_TEXT })
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

    private suspend fun assertAccessBlockedResponseDowngrades(code: Int) {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(code).setBody("denied"))
        server.start()
        try {
            val entry = OnDeviceModelCatalogEntry(
                id = "public-false-positive",
                displayName = "Public False Positive",
                description = "Deliberately public-marked entry for access-blocked regression coverage.",
                downloadUrl = server.url("/model.task").toString(),
                fileName = "model.task",
                accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
                capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name
            )
            val prefs = AppPreferences(context)
            prefs.saveOnDeviceModelCatalog(
                OnDeviceModelCatalog(models = listOf(entry))
            )
            val repository = OnDeviceModelRepository(
                prefs = prefs,
                context = context,
                client = OkHttpClient()
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
}
