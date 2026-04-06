package com.mad.screenagent.data.model

private const val GEMMA_3_1B_IT_GGUF_REPO = "ggml-org/gemma-3-1b-it-GGUF"
private const val GEMMA_3_1B_IT_GGUF_FILE = "gemma-3-1b-it-Q4_K_M.gguf"
private const val GEMMA_3_4B_IT_GGUF_REPO = "ggml-org/gemma-3-4b-it-GGUF"
private const val GEMMA_3_4B_IT_GGUF_FILE = "gemma-3-4b-it-Q4_K_M.gguf"

private val LEGACY_ON_DEVICE_IDS = setOf(
    "deepseek-r1-distill-qwen-1.5b",
    "qwen2.5-1.5b-instruct",
    "phi-4-mini-instruct"
)

enum class OnDeviceDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    VALIDATING,
    READY,
    DOWNLOADED,
    FAILED,
    CANCELLED,
    UNAVAILABLE;

    val isReadyForUse: Boolean
        get() = this == READY

    val isInProgress: Boolean
        get() = this == DOWNLOADING || this == VALIDATING

    val isTerminalFailure: Boolean
        get() = this == FAILED || this == CANCELLED || this == UNAVAILABLE
}

enum class OnDeviceModelAccessState {
    PUBLIC,
    GATED,
    UNKNOWN,
    EXTERNAL;

    companion object {
        fun fromKey(key: String?): OnDeviceModelAccessState =
            values().firstOrNull { it.name.equals(key, ignoreCase = true) } ?: UNKNOWN
    }
}

enum class OnDeviceModelCapability {
    LOCAL_TEXT,
    LOCAL_VISION,
    LOCAL_TOOL_USE;

    companion object {
        fun fromKey(key: String?): OnDeviceModelCapability =
            values().firstOrNull { it.name.equals(key, ignoreCase = true) } ?: LOCAL_TEXT
    }
}

enum class OnDeviceModelSourceType {
    CATALOG,
    IMPORTED;

    companion object {
        fun fromKey(key: String?): OnDeviceModelSourceType =
            values().firstOrNull { it.name.equals(key, ignoreCase = true) } ?: CATALOG
    }
}

data class OnDeviceModelCatalogEntry(
    val id: String,
    val displayName: String = id,
    val description: String = "",
    val downloadUrl: String = "",
    val fileName: String = "",
    val capabilityKey: String? = null,
    val supportsDocuments: Boolean = true,
    val estimatedSizeMb: Int = 0,
    val accessStateKey: String? = null,
    val sourceTypeKey: String? = null
) {
    val accessState: OnDeviceModelAccessState
        get() = OnDeviceModelAccessState.fromKey(accessStateKey)

    val capability: OnDeviceModelCapability
        get() = OnDeviceModelCapability.fromKey(capabilityKey)

    val sourceType: OnDeviceModelSourceType
        get() = OnDeviceModelSourceType.fromKey(sourceTypeKey)

    val isPublicPlugAndPlay: Boolean
        get() = accessState == OnDeviceModelAccessState.PUBLIC

    val isCatalogDownload: Boolean
        get() = sourceType == OnDeviceModelSourceType.CATALOG &&
            isPublicPlugAndPlay &&
            downloadUrl.isNotBlank()

    val isImported: Boolean
        get() = sourceType == OnDeviceModelSourceType.IMPORTED

    val supportsVision: Boolean
        get() = capability == OnDeviceModelCapability.LOCAL_VISION
}

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

fun allOnDeviceCatalogEntries(): List<OnDeviceModelCatalogEntry> = listOf(
    OnDeviceModelCatalogEntry(
        id = "gemma-3-1b-it-gguf",
        displayName = "Gemma 3 1B IT",
        description = "Public GGUF starter model for local text chat.",
        downloadUrl = "https://huggingface.co/$GEMMA_3_1B_IT_GGUF_REPO/resolve/main/$GEMMA_3_1B_IT_GGUF_FILE",
        fileName = GEMMA_3_1B_IT_GGUF_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 815,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
        sourceTypeKey = OnDeviceModelSourceType.CATALOG.name
    ),
    OnDeviceModelCatalogEntry(
        id = "gemma-3-4b-it-gguf",
        displayName = "Gemma 3 4B IT",
        description = "Larger public GGUF model with better local text quality on stronger phones.",
        downloadUrl = "https://huggingface.co/$GEMMA_3_4B_IT_GGUF_REPO/resolve/main/$GEMMA_3_4B_IT_GGUF_FILE",
        fileName = GEMMA_3_4B_IT_GGUF_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 3115,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
        sourceTypeKey = OnDeviceModelSourceType.CATALOG.name
    )
)

fun defaultOnDeviceCatalogEntries(): List<OnDeviceModelCatalogEntry> =
    allOnDeviceCatalogEntries().filter { it.isCatalogDownload }

fun nonPublicOnDeviceCatalogEntries(): List<OnDeviceModelCatalogEntry> =
    allOnDeviceCatalogEntries().filterNot { it.isCatalogDownload }

fun normalizeOnDeviceCatalog(catalog: OnDeviceModelCatalog): OnDeviceModelCatalog {
    val canonicalById = allOnDeviceCatalogEntries().associateBy { it.id }
    val normalized = buildList {
        val seen = mutableSetOf<String>()

        catalog.models.forEach { entry ->
            if (entry.id in LEGACY_ON_DEVICE_IDS && !entry.isImported) return@forEach

            val canonical = canonicalById[entry.id]
            val merged = when {
                entry.isImported -> entry.copy(
                    sourceTypeKey = OnDeviceModelSourceType.IMPORTED.name,
                    capabilityKey = entry.capabilityKey ?: OnDeviceModelCapability.LOCAL_TEXT.name,
                    accessStateKey = entry.accessStateKey ?: OnDeviceModelAccessState.EXTERNAL.name
                )
                canonical != null -> entry.copy(
                    displayName = entry.displayName.ifBlank { canonical.displayName },
                    description = entry.description.ifBlank { canonical.description },
                    downloadUrl = entry.downloadUrl.ifBlank { canonical.downloadUrl },
                    fileName = entry.fileName.ifBlank { canonical.fileName },
                    capabilityKey = entry.capabilityKey ?: canonical.capabilityKey,
                    supportsDocuments = entry.supportsDocuments || canonical.supportsDocuments,
                    estimatedSizeMb = if (entry.estimatedSizeMb > 0) entry.estimatedSizeMb else canonical.estimatedSizeMb,
                    accessStateKey = entry.accessStateKey ?: canonical.accessStateKey,
                    sourceTypeKey = OnDeviceModelSourceType.CATALOG.name
                )
                else -> entry
            }
            if (seen.add(merged.id)) add(merged)
        }

        allOnDeviceCatalogEntries().forEach { canonical ->
            if (seen.add(canonical.id)) add(canonical)
        }
    }
    return catalog.copy(models = normalized)
}
