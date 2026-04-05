package com.mad.screenagent.data.model

private const val DEEPSEEK_1_5B_MODEL_ID = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B"
private const val DEEPSEEK_1_5B_MODEL_FILE = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.task"
private const val QWEN_1_5B_MODEL_ID = "litert-community/Qwen2.5-1.5B-Instruct"
private const val QWEN_1_5B_MODEL_FILE = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
private const val PHI_4_MINI_MODEL_ID = "litert-community/Phi-4-mini-instruct"
private const val PHI_4_MINI_MODEL_FILE = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task"

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
        get() = this == READY || this == DOWNLOADED

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

data class OnDeviceModelCatalogEntry(
    val id: String,
    val displayName: String = id,
    val description: String = "",
    val downloadUrl: String = "",
    val fileName: String = "",
    val capabilityKey: String? = null,
    val supportsDocuments: Boolean = true,
    val estimatedSizeMb: Int = 0,
    val accessStateKey: String? = null
) {
    val accessState: OnDeviceModelAccessState
        get() = OnDeviceModelAccessState.fromKey(accessStateKey)

    val capability: OnDeviceModelCapability
        get() = OnDeviceModelCapability.fromKey(capabilityKey)

    val isPublicPlugAndPlay: Boolean
        get() = accessState == OnDeviceModelAccessState.PUBLIC

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
        id = "deepseek-r1-distill-qwen-1.5b",
        displayName = "DeepSeek-R1-Distill-Qwen-1.5B",
        description = "Public on-device text model for general chat.",
        downloadUrl = "https://huggingface.co/$DEEPSEEK_1_5B_MODEL_ID/resolve/main/$DEEPSEEK_1_5B_MODEL_FILE",
        fileName = DEEPSEEK_1_5B_MODEL_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 2600,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name
    ),
    OnDeviceModelCatalogEntry(
        id = "qwen2.5-1.5b-instruct",
        displayName = "Qwen2.5-1.5B-Instruct",
        description = "Public on-device text model with strong instruction following.",
        downloadUrl = "https://huggingface.co/$QWEN_1_5B_MODEL_ID/resolve/main/$QWEN_1_5B_MODEL_FILE",
        fileName = QWEN_1_5B_MODEL_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 1500,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name
    ),
    OnDeviceModelCatalogEntry(
        id = "phi-4-mini-instruct",
        displayName = "Phi-4-mini-instruct",
        description = "Public compact on-device text model.",
        downloadUrl = "https://huggingface.co/$PHI_4_MINI_MODEL_ID/resolve/main/$PHI_4_MINI_MODEL_FILE",
        fileName = PHI_4_MINI_MODEL_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 3940,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name
    )
)

fun defaultOnDeviceCatalogEntries(): List<OnDeviceModelCatalogEntry> =
    allOnDeviceCatalogEntries().filter { it.isPublicPlugAndPlay }

fun nonPublicOnDeviceCatalogEntries(): List<OnDeviceModelCatalogEntry> =
    allOnDeviceCatalogEntries().filterNot { it.isPublicPlugAndPlay }

fun normalizeOnDeviceCatalog(catalog: OnDeviceModelCatalog): OnDeviceModelCatalog {
    val canonicalById = allOnDeviceCatalogEntries().associateBy { it.id }
    return catalog.copy(
        models = catalog.models.map { entry ->
            val canonical = canonicalById[entry.id] ?: return@map entry
            entry.copy(
                displayName = entry.displayName.ifBlank { canonical.displayName },
                description = entry.description.ifBlank { canonical.description },
                downloadUrl = entry.downloadUrl.ifBlank { canonical.downloadUrl },
                fileName = entry.fileName.ifBlank { canonical.fileName },
                capabilityKey = entry.capabilityKey ?: canonical.capabilityKey,
                supportsDocuments = entry.supportsDocuments || canonical.supportsDocuments,
                estimatedSizeMb = if (entry.estimatedSizeMb > 0) entry.estimatedSizeMb else canonical.estimatedSizeMb,
                accessStateKey = entry.accessStateKey ?: canonical.accessStateKey
            )
        }
    )
}
