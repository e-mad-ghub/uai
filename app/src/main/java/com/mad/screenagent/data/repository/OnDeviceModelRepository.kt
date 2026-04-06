package com.mad.screenagent.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.arm.aichat.gguf.GgufMetadataReader
import com.arm.aichat.gguf.InvalidFileFormatException
import com.mad.screenagent.data.model.InstalledOnDeviceModel
import com.mad.screenagent.data.model.OnDeviceFailureKind
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceModelAccessState
import com.mad.screenagent.data.model.OnDeviceModelCatalog
import com.mad.screenagent.data.model.OnDeviceModelCatalogEntry
import com.mad.screenagent.data.model.OnDeviceModelLibraryItem
import com.mad.screenagent.data.model.OnDeviceModelSourceType
import com.mad.screenagent.data.model.defaultOnDeviceCatalogEntries
import com.mad.screenagent.data.model.normalizeOnDeviceCatalog
import com.mad.screenagent.data.prefs.AppPreferences
import com.mad.screenagent.shared.streaming.OnDeviceRuntime
import com.mad.screenagent.shared.streaming.OnDeviceValidationResult
import java.io.File
import java.io.IOException
import java.util.UUID
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
    suspend fun markModelUnavailable(
        modelId: String,
        reason: String,
        failureKind: OnDeviceFailureKind = OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE
    ) {}
}

