package com.mad.screenagent.feature.agents

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.mad.screenagent.shared.streaming.httpErrorMessage
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.CustomProviderPreset
import com.mad.screenagent.data.model.MONEY_SAVER_MODEL
import com.mad.screenagent.data.model.OPENROUTER_FREE_ROUTER_MODEL
import com.mad.screenagent.data.model.OpenRouterCatalogEntry
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceModelCatalog
import com.mad.screenagent.data.model.OnDeviceModelCatalogEntry
import com.mad.screenagent.data.model.OnDeviceModelLibraryItem
import com.mad.screenagent.data.model.buildOpenAiCompatibleChatCompletionsUrl
import com.mad.screenagent.data.model.buildOpenAiCompatibleModelsUrl
import com.mad.screenagent.data.model.isOpenRouterFreeModel
import com.mad.screenagent.data.model.normalizeOpenAiCompatibleBaseUrl
import com.mad.screenagent.data.model.openRouterFreeFallbackModels
import com.mad.screenagent.data.model.preferredOpenRouterVisionFreeModel
import com.mad.screenagent.data.model.shouldRetryOpenRouterFreeFallback
import com.mad.screenagent.data.repository.AgentRepository
import com.mad.screenagent.data.repository.OpenRouterCatalogRepository
import com.mad.screenagent.data.repository.OnDeviceCatalogRefreshResult
import com.mad.screenagent.data.repository.OnDeviceModelRepository
import com.mad.screenagent.data.repository.ProviderModelCatalogRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mad.screenagent.shared.streaming.OnDeviceUserMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

sealed class ConnectionTestState {
    object Idle : ConnectionTestState()
    object Testing : ConnectionTestState()
    data class Success(
        val message: String = "Availability confirmed. This assistant is ready to use."
    ) : ConnectionTestState()
    data class Failure(val message: String) : ConnectionTestState()
}

enum class OnDeviceShelfSort {
    RECOMMENDED,
    SMALLEST,
    QUALITY
}

enum class OnDeviceCatalogRefreshStatus {
    IDLE,
    REFRESHING,
    REFRESHED,
    FAILED_CACHED
}

data class OnDeviceCatalogUiState(
    val fetchedAt: Long = 0L,
    val refreshStatus: OnDeviceCatalogRefreshStatus = OnDeviceCatalogRefreshStatus.IDLE,
    val lastRefreshFailureMessage: String? = null,
    val shelfSort: OnDeviceShelfSort = OnDeviceShelfSort.RECOMMENDED
)

