package com.example.uai.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.ai.httpErrorMessage
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.model.CustomProviderPreset
import com.example.uai.data.model.OPENROUTER_FREE_ROUTER_MODEL
import com.example.uai.data.model.OpenRouterCatalogEntry
import com.example.uai.data.model.buildOpenAiCompatibleChatCompletionsUrl
import com.example.uai.data.model.buildOpenAiCompatibleModelsUrl
import com.example.uai.data.model.isOpenRouterFreeModel
import com.example.uai.data.model.normalizeOpenAiCompatibleBaseUrl
import com.example.uai.data.model.openRouterFreeFallbackModels
import com.example.uai.data.model.preferredOpenRouterVisionFreeModel
import com.example.uai.data.model.shouldRetryOpenRouterFreeFallback
import com.example.uai.data.repository.AgentRepository
import com.example.uai.data.repository.OpenRouterCatalogRepository
import com.example.uai.data.repository.ProviderModelCatalogRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
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

class AgentEditViewModel(
    private val repo: AgentRepository,
    private val agentId: String?,
    private val duplicateFromAgentId: String?,
    private val httpClient: OkHttpClient,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository,
    private val providerModelCatalogRepository: ProviderModelCatalogRepository
) : ViewModel() {

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

    val saveValidationMessage: StateFlow<String?> = combine(_agent, _allAgents) { draft, agents ->
        validateDraft(draft, agents)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        validateDraft(_agent.value, _allAgents.value)
    )

    private var openAiModels: List<String> = emptyList()
    private var anthropicModels: List<String> = emptyList()
    private var customModels: List<String> = emptyList()
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
        updateCurrentProviderModels(_agent.value.provider)
        scheduleCurrentProviderModelRefresh(force = true)
    }

    fun update(block: AgentConfig.() -> AgentConfig) {
        val previous = _agent.value
        val updated = previous.block()
        _agent.value = updated
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
        if (validateDraft(draft, _allAgents.value) != null) return
        viewModelScope.launch {
            val current = repo.agentsFlow.first().toMutableList()
            val idx = current.indexOfFirst { it.id == draft.id }
            if (idx >= 0) current[idx] = draft else current.add(draft)
            repo.saveAgentList(current)
            if (current.size == 1 || setActiveAfterSave) repo.setActiveAgent(draft.id)
            _isSaved.value = true
        }
    }

    fun testConnection() {
        val agent = _agent.value.normalizedForSave()
        if (agent.apiKey.isBlank()) {
            _connectionTestState.value =
                ConnectionTestState.Failure("Enter an API key for ${agent.provider.displayName} first.")
            return
        }
        viewModelScope.launch {
            _connectionTestState.value = ConnectionTestState.Testing
            try {
                if (agent.provider != AiProviderType.OPENROUTER) {
                    refreshCurrentProviderModels(force = true)
                }
                _connectionTestState.value = if (
                    agent.provider == AiProviderType.OPENROUTER &&
                    isOpenRouterFreeModel(agent.model, _freeModelIds.value)
                ) {
                    testOpenRouterFreeConnection(agent)
                } else {
                    val failure = runProbe(agent)
                    if (failure == null) ConnectionTestState.Success()
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
        update {
            copy(
                provider = provider,
                model = defaultModel,
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
                }
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
        val (url, requestBody, authHeader, authValue) = when (agent.provider) {
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
        }
        val request = Request.Builder()
            .url(url)
            .header(authHeader, authValue)
            .apply {
                if (agent.provider == AiProviderType.ANTHROPIC) {
                    header("anthropic-version", "2023-06-01")
                }
                if (agent.provider == AiProviderType.OPENROUTER) {
                    header("HTTP-Referer", "https://uai.app")
                    header("X-Title", "ScreenAgent")
                }
            }
            .post(requestBody.toRequestBody("application/json".toMediaType()))
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

    private fun scheduleCurrentProviderModelRefresh(force: Boolean = false) {
        modelRefreshJob?.cancel()
        modelRefreshJob = viewModelScope.launch {
            if (!force) delay(600)
            refreshCurrentProviderModels(force = force)
        }
    }

    private suspend fun refreshCurrentProviderModels(force: Boolean = false) {
        when (val provider = _agent.value.provider) {
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
            providerModelCatalogRepository.refreshCatalogIfStale(
                provider = provider,
                apiKey = apiKey,
                force = force
            )
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
        if (!force && customModels.isNotEmpty()) {
            _providerModels.value = customModels
            return
        }
        if (normalizedBaseUrl.isBlank() || apiKey.isBlank()) {
            customModels = emptyList()
            _providerModels.value = emptyList()
            return
        }

        _isLoadingModels.value = true
        customModels = emptyList()
        _providerModels.value = emptyList()
        try {
            customModels = fetchCustomCompatibleModels(
                baseUrl = normalizedBaseUrl,
                apiKey = apiKey
            )
        } catch (_: Exception) {
            // silently fall back to manual model entry
        } finally {
            _providerModels.value = customModels
            _isLoadingModels.value = false
        }
    }

    private fun updateCurrentProviderModels(provider: AiProviderType) {
        _providerModels.value = when (provider) {
            AiProviderType.OPENROUTER -> _openRouterModels.value
            AiProviderType.OPENAI -> openAiModels
            AiProviderType.ANTHROPIC -> anthropicModels
            AiProviderType.CUSTOM -> customModels
        }
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
        private val providerModelCatalogRepository: ProviderModelCatalogRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
                AgentEditViewModel(
                    repo = repo,
                    agentId = agentId,
                    duplicateFromAgentId = duplicateFromAgentId,
                    httpClient = httpClient,
                    openRouterCatalogRepository = openRouterCatalogRepository,
                    providerModelCatalogRepository = providerModelCatalogRepository
                ) as T
    }
}

private fun validateDraft(draft: AgentConfig, agents: List<AgentConfig>): String? {
    return validateName(draft, agents)
        ?: validateCustomBaseUrl(draft)
        ?: validateApiKey(draft)
        ?: if (draft.model.trim().isBlank()) "Choose a model before saving." else null
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

private fun AgentConfig.normalizedForSave(): AgentConfig = copy(
    name = name.trim(),
    apiKey = apiKey.trim(),
    model = model.trim(),
    customBaseUrl = normalizeOpenAiCompatibleBaseUrl(customBaseUrl)
)