class OnDeviceModelRepository(
    private val prefs: AppPreferences,
    private val context: Context,
    private val client: OkHttpClient,
    private val runtime: OnDeviceRuntime
) : OnDeviceModelSource {
    private val ggufMetadataReader = GgufMetadataReader.create()

    val catalogFlow: Flow<OnDeviceModelCatalog> = prefs.onDeviceModelCatalogFlow
    val installedModelsFlow: Flow<List<InstalledOnDeviceModel>> = prefs.installedOnDeviceModelsFlow
    val downloadStateFlow: Flow<OnDeviceDownloadState> = prefs.onDeviceDownloadStateFlow
    val libraryFlow: Flow<List<OnDeviceModelLibraryItem>> = combine(
        catalogFlow,
        installedModelsFlow
    ) { catalog, installed ->
        catalog.models
            .filter { it.isCatalogDownload }
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
            .filter { !it.isCatalogDownload && !it.isImported }
            .map { entry -> entry.toLibraryItem(installed) }
    }
    val importedLibraryFlow: Flow<List<OnDeviceModelLibraryItem>> = combine(
        catalogFlow,
        installedModelsFlow
    ) { catalog, installed ->
        catalog.models
            .filter { it.isImported }
            .map { entry -> entry.toLibraryItem(installed) }
    }

    suspend fun ensureDefaultCatalog(): OnDeviceModelCatalog {
        val current = catalogFlow.first()
        val seeded = if (current.models.isEmpty()) {
            OnDeviceModelCatalog(models = defaultOnDeviceCatalogEntries())
        } else {
            current
        }
        val normalized = normalizeOnDeviceCatalog(seeded)
        if (normalized != current) {
            prefs.saveOnDeviceModelCatalog(normalized)
        }
        return normalized
    }

    suspend fun refreshCatalogIfStale(force: Boolean = false): OnDeviceModelCatalog {
        return ensureDefaultCatalog()
    }

    suspend fun getInstalledModels(): List<InstalledOnDeviceModel> =
        installedModelsFlow.first()

    override suspend fun getInstalledModel(modelId: String): InstalledOnDeviceModel? =
        installedModelsFlow.first().firstOrNull { it.modelId == modelId }

    override suspend fun markModelUnavailable(
        modelId: String,
        reason: String,
        failureKind: OnDeviceFailureKind
    ) {
        val current = installedModelsFlow.first().toMutableList()
        val idx = current.indexOfFirst { it.modelId == modelId }
        if (idx < 0) return
        val existing = current[idx]
        current[idx] = existing.copy(
            downloadState = OnDeviceDownloadState.UNAVAILABLE,
            errorMessage = reason,
            failureKindKey = failureKind.name,
            validatedAt = System.currentTimeMillis(),
            validatedRuntimeProfileId = runtime.runtimeProfileId
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
        errorMessage: String? = null,
        failureKind: OnDeviceFailureKind = OnDeviceFailureKind.NONE,
        validatedAt: Long = 0L,
        validatedRuntimeProfileId: String? = null
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
            errorMessage = errorMessage,
            failureKindKey = failureKind.name,
            validatedAt = validatedAt,
            validatedRuntimeProfileId = validatedRuntimeProfileId
        )
        val idx = current.indexOfFirst { it.modelId == modelId }
        if (idx >= 0) current[idx] = updated else current.add(updated)
        prefs.saveInstalledOnDeviceModels(current)
        return updated
    }

    suspend fun importModel(uri: Uri): InstalledOnDeviceModel = withContext(Dispatchers.IO) {
        val fileName = resolveImportFileName(uri)
        require(fileName.endsWith(".gguf", ignoreCase = true)) {
            "Only GGUF model files can be imported."
        }

        val modelId = "imported-${UUID.randomUUID()}"
        val targetEntry = OnDeviceModelCatalogEntry(
            id = modelId,
            displayName = fileName.removeSuffix(".gguf"),
            description = "Imported GGUF model",
            fileName = fileName,
            capabilityKey = com.mad.screenagent.data.model.OnDeviceModelCapability.LOCAL_TEXT.name,
            accessStateKey = OnDeviceModelAccessState.EXTERNAL.name,
            sourceTypeKey = OnDeviceModelSourceType.IMPORTED.name
        )
        val targetFile = resolveTargetFile(targetEntry)
        targetFile.parentFile?.mkdirs()

        saveDownloadState(OnDeviceDownloadState.VALIDATING)
        saveInstalledModel(
            modelId = modelId,
            localPath = targetFile.absolutePath,
            downloadState = OnDeviceDownloadState.VALIDATING
        )

        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Unable to read the selected GGUF file.")

        val validationResult = validateDownloadedModel(targetFile)
        if (!validationResult.isSuccess) {
            targetFile.delete()
            saveInstalledModel(
                modelId = modelId,
                localPath = targetFile.absolutePath,
                downloadState = OnDeviceDownloadState.FAILED,
                errorMessage = validationResult.message,
                failureKind = validationResult.failureKind,
                validatedAt = System.currentTimeMillis(),
                validatedRuntimeProfileId = runtime.runtimeProfileId
            )
            saveDownloadState(OnDeviceDownloadState.FAILED)
            throw IOException(validationResult.message)
        }

        upsertCatalogEntry(
            targetEntry.copy(
                estimatedSizeMb = (targetFile.length() / (1024L * 1024L)).toInt().coerceAtLeast(1)
            )
        )

        val readyRecord = saveInstalledModel(
            modelId = modelId,
            localPath = targetFile.absolutePath,
            downloadState = OnDeviceDownloadState.READY,
            downloadedBytes = targetFile.length(),
            totalBytes = targetFile.length(),
            failureKind = OnDeviceFailureKind.NONE,
            validatedAt = System.currentTimeMillis(),
            validatedRuntimeProfileId = runtime.runtimeProfileId
        )
        saveDownloadState(OnDeviceDownloadState.READY)
        readyRecord
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
        val existingInstall = getInstalledModel(modelId)
        val downloadUrl = catalogEntry.downloadUrl.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No download URL is configured for $modelId")

        val targetFile = resolveTargetFile(catalogEntry)
        val downloadFile = File(targetFile.parentFile, "${targetFile.name}.download")
        targetFile.parentFile?.mkdirs()

        if (shouldDiscardExistingArtifact(existingInstall, targetFile, downloadFile)) {
            downloadFile.delete()
            targetFile.delete()
            saveInstalledModel(
                modelId = modelId,
                localPath = targetFile.absolutePath,
                downloadState = OnDeviceDownloadState.NOT_DOWNLOADED,
                downloadedBytes = 0L,
                totalBytes = 0L,
                errorMessage = null,
                failureKind = OnDeviceFailureKind.NONE
            )
        }

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
            if (existingInstall?.downloadState == OnDeviceDownloadState.READY && targetFile.exists() && targetFile.length() > 0L) {
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
                val validationResult = validateDownloadedModel(targetFile)
                if (!validationResult.isSuccess) {
                    throw ModelValidationException(
                        message = validationResult.message ?: "GGUF validation failed.",
                        failureKind = validationResult.failureKind
                    )
                }
                val readyRecord = saveInstalledModel(
                    modelId = modelId,
                    localPath = targetFile.absolutePath,
                    downloadState = OnDeviceDownloadState.READY,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    failureKind = OnDeviceFailureKind.NONE,
                    validatedAt = System.currentTimeMillis(),
                    validatedRuntimeProfileId = runtime.runtimeProfileId
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

            val validationResult = validateDownloadedModel(targetFile)
            if (!validationResult.isSuccess) {
                saveInstalledModel(
                    modelId = modelId,
                    localPath = targetFile.absolutePath,
                    downloadState = OnDeviceDownloadState.FAILED,
                    downloadedBytes = targetFile.length(),
                    totalBytes = targetFile.length(),
                    errorMessage = validationResult.message,
                    failureKind = validationResult.failureKind,
                    validatedAt = System.currentTimeMillis(),
                    validatedRuntimeProfileId = runtime.runtimeProfileId
                )
                throw ModelValidationException(
                    message = validationResult.message ?: "GGUF validation failed.",
                    failureKind = validationResult.failureKind
                )
            }

            val readyRecord = saveInstalledModel(
                modelId = modelId,
                localPath = targetFile.absolutePath,
                downloadState = OnDeviceDownloadState.READY,
                downloadedBytes = targetFile.length(),
                totalBytes = targetFile.length(),
                failureKind = OnDeviceFailureKind.NONE,
                validatedAt = System.currentTimeMillis(),
                validatedRuntimeProfileId = runtime.runtimeProfileId
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
                errorMessage = t.message ?: "On-device model download failed",
                failureKind = if (t is ModelValidationException) {
                    t.failureKind
                } else if (t.message?.contains("HTTP", ignoreCase = true) == true) {
                    OnDeviceFailureKind.DOWNLOAD
                } else {
                    OnDeviceFailureKind.INTERNAL_RUNTIME_ERROR
                },
                validatedAt = System.currentTimeMillis(),
                validatedRuntimeProfileId = runtime.runtimeProfileId
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
        val catalog = catalogFlow.first()
        val entry = catalog.models.firstOrNull { it.id == modelId }
        if (entry?.isImported == true) {
            prefs.saveOnDeviceModelCatalog(
                catalog.copy(models = catalog.models.filterNot { it.id == modelId })
            )
        }
    }

    suspend fun saveDownloadState(state: OnDeviceDownloadState) {
        prefs.saveOnDeviceDownloadState(state)
    }

    fun modelFilePath(modelId: String): String =
        "${context.filesDir.absolutePath}/on-device/$modelId/$modelId.gguf"

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

    private suspend fun validateDownloadedModel(file: File): OnDeviceValidationResult {
        if (!file.exists() || file.length() == 0L) {
            return OnDeviceValidationResult.failure(
                OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE,
                "The GGUF model file is missing or empty."
            )
        }
        if (!file.name.endsWith(".gguf", ignoreCase = true)) {
            return OnDeviceValidationResult.failure(
                OnDeviceFailureKind.INVALID_GGUF,
                "Only GGUF model files are supported."
            )
        }
        return try {
            val valid = ggufMetadataReader.ensureSourceFileFormat(file)
            if (!valid) {
                OnDeviceValidationResult.failure(
                    OnDeviceFailureKind.INVALID_GGUF,
                    "The selected file is not a valid GGUF model."
                )
            } else {
                runtime.validateModel(file.absolutePath)
            }
        } catch (_: InvalidFileFormatException) {
            OnDeviceValidationResult.failure(
                OnDeviceFailureKind.INVALID_GGUF,
                "The selected file is not a valid GGUF model."
            )
        } catch (t: Throwable) {
            OnDeviceValidationResult.failure(
                OnDeviceFailureKind.INTERNAL_RUNTIME_ERROR,
                t.message ?: "GGUF validation failed."
            )
        }
    }

    internal fun shouldDiscardExistingArtifact(
        existingInstall: InstalledOnDeviceModel?,
        targetFile: File,
        downloadFile: File
    ): Boolean {
        if (downloadFile.exists()) return true
        if (!targetFile.exists()) return false
        if (targetFile.length() == 0L) return true
        if (existingInstall == null) return true
        if (existingInstall.localPath != targetFile.absolutePath) return true
        if (existingInstall.downloadState != OnDeviceDownloadState.READY) return true
        if (existingInstall.totalBytes > 0L && targetFile.length() != existingInstall.totalBytes) return true
        if (existingInstall.downloadedBytes > 0L && targetFile.length() != existingInstall.downloadedBytes) return true
        return false
    }

    private fun resolveTargetFile(entry: OnDeviceModelCatalogEntry): File {
        val baseDir = File(context.filesDir, "on-device/${entry.id}")
        val fileName = entry.fileName.ifBlank { "${entry.id}.gguf" }
        return File(baseDir, fileName)
    }

    private suspend fun upsertCatalogEntry(entry: OnDeviceModelCatalogEntry) {
        val catalog = catalogFlow.first()
        val updated = normalizeOnDeviceCatalog(
            catalog.copy(
                models = catalog.models
                    .filterNot { it.id == entry.id }
                    .plus(entry)
            )
        )
        prefs.saveOnDeviceModelCatalog(updated)
    }

    private fun resolveImportFileName(uri: Uri): String {
        val nameFromCursor = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        val raw = nameFromCursor?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "imported-model.gguf"
        val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "-")
        return if (sanitized.endsWith(".gguf", ignoreCase = true)) sanitized else "$sanitized.gguf"
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
            errorMessage = reason,
            failureKindKey = OnDeviceFailureKind.DOWNLOAD.name,
            validatedAt = System.currentTimeMillis(),
            validatedRuntimeProfileId = runtime.runtimeProfileId
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

    private class ModelValidationException(
        message: String,
        val failureKind: OnDeviceFailureKind
    ) : IOException(message)

    private fun OnDeviceModelCatalogEntry.toLibraryItem(
        installed: List<InstalledOnDeviceModel>
    ): OnDeviceModelLibraryItem = OnDeviceModelLibraryItem(
        catalogEntry = this,
        installRecord = installed.firstOrNull { it.modelId == id }
    )
}