class AgentEditViewModel(
    private val repo: AgentRepository,
    private val agentId: String?,
    private val duplicateFromAgentId: String?,
    private val httpClient: OkHttpClient,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository,
    private val providerModelCatalogRepository: ProviderModelCatalogRepository,
    private val onDeviceModelRepository: OnDeviceModelRepository
) : ViewModel() {
    private companion object {
        const val TAG = "AgentEditVM"
    }

    val isEditing: Boolean = agentId != null
    val isDuplicating: Boolean = agentId == null && duplicateFromAgentId != null

    private val _agent = MutableStateFlow(AgentConfig())
    val agent: StateFlow<AgentConfig> = _agent

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved

    private val _allAgents = MutableStateFlow<List<AgentConfig>>(emptyList())
    val allAgents: StateFlow<List<AgentConfig>> = _allAgents

    private val _openRouterModels = MutableStateFlow<List<String>>(emptyList())
    val openRouterModels: StateFlow<List<String>> = _openRouterModels

    private val _openRouterCatalogEntries = MutableStateFlow<List<OpenRouterCatalogEntry>>(emptyList())
    val openRouterCatalogEntries: StateFlow<List<OpenRouterCatalogEntry>> = _openRouterCatalogEntries

    private val _freeModelIds = MutableStateFlow<Set<String>>(emptySet())
    val freeModelIds: StateFlow<Set<String>> = _freeModelIds

    private val _providerModels = MutableStateFlow<List<String>>(emptyList())
    val providerModels: StateFlow<List<String>> = _providerModels

    private val _onDeviceCatalog = MutableStateFlow(OnDeviceModelCatalog())
    val onDeviceCatalog: StateFlow<OnDeviceModelCatalog> = _onDeviceCatalog

    private val _onDeviceModelLibrary = MutableStateFlow<List<OnDeviceModelLibraryItem>>(emptyList())
    val onDeviceModelLibrary: StateFlow<List<OnDeviceModelLibraryItem>> = _onDeviceModelLibrary

    private val _publicOnDeviceModelLibrary = MutableStateFlow<List<OnDeviceModelLibraryItem>>(emptyList())
    val publicOnDeviceModelLibrary: StateFlow<List<OnDeviceModelLibraryItem>> = _publicOnDeviceModelLibrary

    private val _readyOnDeviceModelLibrary = MutableStateFlow<List<OnDeviceModelLibraryItem>>(emptyList())
    val readyOnDeviceModelLibrary: StateFlow<List<OnDeviceModelLibraryItem>> = _readyOnDeviceModelLibrary

    private val _importedOnDeviceModelLibrary = MutableStateFlow<List<OnDeviceModelLibraryItem>>(emptyList())
    val importedOnDeviceModelLibrary: StateFlow<List<OnDeviceModelLibraryItem>> = _importedOnDeviceModelLibrary

    private val _nonPublicOnDeviceModelLibrary = MutableStateFlow<List<OnDeviceModelLibraryItem>>(emptyList())
    val nonPublicOnDeviceModelLibrary: StateFlow<List<OnDeviceModelLibraryItem>> = _nonPublicOnDeviceModelLibrary

    private val _onDeviceDownloadState = MutableStateFlow(OnDeviceDownloadState.NOT_DOWNLOADED)
    val onDeviceDownloadState: StateFlow<OnDeviceDownloadState> = _onDeviceDownloadState

    private val _onDeviceCatalogUiState = MutableStateFlow(OnDeviceCatalogUiState())
    val onDeviceCatalogUiState: StateFlow<OnDeviceCatalogUiState> = _onDeviceCatalogUiState

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels

    private val _connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState

    val nameValidationMessage: StateFlow<String?> = combine(_agent, _allAgents) { draft, agents ->
        validateName(draft, agents)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        validateName(_agent.value, _allAgents.value)
    )

    val apiKeyValidationMessage: StateFlow<String?> = _agent.map { draft ->
        validateApiKey(draft)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        validateApiKey(_agent.value)
    )

    val baseUrlValidationMessage: StateFlow<String?> = _agent.map { draft ->
        validateCustomBaseUrl(draft)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        validateCustomBaseUrl(_agent.value)
    )

    val saveValidationMessage: StateFlow<String?> = combine(
        _agent,
        _allAgents,
        _readyOnDeviceModelLibrary
    ) { draft, agents, readyOnDeviceModels ->
        validateDraft(draft, agents, readyOnDeviceModels.map { it.catalogEntry.id }.toSet())
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        validateDraft(
            _agent.value,
            _allAgents.value,
            _readyOnDeviceModelLibrary.value.map { it.catalogEntry.id }.toSet()
        )
    )

    private var openAiModels: List<String> = emptyList()
    private var anthropicModels: List<String> = emptyList()
    private var onDeviceModels: List<String> = emptyList()
    private var customModels: List<String> = emptyList()
    private var customModelCacheKey: CustomModelCatalogCacheKey? = null
    private var modelRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            repo.agentsFlow.collect { agents ->
                _allAgents.value = agents
            }
        }
        if (agentId != null) {
            viewModelScope.launch {
                val found = repo.agentsFlow.first().firstOrNull { it.id == agentId }
                if (found != null) {
                    _agent.value = found
                    updateCurrentProviderModels(found.provider)
                    scheduleCurrentProviderModelRefresh(force = true)
                }
            }
        } else if (duplicateFromAgentId != null) {
            viewModelScope.launch {
                val existingAgents = repo.agentsFlow.first()
                val source = existingAgents.firstOrNull { it.id == duplicateFromAgentId }
                if (source != null) {
                    val duplicated = source.copy(
                        id = UUID.randomUUID().toString(),
                        name = generateDuplicateName(source.name, existingAgents)
                    )
                    _agent.value = duplicated
                    updateCurrentProviderModels(duplicated.provider)
                    scheduleCurrentProviderModelRefresh(force = true)
                }
            }
        }
        viewModelScope.launch {
            openRouterCatalogRepository.catalogFlow.collect { catalog ->
                _openRouterCatalogEntries.value = catalog.models
                _openRouterModels.value = catalog.models.map { it.id }
                _freeModelIds.value = catalog.models.filter { it.isFree }.map { it.id }.toSet()
                if (_agent.value.provider == AiProviderType.OPENROUTER) {
                    _providerModels.value = _openRouterModels.value
                }
            }
        }
        viewModelScope.launch {
            providerModelCatalogRepository.catalogFlow(AiProviderType.OPENAI).collect { catalog ->
                openAiModels = catalog.models.map { it.id }
                if (_agent.value.provider == AiProviderType.OPENAI) {
                    _providerModels.value = openAiModels
                }
            }
        }
        viewModelScope.launch {
            providerModelCatalogRepository.catalogFlow(AiProviderType.ANTHROPIC).collect { catalog ->
                anthropicModels = catalog.models.map { it.id }
                if (_agent.value.provider == AiProviderType.ANTHROPIC) {
                    _providerModels.value = anthropicModels
                }
            }
        }
        viewModelScope.launch {
            onDeviceModelRepository.catalogFlow.collect { catalog ->
                _onDeviceCatalog.value = catalog
                _onDeviceCatalogUiState.value = _onDeviceCatalogUiState.value.copy(
                    fetchedAt = catalog.fetchedAt
                )
            }
        }
        viewModelScope.launch {
            onDeviceModelRepository.libraryFlow.collect { publicLibrary ->
                _publicOnDeviceModelLibrary.value = publicLibrary
            }
        }
        viewModelScope.launch {
            onDeviceModelRepository.importedLibraryFlow.collect { importedLibrary ->
                _importedOnDeviceModelLibrary.value = importedLibrary
            }
        }
        viewModelScope.launch {
            onDeviceModelRepository.allLibraryFlow.collect { library ->
                _onDeviceModelLibrary.value = library
                _readyOnDeviceModelLibrary.value = library.filter {
                    it.installRecord?.downloadState?.isReadyForUse == true
                }
                onDeviceModels = _readyOnDeviceModelLibrary.value.map { it.catalogEntry.id }
                syncSelectedOnDeviceVisionSupport()
                if (_agent.value.provider == AiProviderType.ON_DEVICE) {
                    _providerModels.value = _readyOnDeviceModelLibrary.value.map { it.catalogEntry.id }
                }
            }
        }
        viewModelScope.launch {
            onDeviceModelRepository.nonPublicLibraryFlow.collect { nonPublicLibrary ->
                _nonPublicOnDeviceModelLibrary.value = nonPublicLibrary
            }
        }
        viewModelScope.launch {
            onDeviceModelRepository.downloadStateFlow.collect { state ->
                _onDeviceDownloadState.value = state
            }
        }
        updateCurrentProviderModels(_agent.value.provider)
        scheduleCurrentProviderModelRefresh(force = true)
    }

    fun update(block: AgentConfig.() -> AgentConfig) {
        val previous = _agent.value
        val updated = previous.block()
        _agent.value = if (updated.provider == AiProviderType.ON_DEVICE) {
            val selectedModelId = updated.onDevice.selectedModelId.trim().ifBlank {
                updated.model.trim()
            }
            val selectedModelSupportsVision = resolveOnDeviceModelSupportsVision(selectedModelId)
            updated.copy(
                model = selectedModelId,
                onDevice = updated.onDevice.copy(
                    selectedModelId = selectedModelId,
                    selectedModelSupportsVision = selectedModelSupportsVision
                )
            )
        } else {
            updated
        }
        _connectionTestState.value = ConnectionTestState.Idle
        when {
            previous.provider != updated.provider -> {
                updateCurrentProviderModels(updated.provider)
                scheduleCurrentProviderModelRefresh(force = true)
            }
            previous.apiKey != updated.apiKey ||
                previous.customBaseUrl != updated.customBaseUrl -> {
                scheduleCurrentProviderModelRefresh()
            }
        }
    }

    fun save(setActiveAfterSave: Boolean = false) {
        val draft = _agent.value.normalizedForSave()
        if (validateDraft(
                draft,
                _allAgents.value,
                _readyOnDeviceModelLibrary.value.map { it.catalogEntry.id }.toSet()
            ) != null
        ) return
        viewModelScope.launch {
            val current = repo.agentsFlow.first().toMutableList()
            val idx = current.indexOfFirst { it.id == draft.id }
            if (idx >= 0) current[idx] = draft else current.add(draft)
            repo.saveAgentList(current)
            if (current.size == 1 || setActiveAfterSave) repo.setActiveAgent(draft.id)
            _isSaved.value = true
        }
    }

    fun resetTokenUsage() {
        viewModelScope.launch {
            repo.resetTokenUsage(_agent.value.id)
            // Reload the updated agent so the UI reflects the reset immediately
            val updated = repo.agentsFlow.first().firstOrNull { it.id == _agent.value.id }
            if (updated != null) _agent.value = updated
        }
    }

    fun testConnection() {
        val agent = _agent.value.normalizedForSave()
        if (agent.provider == AiProviderType.ON_DEVICE) {
            viewModelScope.launch {
                val modelId = agent.onDevice.selectedModelId.trim().ifBlank { agent.model.trim() }
                val installed = onDeviceModelRepository.getInstalledModel(modelId)
                _connectionTestState.value = if (modelId.isBlank()) {
                    ConnectionTestState.Failure(OnDeviceUserMessages.chooseModel())
                } else if (installed == null) {
                    ConnectionTestState.Failure(OnDeviceUserMessages.downloadModelFirst())
                } else if (installed.downloadState == OnDeviceDownloadState.DOWNLOADING ||
                    installed.downloadState == OnDeviceDownloadState.VALIDATING
                ) {
                    ConnectionTestState.Failure(OnDeviceUserMessages.modelStillDownloading())
                } else if (!installed.downloadState.isReadyForUse) {
                    ConnectionTestState.Failure(
                        OnDeviceUserMessages.validationMessage(installed.failureKind, installed.errorMessage)
                    )
                } else {
                    ConnectionTestState.Success("This on-device model is ready to use.")
                }
            }
            return
        }
        if (agent.apiKey.isBlank()) {
            _connectionTestState.value = ConnectionTestState.Failure(
                "Enter an API key for ${agent.provider.displayName} first."
            )
            return
        }
        viewModelScope.launch {
            _connectionTestState.value = ConnectionTestState.Testing
            try {
                if (agent.provider != AiProviderType.OPENROUTER) {
                    refreshCurrentProviderModels(force = true)
                }
                // Resolve the money-saver sentinel to an actual model so we don't
                // send "uai:money-saver" as a model ID to the API (causes 404).
                val probeAgent = if (agent.model == MONEY_SAVER_MODEL) {
                    agent.copy(model = resolveMoneySaverModel(agent))
                } else agent
                _connectionTestState.value = if (
                    probeAgent.provider == AiProviderType.OPENROUTER &&
                    isOpenRouterFreeModel(probeAgent.model, _freeModelIds.value)
                ) {
                    testOpenRouterFreeConnection(probeAgent)
                } else {
                    val failure = runProbe(probeAgent)
                    if (failure == null) ConnectionTestState.Success(
                        if (agent.model == MONEY_SAVER_MODEL)
                            "Availability confirmed. Using model: ${probeAgent.model}"
                        else
                            "Availability confirmed. This assistant is ready to use."
                    )
                    else ConnectionTestState.Failure(failure.message)
                }
            } catch (e: Exception) {
                _connectionTestState.value = ConnectionTestState.Failure(e.message ?: "Availability check failed")
            }
        }
    }

    fun switchProvider(provider: AiProviderType, defaultModel: String) {
        val current = _agent.value
        if (current.provider == provider) return
        _agent.value = current.forProviderSwitch(provider = provider, defaultModel = defaultModel)
        _connectionTestState.value = ConnectionTestState.Idle
        updateCurrentProviderModels(provider)
        scheduleCurrentProviderModelRefresh(force = true)
    }

    fun refreshOnDeviceCatalog() {
        if (_agent.value.provider != AiProviderType.ON_DEVICE) return
        Log.i(TAG, "refreshOnDeviceCatalog tapped")
        _onDeviceCatalogUiState.value = _onDeviceCatalogUiState.value.copy(
            refreshStatus = OnDeviceCatalogRefreshStatus.REFRESHING,
            lastRefreshFailureMessage = null
        )
        scheduleCurrentProviderModelRefresh(force = true)
    }

    fun setOnDeviceShelfSort(sort: OnDeviceShelfSort) {
        _onDeviceCatalogUiState.value = _onDeviceCatalogUiState.value.copy(shelfSort = sort)
    }

    fun downloadOnDeviceModel(modelId: String) {
        if (modelId.isBlank()) return
        viewModelScope.launch {
            try {
                onDeviceModelRepository.enqueueDownload(modelId)
                if (_agent.value.provider == AiProviderType.ON_DEVICE) {
                    _connectionTestState.value = ConnectionTestState.Success(
                        "Downloading the local model in the background."
                    )
                }
            } catch (e: Exception) {
                if (_agent.value.provider == AiProviderType.ON_DEVICE) {
                    _connectionTestState.value = ConnectionTestState.Failure(
                        OnDeviceUserMessages.downloadFailure(e)
                    )
                }
            }
        }
    }

    fun cancelOnDeviceDownload(modelId: String) {
        if (modelId.isBlank()) return
        viewModelScope.launch {
            try {
                onDeviceModelRepository.cancelDownload(modelId)
                if (_agent.value.provider == AiProviderType.ON_DEVICE) {
                    _connectionTestState.value = ConnectionTestState.Idle
                }
            } catch (e: Exception) {
                if (_agent.value.provider == AiProviderType.ON_DEVICE) {
                    _connectionTestState.value = ConnectionTestState.Failure(
                        OnDeviceUserMessages.cancelDownloadFailure()
                    )
                }
            }
        }
    }

    fun importOnDeviceModel(uri: Uri) {
        if (_agent.value.provider != AiProviderType.ON_DEVICE) return
        viewModelScope.launch {
            try {
                val installed = onDeviceModelRepository.importModel(uri)
                update {
                    copy(
                        model = installed.modelId,
                        onDevice = onDevice.copy(
                            selectedModelId = installed.modelId,
                            selectedModelSupportsVision = false
                        )
                    )
                }
                _connectionTestState.value = ConnectionTestState.Success(
                    OnDeviceUserMessages.importSuccess()
                )
            } catch (e: Exception) {
                _connectionTestState.value = ConnectionTestState.Failure(
                    OnDeviceUserMessages.importFailure(e)
                )
            }
        }
    }

    fun deleteOnDeviceModel(modelId: String) {
        if (modelId.isBlank()) return
        viewModelScope.launch {
            onDeviceModelRepository.deleteInstalledModel(modelId)
            onDeviceModelRepository.saveDownloadState(OnDeviceDownloadState.NOT_DOWNLOADED)
            if (_agent.value.provider == AiProviderType.ON_DEVICE &&
                _agent.value.onDevice.selectedModelId == modelId
            ) {
                _connectionTestState.value = ConnectionTestState.Idle
            }
        }
    }

    fun selectOnDeviceModel(modelId: String) {
        if (_agent.value.provider != AiProviderType.ON_DEVICE) return
        val readyModelIds = _readyOnDeviceModelLibrary.value.map { it.catalogEntry.id }.toSet()
        if (modelId !in readyModelIds) return
        val supportsVision = resolveOnDeviceModelSupportsVision(modelId)
        Log.i(TAG, "selectOnDeviceModel id=$modelId supportsVision=$supportsVision")
        update {
            copy(
                model = modelId,
                onDevice = onDevice.copy(
                    selectedModelId = modelId,
                    selectedModelSupportsVision = supportsVision
                )
            )
        }
    }

    fun applyCustomPreset(preset: CustomProviderPreset) {
        if (_agent.value.provider != AiProviderType.CUSTOM) return
        update {
            copy(
                customPreset = preset,
                customBaseUrl = if (preset == CustomProviderPreset.MANUAL) {
                    customBaseUrl
                } else {
                    preset.suggestedBaseUrl
                }
            )
        }
    }

    private data class Probe(val url: String, val body: String, val headerName: String, val headerValue: String)
    private data class ProbeFailure(val code: Int, val message: String)

    private suspend fun testOpenRouterFreeConnection(agent: AgentConfig): ConnectionTestState {
        val catalogEntries = openRouterCatalogRepository.getCatalog().models
        val requireVision = agent.model == preferredOpenRouterVisionFreeModel(
            catalogEntries = catalogEntries,
            fetchedOpenRouterModels = _openRouterModels.value,
            freeModelIds = _freeModelIds.value
        )
        val candidates = openRouterFreeFallbackModels(
            catalogEntries = catalogEntries,
            fetchedOpenRouterModels = _openRouterModels.value,
            freeModelIds = _freeModelIds.value,
            currentModel = agent.model,
            requireVision = requireVision
        )
        var lastRetryableFailure: ProbeFailure? = null

        for (candidate in candidates) {
            val failure = runProbe(agent.copy(model = candidate))
            if (failure == null) {
                if (candidate != agent.model) {
                    return ConnectionTestState.Success(
                        if (requireVision) {
                            "Vision free is ready. The selected model did not respond, but ScreenAgent found a working free vision fallback: $candidate. Your selection will stay on Vision free."
                        } else {
                            "Free model is ready. The selected model did not respond, but ScreenAgent found another working free fallback: $candidate. Your selected option will stay the same."
                        }
                    )
                }
                return if (requireVision) {
                    ConnectionTestState.Success(
                        "Vision free is ready. ScreenAgent can route image requests through a working free vision model."
                    )
                } else {
                    if (candidate == OPENROUTER_FREE_ROUTER_MODEL) {
                        ConnectionTestState.Success(
                            "Free model is ready. OpenRouter's free router can route your requests to a working free model."
                        )
                    } else {
                        ConnectionTestState.Success(
                            "Free model is ready. ScreenAgent found a working free model for general chat."
                        )
                    }
                }
            }

            if (!shouldRetryOpenRouterFreeFallback(failure.code, failure.message)) {
                return ConnectionTestState.Failure(failure.message)
            }
            lastRetryableFailure = failure
        }

        val lastMessage = lastRetryableFailure?.message ?: "Connection failed"
        return ConnectionTestState.Failure(
            if (requireVision) {
                "OpenRouter's free vision models are not responding right now. ScreenAgent tried alternate free vision options automatically. $lastMessage"
            } else {
                "OpenRouter's best free models are not responding right now. ScreenAgent tried alternate free options automatically. $lastMessage"
            }
        )
    }

    private suspend fun runProbe(agent: AgentConfig): ProbeFailure? = withContext(Dispatchers.IO) {
        if (agent.provider == AiProviderType.ON_DEVICE) {
            return@withContext ProbeFailure(
                code = 400,
                message = "On-Device models are validated locally, not over HTTP."
            )
        }
        val probe = when (agent.provider) {
            AiProviderType.ANTHROPIC -> Probe(
                url = "https://api.anthropic.com/v1/messages",
                body = """{"model":"${agent.model}","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}""",
                headerName = "x-api-key",
                headerValue = agent.apiKey
            )
            AiProviderType.OPENAI -> Probe(
                url = "https://api.openai.com/v1/chat/completions",
                body = """{"model":"${agent.model}","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}""",
                headerName = "Authorization",
                headerValue = "Bearer ${agent.apiKey}"
            )
            AiProviderType.OPENROUTER -> Probe(
                url = "https://openrouter.ai/api/v1/chat/completions",
                body = """{"model":"${agent.model}","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}""",
                headerName = "Authorization",
                headerValue = "Bearer ${agent.apiKey}"
            )
            AiProviderType.CUSTOM -> Probe(
                url = buildOpenAiCompatibleChatCompletionsUrl(agent.customBaseUrl),
                body = """{"model":"${agent.model}","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}""",
                headerName = "Authorization",
                headerValue = "Bearer ${agent.apiKey}"
            )
            else -> error("On-Device providers are validated locally, not over HTTP.")
        }
        val request = Request.Builder()
            .url(probe.url)
            .header(probe.headerName, probe.headerValue)
            .apply {
                if (agent.provider == AiProviderType.ANTHROPIC) {
                    header("anthropic-version", "2023-06-01")
                }
                if (agent.provider == AiProviderType.OPENROUTER) {
                    header("HTTP-Referer", "https://uai.app")
                    header("X-Title", "ScreenAgent")
                }
            }
            .post(probe.body.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                null
            } else {
                ProbeFailure(
                    code = response.code,
                    message = httpErrorMessage(response.code)
                )
            }
        }
    }

    private suspend fun resolveMoneySaverModel(agent: AgentConfig): String {
        return when (agent.provider) {
            AiProviderType.ANTHROPIC -> {
                val models = providerModelCatalogRepository.getCatalog(AiProviderType.ANTHROPIC).models.map { it.id }
                models.minByOrNull { id ->
                    val n = id.lowercase()
                    when {
                        n.contains("haiku") -> 0
                        n.contains("sonnet") -> 1
                        n.contains("opus") -> 2
                        else -> 3
                    }
                } ?: "claude-haiku-4-5-20251001"
            }
            AiProviderType.OPENAI -> {
                val models = providerModelCatalogRepository.getCatalog(AiProviderType.OPENAI).models.map { it.id }
                models.minByOrNull { id ->
                    val n = id.lowercase()
                    when {
                        n.contains("nano") -> 0
                        n.contains("mini") -> 1
                        n.contains("4o") -> 2
                        n.contains("4.1") -> 3
                        n.contains("gpt-5") -> 4
                        else -> 5
                    }
                } ?: "gpt-4o-mini"
            }
            AiProviderType.ON_DEVICE -> agent.onDevice.selectedModelId.ifBlank { agent.model }
            else -> agent.model
        }
    }

    private fun scheduleCurrentProviderModelRefresh(force: Boolean = false) {
        modelRefreshJob?.cancel()
        modelRefreshJob = viewModelScope.launch {
            if (!force) delay(600)
            refreshCurrentProviderModels(force = force)
        }
    }

    private suspend fun refreshCurrentProviderModels(force: Boolean = false) {
        when (val provider = _agent.value.provider) {
            AiProviderType.ON_DEVICE -> refreshOnDeviceModels(force = force)
            AiProviderType.OPENROUTER -> refreshOpenRouterModels(force = force)
            AiProviderType.OPENAI, AiProviderType.ANTHROPIC -> refreshProviderModels(
                provider = provider,
                apiKey = _agent.value.apiKey,
                force = force
            )
            AiProviderType.CUSTOM -> refreshCustomModels(
                baseUrl = _agent.value.customBaseUrl,
                apiKey = _agent.value.apiKey,
                force = force
            )
        }
    }

    private suspend fun refreshProviderModels(
        provider: AiProviderType,
        apiKey: String,
        force: Boolean = false
    ) {
        _isLoadingModels.value = true
        try {
            val catalog = providerModelCatalogRepository.refreshCatalogIfStale(
                provider = provider,
                apiKey = apiKey,
                force = force
            )
            // Update in-memory cache directly so updateCurrentProviderModels sees the fresh list
            // without waiting for the DataStore flow collector to fire.
            when (provider) {
                AiProviderType.OPENAI -> if (catalog.models.isNotEmpty()) openAiModels = catalog.models.map { it.id }
                AiProviderType.ANTHROPIC -> if (catalog.models.isNotEmpty()) anthropicModels = catalog.models.map { it.id }
                AiProviderType.ON_DEVICE -> if (catalog.models.isNotEmpty()) onDeviceModels = catalog.models.map { it.id }
                else -> {}
            }
        } catch (_: Exception) {
            // silently fall back to cached or static list
        } finally {
            updateCurrentProviderModels(provider)
            _isLoadingModels.value = false
        }
    }

    private suspend fun refreshOpenRouterModels(force: Boolean = false) {
        _isLoadingModels.value = true
        try {
            openRouterCatalogRepository.refreshCatalogIfStale(force = force || _openRouterModels.value.isEmpty())
        } catch (_: Exception) {
            // silently fall back to static list
        } finally {
            _providerModels.value = _openRouterModels.value
            _isLoadingModels.value = false
        }
    }

    private suspend fun refreshCustomModels(
        baseUrl: String,
        apiKey: String,
        force: Boolean = false
    ) {
        val normalizedBaseUrl = normalizeOpenAiCompatibleBaseUrl(baseUrl)
        val requestedCacheKey = normalizedBaseUrl
            .takeIf { it.isNotBlank() && apiKey.isNotBlank() }
            ?.let { CustomModelCatalogCacheKey(baseUrl = it, apiKey = apiKey) }
        if (shouldReuseCustomModelCatalog(
                force = force,
                requestedKey = requestedCacheKey,
                cachedKey = customModelCacheKey,
                cachedModels = customModels
            )
        ) {
            _providerModels.value = customModels
            return
        }
        if (normalizedBaseUrl.isBlank() || apiKey.isBlank()) {
            customModels = emptyList()
            customModelCacheKey = null
            _providerModels.value = emptyList()
            return
        }

        _isLoadingModels.value = true
        customModels = emptyList()
        customModelCacheKey = null
        _providerModels.value = emptyList()
        try {
            customModels = fetchCustomCompatibleModels(
                baseUrl = normalizedBaseUrl,
                apiKey = apiKey
            )
            customModelCacheKey = requestedCacheKey
        } catch (_: Exception) {
            // silently fall back to manual model entry
        } finally {
            _providerModels.value = customModels
            _isLoadingModels.value = false
        }
    }

    private fun updateCurrentProviderModels(provider: AiProviderType) {
        _providerModels.value = when (provider) {
            AiProviderType.ON_DEVICE -> onDeviceModels
            AiProviderType.OPENROUTER -> _openRouterModels.value
            AiProviderType.OPENAI -> openAiModels
            AiProviderType.ANTHROPIC -> anthropicModels
            AiProviderType.CUSTOM -> customModels
        }
    }

    private suspend fun refreshOnDeviceModels(force: Boolean = false) {
        _isLoadingModels.value = true
        try {
            Log.i(TAG, "refreshOnDeviceModels force=$force")
            val result = onDeviceModelRepository.refreshCatalogIfStale(force = force)
            applyOnDeviceCatalogRefreshResult(result)
            Log.i(
                TAG,
                "refreshOnDeviceModels result usedCached=${result.usedCachedCatalog} models=${result.catalog.models.size} failure=${result.failureMessage}"
            )
            onDeviceModels = result.catalog.models
                .filter { it.isPublicDefaultChoice() }
                .map { it.id }
        } catch (e: Exception) {
            // fall back to cached catalog, but keep the customer-facing reason specific
            Log.w(TAG, "refreshOnDeviceModels failed", e)
            _onDeviceCatalogUiState.value = _onDeviceCatalogUiState.value.copy(
                refreshStatus = OnDeviceCatalogRefreshStatus.FAILED_CACHED,
                lastRefreshFailureMessage = OnDeviceUserMessages.refreshCatalogFailure(e)
            )
        } finally {
            updateCurrentProviderModels(AiProviderType.ON_DEVICE)
            _isLoadingModels.value = false
        }
    }

    private fun resolveOnDeviceModelSupportsVision(modelId: String): Boolean {
        val normalized = modelId.trim()
        if (normalized.isBlank()) return false
        return _onDeviceModelLibrary.value.firstOrNull { it.catalogEntry.id == normalized }
            ?.catalogEntry
            ?.supportsVision
            ?: _onDeviceCatalog.value.models.firstOrNull { it.id == normalized }?.supportsVision
            ?: false
    }

    private fun syncSelectedOnDeviceVisionSupport() {
        val current = _agent.value
        if (current.provider != AiProviderType.ON_DEVICE) return
        val selectedModelId = current.onDevice.selectedModelId.trim().ifBlank { current.model.trim() }
        if (selectedModelId.isBlank()) return
        val supportsVision = resolveOnDeviceModelSupportsVision(selectedModelId)
        if (current.onDevice.selectedModelSupportsVision == supportsVision &&
            current.model == selectedModelId
        ) {
            return
        }
        _agent.value = current.copy(
            model = selectedModelId,
            onDevice = current.onDevice.copy(
                selectedModelId = selectedModelId,
                selectedModelSupportsVision = supportsVision
            )
        )
    }

    private fun applyOnDeviceCatalogRefreshResult(result: OnDeviceCatalogRefreshResult) {
        _onDeviceCatalogUiState.value = _onDeviceCatalogUiState.value.copy(
            fetchedAt = result.catalog.fetchedAt,
            refreshStatus = if (result.usedCachedCatalog) {
                OnDeviceCatalogRefreshStatus.FAILED_CACHED
            } else {
                OnDeviceCatalogRefreshStatus.REFRESHED
            },
            lastRefreshFailureMessage = result.failureMessage
        )
    }

    private suspend fun fetchCustomCompatibleModels(
        baseUrl: String,
        apiKey: String
    ): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildOpenAiCompatibleModelsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .build()

        val gson = Gson()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use emptyList()

            val root = gson.fromJson(body, JsonObject::class.java)
            val data = root.getAsJsonArray("data") ?: return@use emptyList()
            buildList {
                data.forEach { element ->
                    val obj = element.asJsonObject
                    val id = obj.get("id")?.asString?.trim().orEmpty()
                    if (id.isBlank()) return@forEach
                    add(id)
                }
            }.distinct().sorted()
        }
    }

    class Factory(
        private val repo: AgentRepository,
        private val agentId: String?,
        private val duplicateFromAgentId: String?,
        private val httpClient: OkHttpClient,
        private val openRouterCatalogRepository: OpenRouterCatalogRepository,
        private val providerModelCatalogRepository: ProviderModelCatalogRepository,
        private val onDeviceModelRepository: OnDeviceModelRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
                AgentEditViewModel(
                    repo = repo,
                    agentId = agentId,
                    duplicateFromAgentId = duplicateFromAgentId,
                    httpClient = httpClient,
                    openRouterCatalogRepository = openRouterCatalogRepository,
                    providerModelCatalogRepository = providerModelCatalogRepository,
                    onDeviceModelRepository = onDeviceModelRepository
                ) as T
    }
}

