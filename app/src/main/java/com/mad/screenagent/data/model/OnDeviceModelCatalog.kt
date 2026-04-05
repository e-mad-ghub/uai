package com.mad.screenagent.data.model

enum class OnDeviceDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

data class OnDeviceModelCatalogEntry(
    val id: String,
    val displayName: String = id,
    val description: String = "",
    val downloadUrl: String = "",
    val fileName: String = "",
    val supportsVision: Boolean = false,
    val supportsDocuments: Boolean = true,
    val estimatedSizeMb: Int = 0
)

data class OnDeviceModelCatalog(
    val fetchedAt: Long = 0L,
    val models: List<OnDeviceModelCatalogEntry> = emptyList()
)

data class InstalledOnDeviceModel(
    val modelId: String,
    val localPath: String,
    val downloadState: OnDeviceDownloadState = OnDeviceDownloadState.NOT_DOWNLOADED,
    val installedAt: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
)

data class OnDeviceModelLibraryItem(
    val catalogEntry: OnDeviceModelCatalogEntry,
    val installRecord: InstalledOnDeviceModel? = null
)
