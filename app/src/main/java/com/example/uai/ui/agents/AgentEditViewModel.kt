package com.example.uai.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.ai.httpErrorMessage
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.model.OPENROUTER_FREE_ROUTER_MODEL
import com.example.uai.data.model.OpenRouterCatalogEntry
import com.example.uai.data.model.isOpenRouterFreeModel
import com.example.uai.data.model.openRouterFreeFallbackModels
import com.example.uai.data.model.preferredOpenRouterVisionFreeModel
import com.example.uai.data.model.shouldRetryOpenRouterFreeFallback
import com.example.uai.data.repository.AgentRepository
import com.example.uai.data.repository.OpenRouterCatalogRepository
import com.example.uai.data.repository.ProviderModelCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
    private val httpClient: OkHttpClient,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository,
    private val providerModelCatalogRepository: ProviderModelCatalogRepository
) : ViewModel() {

    val isEditing: Boolean = agentId != null

    private val _agent = MutableStateFlow(AgentConfig())
    val agent: StateFlow<AgentConfig> = _agent

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved

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

    private var openAiModels: List<String> = emptyList()
    private var anthropicModels: List<String> = emptyList()
    private var modelRefreshJob: Job? = null

    init {
        if (agentId != null) {
            viewModelScope.launch {
                val found = repo.agentsFlow.first().firstOrNull { it.id == agentId }
                if (found != null) {
                    _agent.value = found
                    updateCurrentProviderModels(found.provider)
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
            previous.apiKey != updated.apiKey -> {
                scheduleCurrentProviderModelRefresh()
            }
        }
    }

    fun save(setActiveAfterSave: Boolean = false) {
        val draft = _agent.value
        if (draft.name.isBlank() || draft.model.isBlank()) return
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
        val agent = _agent.value
        if (agent.apiKey.isBlank()) {
            _connectionTestState.value = ConnectionTestState.Failure("Enter an API key first.")
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
                            "Vision free is ready. The selected model did not respond, but SideAgent found a working free vision fallback: $candidate. Your selection will stay on Vision free."
                        } else {
                            "Free model is ready. The selected model did not respond, but SideAgent found another working free fallback: $candidate. Your selected option will stay the same."
                        }
                    )
                }
                return if (requireVision) {
                    ConnectionTestState.Success(
                        "Vision free is ready. SideAgent can route image requests through a working free vision model."
                    )
                } else {
                    if (candidate == OPENROUTER_FREE_ROUTER_MODEL) {
                        ConnectionTestState.Success(
                            "Free model is ready. OpenRouter's free router can route your requests to a working free model."
                        )
                    } else {
                        ConnectionTestState.Success(
                            "Free model is ready. SideAgent found a working free model for general chat."
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
                "OpenRouter's free vision models are not responding right now. SideAgent tried alternate free vision options automatically. $lastMessage"
            } else {
                "OpenRouter's best free models are not responding right now. SideAgent tried alternate free options automatically. $lastMessage"
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
                    header("X-Title", "SideAgent")
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

    private fun updateCurrentProviderModels(provider: AiProviderType) {
        _providerModels.value = when (provider) {
            AiProviderType.OPENROUTER -> _openRouterModels.value
            AiProviderType.OPENAI -> openAiModels
            AiProviderType.ANTHROPIC -> anthropicModels
        }
    }

    class Factory(
        private val repo: AgentRepository,
        private val agentId: String?,
        private val httpClient: OkHttpClient,
        private val openRouterCatalogRepository: OpenRouterCatalogRepository,
        private val providerModelCatalogRepository: ProviderModelCatalogRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            AgentEditViewModel(
                repo = repo,
                agentId = agentId,
                httpClient = httpClient,
                openRouterCatalogRepository = openRouterCatalogRepository,
                providerModelCatalogRepository = providerModelCatalogRepository
            ) as T
    }
}
