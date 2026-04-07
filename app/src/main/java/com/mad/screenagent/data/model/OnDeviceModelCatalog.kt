package com.mad.screenagent.data.model

private const val GEMMA_3_1B_IT_GGUF_REPO = "ggml-org/gemma-3-1b-it-GGUF"
private const val GEMMA_3_1B_IT_GGUF_FILE = "gemma-3-1b-it-Q4_K_M.gguf"
private const val GEMMA_3_4B_IT_GGUF_REPO = "ggml-org/gemma-3-4b-it-GGUF"
private const val GEMMA_3_4B_IT_GGUF_FILE = "gemma-3-4b-it-Q4_K_M.gguf"
private const val GEMMA_3_4B_IT_MMPROJ_FILE = "mmproj-model-f16.gguf"
private const val SMOLLM2_360M_INSTRUCT_GGUF_REPO = "mradermacher/SmolLM2-360M-Instruct-GGUF"
private const val SMOLLM2_360M_INSTRUCT_GGUF_FILE = "SmolLM2-360M-Instruct.Q5_K_M.gguf"
private const val QWEN2_5_0_5B_INSTRUCT_GGUF_REPO = "Qwen/Qwen2.5-0.5B-Instruct-GGUF"
private const val QWEN2_5_0_5B_INSTRUCT_GGUF_FILE = "qwen2.5-0.5b-instruct-q5_k_m.gguf"
private const val TINYLLAMA_1_1B_CHAT_GGUF_REPO = "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF"
private const val TINYLLAMA_1_1B_CHAT_GGUF_FILE = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"
private const val QWEN2_5_1_5B_INSTRUCT_GGUF_REPO = "bartowski/Qwen2.5-1.5B-Instruct-GGUF"
private const val QWEN2_5_1_5B_INSTRUCT_GGUF_FILE = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"

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

enum class OnDeviceFailureKind {
    NONE,
    DOWNLOAD,
    INVALID_GGUF,
    RUNTIME_INCOMPATIBLE,
    UNAVAILABLE_ON_DEVICE,
    INTERNAL_RUNTIME_ERROR;

    companion object {
        fun fromKey(key: String?): OnDeviceFailureKind =
            values().firstOrNull { it.name.equals(key, ignoreCase = true) } ?: NONE
    }
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
    val visionProjectorDownloadUrl: String = "",
    val visionProjectorFileName: String = "",
    val capabilityKey: String? = null,
    val supportsDocuments: Boolean = true,
    val estimatedSizeMb: Int = 0,
    val recommendedRank: Int = Int.MAX_VALUE,
    val accessStateKey: String? = null,
    val sourceTypeKey: String? = null,
    val runtimeProfileId: String? = null,
    val curatedVerified: Boolean = false
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

    val hasVisionProjector: Boolean
        get() = supportsVision &&
            visionProjectorDownloadUrl.isNotBlank() &&
            visionProjectorFileName.isNotBlank()

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
    val visionProjectorPath: String? = null,
    val downloadState: OnDeviceDownloadState = OnDeviceDownloadState.NOT_DOWNLOADED,
    val installedAt: Long = 0L,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val failureKindKey: String? = null,
    val validatedAt: Long = 0L,
    val validatedRuntimeProfileId: String? = null
) {
    val failureKind: OnDeviceFailureKind
        get() = OnDeviceFailureKind.fromKey(failureKindKey)
}

data class OnDeviceModelLibraryItem(
    val catalogEntry: OnDeviceModelCatalogEntry,
    val installRecord: InstalledOnDeviceModel? = null
)

data class OnDeviceRuntimeCompatibilityRule(
    val modelId: String,
    val fileName: String,
    val runtimeProfileId: String
)

private const val CURATED_GGUF_RUNTIME_PROFILE = "llama.android-af76639-arm64-v8a-kleidiai-openmp"