private fun OnDeviceModelCatalogEntry.isPublicDefaultChoice(): Boolean {
    return isCatalogDownload
}

private fun validateDraft(
    draft: AgentConfig,
    agents: List<AgentConfig>,
    readyOnDeviceModelIds: Set<String>
): String? {
    val onDeviceModelId = draft.onDevice.selectedModelId.trim().ifBlank { draft.model.trim() }
    return validateName(draft, agents)
        ?: validateCustomBaseUrl(draft)
        ?: validateApiKey(draft)
        ?: if (draft.provider == AiProviderType.ON_DEVICE) {
            if (onDeviceModelId.isBlank()) {
                "Choose an on-device model before saving."
            } else if (onDeviceModelId !in readyOnDeviceModelIds) {
                "Download and select a ready on-device model before saving."
            } else null
        } else if (draft.model.trim().isBlank()) {
            "Choose a model before saving."
        } else null
}

private fun validateName(draft: AgentConfig, agents: List<AgentConfig>): String? {
    val normalizedName = draft.name.trim()
    if (normalizedName.isBlank()) return "Enter an assistant name."

    val hasDuplicate = agents.any { existing ->
        existing.id != draft.id &&
            existing.name.trim().equals(normalizedName, ignoreCase = true)
    }
    return if (hasDuplicate) {
        "Assistant names must be unique."
    } else {
        null
    }
}

