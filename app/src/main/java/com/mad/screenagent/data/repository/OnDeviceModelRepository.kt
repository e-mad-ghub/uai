package com.mad.screenagent.data.repository

import android.content.Context
import com.mad.screenagent.data.model.InstalledOnDeviceModel
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceModelCatalog
import com.mad.screenagent.data.model.OnDeviceModelCatalogEntry
import com.mad.screenagent.data.model.OnDeviceModelLibraryItem
import com.mad.screenagent.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

interface OnDeviceModelSource {
    suspend fun getInstalledModel(modelId: String): InstalledOnDeviceModel?
}

class OnDeviceModelRepository(
    private val prefs: AppPreferences,
    private val context: Context
) : OnDeviceModelSource {
    val catalogFlow: Flow<OnDeviceModelCatalog> = prefs.onDeviceModelCatalogFlow
    val installedModelsFlow: Flow<List<InstalledOnDeviceModel>> = prefs.installedOnDeviceModelsFlow
    val downloadStateFlow: Flow<OnDeviceDownloadState> = prefs.onDeviceDownloadStateFlow
    val libraryFlow: Flow<List<OnDeviceModelLibraryItem>> = combine(
        catalogFlow,
        installedModelsFlow
    ) { catalog, installed ->
        catalog.models.map { entry ->
            OnDeviceModelLibraryItem(
                catalogEntry = entry,
                installRecord = installed.firstOrNull { it.modelId == entry.id }
            )
        }
    }

    suspend fun ensureDefaultCatalog(): OnDeviceModelCatalog {
        val current = catalogFlow.first()
        if (current.models.isNotEmpty()) return current
        val seeded = OnDeviceModelCatalog(
            models = listOf(
                OnDeviceModelCatalogEntry(
                    id = "gemma-3n-e2b-it",
                    displayName = "Gemma 3n E2B IT",
                    description = "Balanced on-device starter model."
                ),
                OnDeviceModelCatalogEntry(
                    id = "gemma-3-1b-it",
                    displayName = "Gemma 3 1B IT",
                    description = "Lightweight on-device chat model."
                ),
                OnDeviceModelCatalogEntry(
                    id = "gemma-3-4b-it",
                    displayName = "Gemma 3 4B IT",
                    description = "More capable on-device model."
                )
            )
        )
        prefs.saveOnDeviceModelCatalog(seeded)
        return seeded
    }

    suspend fun refreshCatalogIfStale(force: Boolean = false): OnDeviceModelCatalog {
        // Placeholder catalog source for the first pass.
        // The actual download/discovery backend will be wired in the next stage.
        return ensureDefaultCatalog()
    }

    suspend fun getInstalledModels(): List<InstalledOnDeviceModel> =
        installedModelsFlow.first()

    override suspend fun getInstalledModel(modelId: String): InstalledOnDeviceModel? =
        installedModelsFlow.first().firstOrNull { it.modelId == modelId }

    suspend fun saveInstalledModel(modelId: String, localPath: String) {
        val current = installedModelsFlow.first().toMutableList()
        val updated = InstalledOnDeviceModel(
            modelId = modelId,
            localPath = localPath,
            downloadState = OnDeviceDownloadState.DOWNLOADED,
            installedAt = System.currentTimeMillis()
        )
        val idx = current.indexOfFirst { it.modelId == modelId }
        if (idx >= 0) current[idx] = updated else current.add(updated)
        prefs.saveInstalledOnDeviceModels(current)
    }

    suspend fun deleteInstalledModel(modelId: String) {
        val current = installedModelsFlow.first().filterNot { it.modelId == modelId }
        prefs.saveInstalledOnDeviceModels(current)
    }

    suspend fun saveDownloadState(state: OnDeviceDownloadState) {
        prefs.saveOnDeviceDownloadState(state)
    }

    fun modelFilePath(modelId: String): String =
        "${context.filesDir.absolutePath}/on-device/$modelId"
}
