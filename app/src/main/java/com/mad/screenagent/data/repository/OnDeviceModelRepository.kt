package com.mad.screenagent.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mad.screenagent.data.model.InstalledOnDeviceModel
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceModelAccessState
import com.mad.screenagent.data.model.OnDeviceModelCatalog
import com.mad.screenagent.data.model.OnDeviceModelCatalogEntry
import com.mad.screenagent.data.model.OnDeviceModelLibraryItem
import com.mad.screenagent.data.model.defaultOnDeviceCatalogEntries
import com.mad.screenagent.data.prefs.AppPreferences
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface OnDeviceModelSource {
    suspend fun getInstalledModel(modelId: String): InstalledOnDeviceModel?
    suspend fun markModelUnavailable(modelId: String, reason: String) {}
}

class OnDeviceModelRepository(
    private val prefs: AppPreferences,
    private val context: Context,
    private val client: OkHttpClient
) : OnDeviceModelSource {
    val catalogFlow: Flow<OnDeviceModelCatalog> = prefs.onDeviceModelCatalogFlow
    val installedModelsFlow: Flow<List<InstalledOnDeviceModel>> = prefs.installedOnDeviceModelsFlow
    val downloadStateFlow: Flow<OnDeviceDownloadState> = prefs.onDeviceDownloadStateFlow
    val libraryFlow: Flow<List<OnDeviceModelLibraryItem>> = combine(
        catalogFlow,
        installedModelsFlow
    ) { catalog, installed ->
        catalog.models
            .filter { it.isPublicPlugAndPlay }
            .map { entry -> entry.toLibraryItem(installed) }
    }
    val allLibraryFlow: Flow<List<OnDeviceModelLibraryItem>> = combine(
        catalogFlow,
        installedModelsFlow
    ) { catalog, installed ->
        catalog.models.map { entry -> entry.toLibraryItem(installed) }
    }
    val nonPublicLibraryFlow: Flow<List<OnDeviceModelLibraryItem>> = combine(
        catalogFlow,
        installedModelsFlow
    ) { catalog, installed ->
        catalog.models
            .filterNot { it.isPublicPlugAndPlay }
            .map { entry -> entry.toLibraryItem(installed) }
    }

    suspend fun ensureDefaultCatalog(): OnDeviceModelCatalog {
        val current = catalogFlow.first()
        if (current.models.isNotEmpty()) return current
        val seeded = OnDeviceModelCatalog(
            models = defaultOnDeviceCatalogEntries()
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

    override suspend fun markModelUnavailable(modelId: String, reason: String) {
        val current = installedModelsFlow.first().toMutableList()
        val idx = current.indexOfFirst { it.modelId == modelId }
        if (idx < 0) return
        val existing = current[idx]
        current[idx] = existing.copy(
            downloadState = OnDeviceDownloadState.UNAVAILABLE,
            errorMessage = reason
        )
        prefs.saveInstalledOnDeviceModels(current)
        saveDownloadState(OnDeviceDownloadState.UNAVAILABLE)
    }

    suspend fun saveInstalledModel(
        modelId: String,
        localPath: String,
        downloadState: OnDeviceDownloadState = OnDeviceDownloadState.READY,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        errorMessage: String? = null
    ): InstalledOnDeviceModel {
        val current = installedModelsFlow.first().toMutableList()
        val existing = current.firstOrNull { it.modelId == modelId }
        val updated = InstalledOnDeviceModel(
            modelId = modelId,
            localPath = localPath,
            downloadState = downloadState,
            installedAt = existing?.installedAt ?: System.currentTimeMillis(),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            errorMessage = errorMessage
        )
        val idx = current.indexOfFirst { it.modelId == modelId }
        if (idx >= 0) current[idx] = updated else current.add(updated)
        prefs.saveInstalledOnDeviceModels(current)
        return updated
    }

    private suspend fun updateInstalledModelProgress(
        modelId: String,
        localPath: String,
        downloadState: OnDeviceDownloadState,
        downloadedBytes: Long,
        totalBytes: Long
    ): InstalledOnDeviceModel = saveInstalledModel(
        modelId = modelId,
        localPath = localPath,
        downloadState = downloadState,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes
    )

    suspend fun enqueueDownload(modelId: String) {
        val catalogEntry = catalogFlow.first().models.firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown on-device model: $modelId")
        saveInstalledModel(
            modelId = modelId,
            localPath = resolveTargetFile(catalogEntry).absolutePath,
            downloadState = OnDeviceDownloadState.DOWNLOADING
        )
        val request = OneTimeWorkRequestBuilder<com.mad.screenagent.data.repository.OnDeviceModelDownloadWorker>()
            .setInputData(
                androidx.work.workDataOf(
                    OnDeviceModelDownloadWorker.KEY_MODEL_ID to modelId
                )
            )
            .addTag(downloadWorkTag(modelId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            downloadWorkName(modelId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    suspend fun cancelDownload(modelId: String, reason: String = "Download cancelled") {
        markDownloadCancelled(modelId, reason)
        WorkManager.getInstance(context).cancelUniqueWork(downloadWorkName(modelId))
    }

    suspend fun downloadModel(
        modelId: String,
        onProgress: suspend (
            state: OnDeviceDownloadState,
            downloadedBytes: Long,
            totalBytes: Long,
            localPath: String
        ) -> Unit = { _, _, _, _ -> }
    ): InstalledOnDeviceModel = withContext(Dispatchers.IO) {
        val catalogEntry = catalogFlow.first().models.firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown on-device model: $modelId")
        val downloadUrl = catalogEntry.downloadUrl.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No download URL is configured for $modelId")

        val targetFile = resolveTargetFile(catalogEntry)
        val downloadFile = File(targetFile.parentFile, "${targetFile.name}.download")
        targetFile.parentFile?.mkdirs()

        saveDownloadState(OnDeviceDownloadState.DOWNLOADING)
        saveInstalledModel(
            modelId = modelId,
            localPath = targetFile.absolutePath,
            downloadState = OnDeviceDownloadState.DOWNLOADING
        )
        onProgress(
            OnDeviceDownloadState.DOWNLOADING,
            0L,
            0L,
            targetFile.absolutePath
        )

        var downloadedBytes = 0L
        var totalBytes = 0L
        try {
            if (targetFile.exists() && targetFile.length() > 0L) {
                totalBytes = targetFile.length()
                downloadedBytes = totalBytes
                saveDownloadState(OnDeviceDownloadState.VALIDATING)
                saveInstalledModel(
                    modelId = modelId,
                    localPath = targetFile.absolutePath,
                    downloadState = OnDeviceDownloadState.VALIDATING,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes
                )
                onProgress(
                    OnDeviceDownloadState.VALIDATING,
                    downloadedBytes,
                    totalBytes,
                    targetFile.absolutePath
                )
                if (!validateDownloadedModel(targetFile)) {
                    throw IOException("Downloaded file failed validation for $modelId")
                }
                val readyRecord = saveInstalledModel(
                    modelId = modelId,
                    localPath = targetFile.absolutePath,
                    downloadState = OnDeviceDownloadState.READY,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes
                )
                saveDownloadState(OnDeviceDownloadState.READY)
                onProgress(
                    OnDeviceDownloadState.READY,
                    downloadedBytes,
                    totalBytes,
                    targetFile.absolutePath
                )
                return@withContext readyRecord
            }

            val request = Request.Builder().url(downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        downgradeCatalogEntryAccess(modelId, OnDeviceModelAccessState.GATED)
                    }
                    throw IOException("Download failed for $modelId: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty model download response for $modelId")
                totalBytes = body.contentLength().takeIf { it > 0L } ?: 0L
                val progressBuffer = ByteArray(DEFAULT_DOWNLOAD_BUFFER_SIZE)
                var lastReportedBytes = 0L
                downloadFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(progressBuffer)
                            if (read <= 0) break
                            output.write(progressBuffer, 0, read)
                            downloadedBytes += read
                            val shouldReport = totalBytes <= 0L ||
                                downloadedBytes - lastReportedBytes >= PROGRESS_UPDATE_STEP ||
                                downloadedBytes == totalBytes
                            if (shouldReport) {
                                lastReportedBytes = downloadedBytes
                                updateInstalledModelProgress(
                                    modelId = modelId,
                                    localPath = targetFile.absolutePath,
                                    downloadState = OnDeviceDownloadState.DOWNLOADING,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes
                                )
                                onProgress(
                                    OnDeviceDownloadState.DOWNLOADING,
                                    downloadedBytes,
                                    totalBytes,
                                    targetFile.absolutePath
                                )
                            }
                        }
                    }
                }
            }

            if (downloadFile.exists() && downloadFile.length() > 0L) {
                if (targetFile.exists()) targetFile.delete()
                if (!downloadFile.renameTo(targetFile)) {
                    downloadFile.copyTo(targetFile, overwrite = true)
                    downloadFile.delete()
                }
            }

            saveDownloadState(OnDeviceDownloadState.VALIDATING)
            saveInstalledModel(
                modelId = modelId,
                localPath = targetFile.absolutePath,
                downloadState = OnDeviceDownloadState.VALIDATING,
                downloadedBytes = targetFile.length(),
                totalBytes = targetFile.length()
            )
            onProgress(
                OnDeviceDownloadState.VALIDATING,
                targetFile.length(),
                targetFile.length(),
                targetFile.absolutePath
            )

            if (!validateDownloadedModel(targetFile)) {
                throw IOException("Downloaded file is missing for $modelId")
            }

            val readyRecord = saveInstalledModel(
                modelId = modelId,
                localPath = targetFile.absolutePath,
                downloadState = OnDeviceDownloadState.READY,
                downloadedBytes = targetFile.length(),
                totalBytes = targetFile.length()
            )
            saveDownloadState(OnDeviceDownloadState.READY)
            onProgress(
                OnDeviceDownloadState.READY,
                targetFile.length(),
                targetFile.length(),
                targetFile.absolutePath
            )
            readyRecord
        } catch (cancellation: CancellationException) {
            downloadFile.delete()
            targetFile.delete()
            markDownloadCancelled(
                modelId = modelId,
                reason = cancellation.message ?: "Download cancelled",
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                localPath = targetFile.absolutePath
            )
            throw cancellation
        } catch (t: Throwable) {
            downloadFile.delete()
            targetFile.delete()
            saveInstalledModel(
                modelId = modelId,
                localPath = targetFile.absolutePath,
                downloadState = OnDeviceDownloadState.FAILED,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                errorMessage = t.message ?: "On-device model download failed"
            )
            saveDownloadState(OnDeviceDownloadState.FAILED)
            throw t
        }
    }

    suspend fun deleteInstalledModel(modelId: String) {
        val current = installedModelsFlow.first().toMutableList()
        val record = current.firstOrNull { it.modelId == modelId }
        record?.let { File(it.localPath).delete() }
        current.removeAll { it.modelId == modelId }
        prefs.saveInstalledOnDeviceModels(current)
    }

    suspend fun saveDownloadState(state: OnDeviceDownloadState) {
        prefs.saveOnDeviceDownloadState(state)
    }

    fun modelFilePath(modelId: String): String =
        "${context.filesDir.absolutePath}/on-device/$modelId"

    private suspend fun downgradeCatalogEntryAccess(
        modelId: String,
        accessState: OnDeviceModelAccessState
    ) {
        val catalog = catalogFlow.first()
        val updated = catalog.copy(
            models = catalog.models.map { entry ->
                if (entry.id == modelId) {
                    entry.copy(accessStateKey = accessState.name)
                } else {
                    entry
                }
            }
        )
        prefs.saveOnDeviceModelCatalog(updated)
    }

    private fun validateDownloadedModel(file: File): Boolean =
        file.exists() && file.length() > 0L

    private fun resolveTargetFile(entry: OnDeviceModelCatalogEntry): File {
        val baseDir = File(context.filesDir, "on-device/${entry.id}")
        val fileName = entry.fileName.ifBlank { "${entry.id}.task" }
        return File(baseDir, fileName)
    }

    private suspend fun markDownloadCancelled(
        modelId: String,
        reason: String,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        localPath: String = modelFilePath(modelId)
    ) = withContext(NonCancellable) {
        val current = installedModelsFlow.first().toMutableList()
        val idx = current.indexOfFirst { it.modelId == modelId }
        val updated = InstalledOnDeviceModel(
            modelId = modelId,
            localPath = localPath,
            downloadState = OnDeviceDownloadState.CANCELLED,
            installedAt = current.getOrNull(idx)?.installedAt ?: System.currentTimeMillis(),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            errorMessage = reason
        )
        if (idx >= 0) current[idx] = updated else current.add(updated)
        prefs.saveInstalledOnDeviceModels(current)
        saveDownloadState(OnDeviceDownloadState.CANCELLED)
    }

    private fun downloadWorkName(modelId: String) = "on-device-download-$modelId"

    private fun downloadWorkTag(modelId: String) = "on-device-download-tag-$modelId"

    companion object {
        private const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_UPDATE_STEP = 512 * 1024L
    }

    private fun OnDeviceModelCatalogEntry.toLibraryItem(
        installed: List<InstalledOnDeviceModel>
    ): OnDeviceModelLibraryItem = OnDeviceModelLibraryItem(
        catalogEntry = this,
        installRecord = installed.firstOrNull { it.modelId == id }
    )
}