private fun validateApiKey(draft: AgentConfig): String? {
    if (!providerRequiresApiKey(draft.provider)) return null
    return if (draft.apiKey.trim().isBlank()) {
        "Enter an API key for ${draft.provider.displayName}."
    } else {
        null
    }
}

private fun validateCustomBaseUrl(draft: AgentConfig): String? {
    if (draft.provider != AiProviderType.CUSTOM) return null
    val normalized = normalizeOpenAiCompatibleBaseUrl(draft.customBaseUrl)
    if (normalized.isBlank()) {
        return "Enter a base URL for the custom provider."
    }
    if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
        return "Enter a full http or https base URL."
    }
    return null
}

private fun generateDuplicateName(sourceName: String, agents: List<AgentConfig>): String {
    val baseName = sourceName.trim().ifBlank { "Assistant" }
    val existingNames = agents.map { it.name.trim().lowercase() }.toSet()

    val firstChoice = "$baseName Copy"
    if (firstChoice.lowercase() !in existingNames) return firstChoice

    var copyIndex = 2
    while (true) {
        val candidate = "$baseName Copy $copyIndex"
        if (candidate.lowercase() !in existingNames) return candidate
        copyIndex += 1
    }
}

internal fun supportsNativeWebSearch(provider: AiProviderType): Boolean =
    provider == AiProviderType.OPENAI || provider == AiProviderType.ANTHROPIC

