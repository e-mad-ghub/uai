package com.mad.screenagent.data.model

private const val GEMMA_3_1B_IT_GGUF_REPO = "ggml-org/gemma-3-1b-it-GGUF"
private const val GEMMA_3_1B_IT_GGUF_FILE = "gemma-3-1b-it-Q4_K_M.gguf"
private const val GEMMA_3_4B_IT_GGUF_REPO = "ggml-org/gemma-3-4b-it-GGUF"
private const val GEMMA_3_4B_IT_GGUF_FILE = "gemma-3-4b-it-Q4_K_M.gguf"
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
        capabilityKey = OnDeviceModelCapability.LOCAL_TEXT.name,
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
            if (entry.id in LEGACY_ON_DEVICE_IDS && !entry.isImported) return@forEach

            val canonical = canonicalById[entry.id]
            val merged = when {
                entry.isImported -> entry.copy(
                    sourceTypeKey = OnDeviceModelSourceType.IMPORTED.name,
                    capabilityKey = entry.capabilityKey ?: OnDeviceModelCapability.LOCAL_TEXT.name,
                    accessStateKey = entry.accessStateKey ?: OnDeviceModelAccessState.EXTERNAL.name,
                    runtimeProfileId = entry.runtimeProfileId ?: CURATED_GGUF_RUNTIME_PROFILE
                )
                canonical != null -> entry.copy(
                    displayName = entry.displayName.ifBlank { canonical.displayName },
                    description = entry.description.ifBlank { canonical.description },
                    downloadUrl = entry.downloadUrl.ifBlank { canonical.downloadUrl },
                    fileName = entry.fileName.ifBlank { canonical.fileName },
                    capabilityKey = entry.capabilityKey ?: canonical.capabilityKey,
                    supportsDocuments = entry.supportsDocuments || canonical.supportsDocuments,
                    estimatedSizeMb = if (entry.estimatedSizeMb > 0) entry.estimatedSizeMb else canonical.estimatedSizeMb,
                    recommendedRank = if (entry.recommendedRank != Int.MAX_VALUE) entry.recommendedRank else canonical.recommendedRank,
                    accessStateKey = entry.accessStateKey ?: canonical.accessStateKey,
                    sourceTypeKey = OnDeviceModelSourceType.CATALOG.name,
                    runtimeProfileId = entry.runtimeProfileId ?: canonical.runtimeProfileId,
                    curatedVerified = entry.curatedVerified || canonical.curatedVerified
                )
                else -> entry
            }
            if (seen.add(merged.id)) add(merged)
        }
    }
    return catalog.copy(models = normalized)
}
