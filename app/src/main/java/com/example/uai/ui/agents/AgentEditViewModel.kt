package com.example.uai.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import okhttp3.OkHttpClient
import okhttp3.Request

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

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels

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

    private fun fetchOpenRouterModels() {
        viewModelScope.launch {
            _isLoadingModels.value = true
            try {
                val models = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("https://openrouter.ai/api/v1/models")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext emptyList()
                        val body = response.body?.string() ?: return@withContext emptyList()
                        val root = Gson().fromJson(body, JsonObject::class.java)
                        val data = root.getAsJsonArray("data") ?: return@withContext emptyList()
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
                        free.sorted() + paid.sorted()
                    }
                }
                _openRouterModels.value = models
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
