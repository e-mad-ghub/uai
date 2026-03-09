package com.example.uai.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.ai.httpErrorMessage
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.repository.AgentRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
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
    object Success : ConnectionTestState()
    data class Failure(val message: String) : ConnectionTestState()
}

class AgentEditViewModel(
    private val repo: AgentRepository,
    private val agentId: String?,
    private val httpClient: OkHttpClient
) : ViewModel() {

    private val _agent = MutableStateFlow(AgentConfig())
    val agent: StateFlow<AgentConfig> = _agent

    private val _isSaved = MutableStateFlow(false)
    val isSaved: StateFlow<Boolean> = _isSaved

    private val _openRouterModels = MutableStateFlow<List<String>>(emptyList())
    val openRouterModels: StateFlow<List<String>> = _openRouterModels

    private val _freeModelIds = MutableStateFlow<Set<String>>(emptySet())
    val freeModelIds: StateFlow<Set<String>> = _freeModelIds

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels

    private val _connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState

    init {
        if (agentId != null) {
            viewModelScope.launch {
                val found = repo.agentsFlow.first().firstOrNull { it.id == agentId }
                if (found != null) _agent.value = found
            }
        }
        fetchOpenRouterModels()
    }

    fun update(block: AgentConfig.() -> AgentConfig) {
        _agent.value = _agent.value.block()
        _connectionTestState.value = ConnectionTestState.Idle
    }

    fun save() {
        viewModelScope.launch {
            val current = repo.agentsFlow.first().toMutableList()
            val idx = current.indexOfFirst { it.id == _agent.value.id }
            if (idx >= 0) current[idx] = _agent.value else current.add(_agent.value)
            repo.saveAgentList(current)
            if (current.size == 1) repo.setActiveAgent(current[0].id)
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
                val result = withContext(Dispatchers.IO) {
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
                                header("X-Title", "UAI")
                            }
                        }
                        .post(requestBody.toRequestBody("application/json".toMediaType()))
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) null else httpErrorMessage(response.code)
                    }
                }
                _connectionTestState.value = if (result == null) ConnectionTestState.Success
                    else ConnectionTestState.Failure(result)
            } catch (e: Exception) {
                _connectionTestState.value = ConnectionTestState.Failure(e.message ?: "Connection failed")
            }
        }
    }

    private data class Probe(val url: String, val body: String, val headerName: String, val headerValue: String)

    private fun fetchOpenRouterModels() {
        viewModelScope.launch {
            _isLoadingModels.value = true
            try {
                val gson = Gson()
                val (models, freeIds) = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("https://openrouter.ai/api/v1/models")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext Pair(emptyList(), emptySet<String>())
                        val body = response.body?.string() ?: return@withContext Pair(emptyList(), emptySet<String>())
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val data = root.getAsJsonArray("data") ?: return@withContext Pair(emptyList(), emptySet<String>())
                        val free = mutableListOf<String>()
                        val paid = mutableListOf<String>()
                        data.forEach { element ->
                            val obj = element.asJsonObject
                            val id = obj.get("id")?.asString ?: return@forEach
                            val pricing = obj.getAsJsonObject("pricing")
                            val promptPrice = pricing?.get("prompt")?.asString?.toDoubleOrNull() ?: 1.0
                            val completionPrice = pricing?.get("completion")?.asString?.toDoubleOrNull() ?: 1.0
                            if (promptPrice == 0.0 && completionPrice == 0.0) free.add(id)
                            else paid.add(id)
                        }
                        Pair(free.sorted() + paid.sorted(), free.toSet())
                    }
                }
                _openRouterModels.value = models
                _freeModelIds.value = freeIds
            } catch (_: Exception) {
                // silently fall back to static list
            } finally {
                _isLoadingModels.value = false
            }
        }
    }

    class Factory(
        private val repo: AgentRepository,
        private val agentId: String?,
        private val httpClient: OkHttpClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            AgentEditViewModel(repo, agentId, httpClient) as T
    }
}