fun curatedOnDeviceCompatibilityManifest(): List<OnDeviceRuntimeCompatibilityRule> = listOf(
    OnDeviceRuntimeCompatibilityRule(
        modelId = "smollm2-360m-instruct-gguf",
        fileName = SMOLLM2_360M_INSTRUCT_GGUF_FILE,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE
    ),
    OnDeviceRuntimeCompatibilityRule(
        modelId = "qwen2.5-0.5b-instruct-gguf",
        fileName = QWEN2_5_0_5B_INSTRUCT_GGUF_FILE,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE
    ),
    OnDeviceRuntimeCompatibilityRule(
        modelId = "tinyllama-1.1b-chat-gguf",
        fileName = TINYLLAMA_1_1B_CHAT_GGUF_FILE,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE
    ),
    OnDeviceRuntimeCompatibilityRule(
        modelId = "qwen2.5-1.5b-instruct-gguf",
        fileName = QWEN2_5_1_5B_INSTRUCT_GGUF_FILE,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE
    ),
    OnDeviceRuntimeCompatibilityRule(
        modelId = "gemma-3-1b-it-gguf",
        fileName = GEMMA_3_1B_IT_GGUF_FILE,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE
    ),
    OnDeviceRuntimeCompatibilityRule(
        modelId = "gemma-3-4b-it-gguf",
        fileName = GEMMA_3_4B_IT_GGUF_FILE,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE
    )
)

fun allOnDeviceCatalogEntries(): List<OnDeviceModelCatalogEntry> = listOf(
    OnDeviceModelCatalogEntry(
        id = "smollm2-360m-instruct-gguf",
        displayName = "SmolLM2 360M Instruct",
        description = "Very small GGUF model for the fastest local replies on weaker phones.",
        downloadUrl = "https://huggingface.co/$SMOLLM2_360M_INSTRUCT_GGUF_REPO/resolve/main/$SMOLLM2_360M_INSTRUCT_GGUF_FILE",
        fileName = SMOLLM2_360M_INSTRUCT_GGUF_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 277,
        recommendedRank = 0,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
        sourceTypeKey = OnDeviceModelSourceType.CATALOG.name,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE,
        curatedVerified = true
    ),
    OnDeviceModelCatalogEntry(
        id = "qwen2.5-0.5b-instruct-gguf",
        displayName = "Qwen2.5 0.5B Instruct",
        description = "Balanced small GGUF model with better quality than ultra-tiny models.",
        downloadUrl = "https://huggingface.co/$QWEN2_5_0_5B_INSTRUCT_GGUF_REPO/resolve/main/$QWEN2_5_0_5B_INSTRUCT_GGUF_FILE",
        fileName = QWEN2_5_0_5B_INSTRUCT_GGUF_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 498,
        recommendedRank = 1,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
        sourceTypeKey = OnDeviceModelSourceType.CATALOG.name,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE,
        curatedVerified = true
    ),
    OnDeviceModelCatalogEntry(
        id = "tinyllama-1.1b-chat-gguf",
        displayName = "TinyLlama 1.1B Chat",
        description = "Phone-friendly GGUF chat model with low memory pressure.",
        downloadUrl = "https://huggingface.co/$TINYLLAMA_1_1B_CHAT_GGUF_REPO/resolve/main/$TINYLLAMA_1_1B_CHAT_GGUF_FILE",
        fileName = TINYLLAMA_1_1B_CHAT_GGUF_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 638,
        recommendedRank = 2,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
        sourceTypeKey = OnDeviceModelSourceType.CATALOG.name,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE,
        curatedVerified = true
    ),
    OnDeviceModelCatalogEntry(
        id = "qwen2.5-1.5b-instruct-gguf",
        displayName = "Qwen2.5 1.5B Instruct",
        description = "Stronger local text model when you want better quality and can spend more RAM.",
        downloadUrl = "https://huggingface.co/$QWEN2_5_1_5B_INSTRUCT_GGUF_REPO/resolve/main/$QWEN2_5_1_5B_INSTRUCT_GGUF_FILE",
        fileName = QWEN2_5_1_5B_INSTRUCT_GGUF_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 940,
        recommendedRank = 3,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
        sourceTypeKey = OnDeviceModelSourceType.CATALOG.name,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE,
        curatedVerified = true
    ),
    OnDeviceModelCatalogEntry(
        id = "gemma-3-1b-it-gguf",
        displayName = "Gemma 3 1B IT",
        description = "Public GGUF starter model for local text chat.",
        downloadUrl = "https://huggingface.co/$GEMMA_3_1B_IT_GGUF_REPO/resolve/main/$GEMMA_3_1B_IT_GGUF_FILE",
        fileName = GEMMA_3_1B_IT_GGUF_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
        estimatedSizeMb = 815,
        recommendedRank = 4,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
        sourceTypeKey = OnDeviceModelSourceType.CATALOG.name,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE,
        curatedVerified = true
    ),
    OnDeviceModelCatalogEntry(
        id = "gemma-3-4b-it-gguf",
        displayName = "Gemma 3 4B IT",
        description = "Larger public GGUF model with better local text quality on stronger phones.",
        downloadUrl = "https://huggingface.co/$GEMMA_3_4B_IT_GGUF_REPO/resolve/main/$GEMMA_3_4B_IT_GGUF_FILE",
        fileName = GEMMA_3_4B_IT_GGUF_FILE,
        visionProjectorDownloadUrl = "https://huggingface.co/$GEMMA_3_4B_IT_GGUF_REPO/resolve/main/$GEMMA_3_4B_IT_MMPROJ_FILE",
        visionProjectorFileName = GEMMA_3_4B_IT_MMPROJ_FILE,
        capabilityKey = OnDeviceModelCapability.LOCAL_VISION.name,
        estimatedSizeMb = 3115,
        recommendedRank = 5,
        accessStateKey = OnDeviceModelAccessState.PUBLIC.name,
        sourceTypeKey = OnDeviceModelSourceType.CATALOG.name,
        runtimeProfileId = CURATED_GGUF_RUNTIME_PROFILE,
        curatedVerified = true
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
            val entryId = entry.id.orEmpty().trim()
            if (entryId.isBlank()) return@forEach
            if (entryId in LEGACY_ON_DEVICE_IDS && !entry.isImported) return@forEach

            val canonical = canonicalById[entryId]
            val merged = sanitizeOnDeviceCatalogEntry(
                entry = entry,
                canonical = canonical,
                overrideId = entryId,
                forceImported = entry.isImported
            )
            val mergedId = merged.id.orEmpty().trim()
            if (mergedId.isBlank()) return@forEach
            if (seen.add(mergedId)) add(merged)
        }
    }
    return catalog.copy(models = normalized)
}