internal data class CustomModelCatalogCacheKey(
    val baseUrl: String,
    val apiKey: String
)

internal fun shouldReuseCustomModelCatalog(
    force: Boolean,
    requestedKey: CustomModelCatalogCacheKey?,
    cachedKey: CustomModelCatalogCacheKey?,
    cachedModels: List<String>
): Boolean {
    return !force &&
        requestedKey != null &&
        requestedKey == cachedKey &&
        cachedModels.isNotEmpty()
}

internal fun providerRequiresApiKey(provider: AiProviderType): Boolean =
    provider != AiProviderType.ON_DEVICE

internal fun AgentConfig.forProviderSwitch(
    provider: AiProviderType,
    defaultModel: String
): AgentConfig = copy(
    provider = provider,
    model = if (provider == AiProviderType.ON_DEVICE) "" else defaultModel,
    apiKey = "",
    customPreset = if (provider == AiProviderType.CUSTOM) {
        CustomProviderPreset.MANUAL
    } else {
        customPreset
    },
    customBaseUrl = if (provider == AiProviderType.CUSTOM) {
        customBaseUrl
    } else {
        ""
    },
    onDevice = if (provider == AiProviderType.ON_DEVICE) {
        onDevice.copy(selectedModelId = "")
    } else {
        onDevice
    },
    nativeWebSearchEnabled = false,
    nativeWebSearchToolType = null
)

private fun AgentConfig.normalizedForSave(): AgentConfig {
    val sanitizedToolType = nativeWebSearchToolType
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return copy(
        name = name.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        onDevice = if (provider == AiProviderType.ON_DEVICE) {
            onDevice.copy(selectedModelId = onDevice.selectedModelId.trim().ifBlank { model.trim() })
        } else {
            onDevice
        },
        customBaseUrl = normalizeOpenAiCompatibleBaseUrl(customBaseUrl),
        nativeWebSearchEnabled = nativeWebSearchEnabled && supportsNativeWebSearch(provider),
        nativeWebSearchToolType = if (supportsNativeWebSearch(provider)) sanitizedToolType else null
    )
}