private fun sanitizeOnDeviceCatalogEntry(
    entry: OnDeviceModelCatalogEntry,
    canonical: OnDeviceModelCatalogEntry?,
    overrideId: String,
    forceImported: Boolean
): OnDeviceModelCatalogEntry {
    val canonicalEntry = canonical
    val sourceType = when {
        forceImported -> OnDeviceModelSourceType.IMPORTED.name
        canonicalEntry != null -> OnDeviceModelSourceType.CATALOG.name
        else -> entry.sourceTypeKey.orEmpty().ifBlank { OnDeviceModelSourceType.CATALOG.name }
    }
    val accessState = when {
        forceImported -> entry.accessStateKey.orEmpty().ifBlank { OnDeviceModelAccessState.EXTERNAL.name }
        canonicalEntry != null -> entry.accessStateKey.orEmpty().ifBlank {
            canonicalEntry.accessStateKey.orEmpty().ifBlank { OnDeviceModelAccessState.PUBLIC.name }
        }
        else -> entry.accessStateKey.orEmpty().ifBlank { OnDeviceModelAccessState.PUBLIC.name }
    }
    val capability = when {
        forceImported -> entry.capabilityKey.orEmpty().ifBlank { OnDeviceModelCapability.LOCAL_TEXT.name }
        canonicalEntry != null -> entry.capabilityKey.orEmpty().ifBlank {
            canonicalEntry.capabilityKey.orEmpty().ifBlank { OnDeviceModelCapability.LOCAL_TEXT.name }
        }
        else -> entry.capabilityKey.orEmpty().ifBlank { OnDeviceModelCapability.LOCAL_TEXT.name }
    }
    val runtimeProfileId = when {
        forceImported -> entry.runtimeProfileId.orEmpty().ifBlank { CURATED_GGUF_RUNTIME_PROFILE }
        canonicalEntry != null -> entry.runtimeProfileId.orEmpty().ifBlank {
            canonicalEntry.runtimeProfileId.orEmpty().ifBlank { CURATED_GGUF_RUNTIME_PROFILE }
        }
        else -> entry.runtimeProfileId.orEmpty().ifBlank { CURATED_GGUF_RUNTIME_PROFILE }
    }
    val mergedVisionProjectorDownloadUrl = when {
        forceImported -> entry.visionProjectorDownloadUrl.orEmpty()
        canonicalEntry != null && canonicalEntry.curatedVerified ->
            canonicalEntry.visionProjectorDownloadUrl.orEmpty().ifBlank {
                entry.visionProjectorDownloadUrl.orEmpty()
            }
        canonicalEntry != null -> entry.visionProjectorDownloadUrl.orEmpty().ifBlank {
            canonicalEntry.visionProjectorDownloadUrl.orEmpty()
        }
        else -> entry.visionProjectorDownloadUrl.orEmpty()
    }
    val mergedVisionProjectorFileName = when {
        forceImported -> entry.visionProjectorFileName.orEmpty()
        canonicalEntry != null && canonicalEntry.curatedVerified ->
            canonicalEntry.visionProjectorFileName.orEmpty().ifBlank {
                entry.visionProjectorFileName.orEmpty()
            }
        canonicalEntry != null -> entry.visionProjectorFileName.orEmpty().ifBlank {
            canonicalEntry.visionProjectorFileName.orEmpty()
        }
        else -> entry.visionProjectorFileName.orEmpty()
    }
    val mergedCapability = when {
        forceImported -> entry.capabilityKey.orEmpty().ifBlank { OnDeviceModelCapability.LOCAL_TEXT.name }
        canonicalEntry != null && canonicalEntry.curatedVerified ->
            canonicalEntry.capabilityKey.orEmpty().ifBlank {
                entry.capabilityKey.orEmpty().ifBlank { OnDeviceModelCapability.LOCAL_TEXT.name }
            }
        canonicalEntry != null -> canonicalEntry.capabilityKey.orEmpty().ifBlank {
            entry.capabilityKey.orEmpty().ifBlank { OnDeviceModelCapability.LOCAL_TEXT.name }
        }
        else -> entry.capabilityKey.orEmpty().ifBlank { OnDeviceModelCapability.LOCAL_TEXT.name }
    }
    return OnDeviceModelCatalogEntry(
        id = overrideId,
        displayName = entry.displayName.orEmpty().ifBlank {
            canonicalEntry?.displayName.orEmpty().ifBlank { overrideId }
        },
        description = entry.description.orEmpty().ifBlank {
            canonicalEntry?.description.orEmpty()
        },
        downloadUrl = entry.downloadUrl.orEmpty().ifBlank {
            canonicalEntry?.downloadUrl.orEmpty()
        },
        fileName = entry.fileName.orEmpty().ifBlank {
            canonicalEntry?.fileName.orEmpty()
        },
        visionProjectorDownloadUrl = mergedVisionProjectorDownloadUrl,
        visionProjectorFileName = mergedVisionProjectorFileName,
        capabilityKey = mergedCapability,
        supportsDocuments = entry.supportsDocuments || canonicalEntry?.supportsDocuments == true,
        estimatedSizeMb = if (entry.estimatedSizeMb > 0) entry.estimatedSizeMb else canonicalEntry?.estimatedSizeMb ?: 0,
        recommendedRank = if (entry.recommendedRank != Int.MAX_VALUE) {
            entry.recommendedRank
        } else {
            canonicalEntry?.recommendedRank ?: Int.MAX_VALUE
        },
        accessStateKey = accessState,
        sourceTypeKey = sourceType,
        runtimeProfileId = runtimeProfileId,
        curatedVerified = entry.curatedVerified || canonicalEntry?.curatedVerified == true
    )
}
