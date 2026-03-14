package com.example.uai.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.model.AppColorTheme
import com.example.uai.data.model.OpenRouterCatalog
import com.example.uai.data.model.ProviderModelCatalog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("uai_prefs")

class AppPreferences(context: Context) {

    private val store = context.dataStore
    private val gson = Gson()

    private object Keys {
        val AGENT_LIST_JSON = stringPreferencesKey("agent_list_json")
        val ACTIVE_AGENT_ID = stringPreferencesKey("active_agent_id")
        val BUBBLE_ENABLED = booleanPreferencesKey("bubble_enabled")
        val BUBBLE_DEFAULT_INITIALIZED = booleanPreferencesKey("bubble_default_initialized")
        val BUBBLE_USER_SET = booleanPreferencesKey("bubble_user_set")
        val BUBBLE_POS_X = intPreferencesKey("bubble_pos_x")
        val BUBBLE_POS_Y = intPreferencesKey("bubble_pos_y")
        val COLOR_THEME = stringPreferencesKey("color_theme")
        val MINI_CHAT_ENTRY_TIP_DISMISSED = booleanPreferencesKey("mini_chat_entry_tip_dismissed")
        val MINI_CHAT_MINIMIZE_TIP_DISMISSED = booleanPreferencesKey("mini_chat_minimize_tip_dismissed")
        val OPENROUTER_CATALOG_JSON = stringPreferencesKey("openrouter_catalog_json")
        val OPENROUTER_CATALOG_FETCHED_AT = longPreferencesKey("openrouter_catalog_fetched_at")
        val OPENAI_MODEL_CATALOG_JSON = stringPreferencesKey("openai_model_catalog_json")
        val OPENAI_MODEL_CATALOG_FETCHED_AT = longPreferencesKey("openai_model_catalog_fetched_at")
        val ANTHROPIC_MODEL_CATALOG_JSON = stringPreferencesKey("anthropic_model_catalog_json")
        val ANTHROPIC_MODEL_CATALOG_FETCHED_AT = longPreferencesKey("anthropic_model_catalog_fetched_at")
    }

    val agentListFlow: Flow<List<AgentConfig>> = store.data.map { prefs ->
        val json = prefs[Keys.AGENT_LIST_JSON] ?: return@map emptyList()
        val type = object : TypeToken<List<AgentConfig>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    val activeAgentIdFlow: Flow<String?> = store.data.map { it[Keys.ACTIVE_AGENT_ID] }

    val bubbleEnabledFlow: Flow<Boolean> = store.data.map { prefs ->
        if (prefs[Keys.BUBBLE_USER_SET] == true) {
            prefs[Keys.BUBBLE_ENABLED] ?: true
        } else {
            true
        }
    }

    val miniChatEntryTipDismissedFlow: Flow<Boolean> =
        store.data.map { it[Keys.MINI_CHAT_ENTRY_TIP_DISMISSED] ?: false }

    val miniChatMinimizeTipDismissedFlow: Flow<Boolean> =
        store.data.map { it[Keys.MINI_CHAT_MINIMIZE_TIP_DISMISSED] ?: false }

    val colorThemeFlow: Flow<AppColorTheme> = store.data.map {
        AppColorTheme.fromKey(it[Keys.COLOR_THEME] ?: AppColorTheme.DEFAULT.name)
    }

    val openRouterCatalogFlow: Flow<OpenRouterCatalog> = store.data.map { prefs ->
        val json = prefs[Keys.OPENROUTER_CATALOG_JSON]
        if (json.isNullOrBlank()) {
            OpenRouterCatalog()
        } else {
            gson.fromJson(json, OpenRouterCatalog::class.java)?.copy(
                fetchedAt = prefs[Keys.OPENROUTER_CATALOG_FETCHED_AT] ?: 0L
            ) ?: OpenRouterCatalog()
        }
    }

    val openAiModelCatalogFlow: Flow<ProviderModelCatalog> = store.data.map { prefs ->
        val json = prefs[Keys.OPENAI_MODEL_CATALOG_JSON]
        if (json.isNullOrBlank()) {
            ProviderModelCatalog()
        } else {
            gson.fromJson(json, ProviderModelCatalog::class.java)?.copy(
                fetchedAt = prefs[Keys.OPENAI_MODEL_CATALOG_FETCHED_AT] ?: 0L
            ) ?: ProviderModelCatalog()
        }
    }

    val anthropicModelCatalogFlow: Flow<ProviderModelCatalog> = store.data.map { prefs ->
        val json = prefs[Keys.ANTHROPIC_MODEL_CATALOG_JSON]
        if (json.isNullOrBlank()) {
            ProviderModelCatalog()
        } else {
            gson.fromJson(json, ProviderModelCatalog::class.java)?.copy(
                fetchedAt = prefs[Keys.ANTHROPIC_MODEL_CATALOG_FETCHED_AT] ?: 0L
            ) ?: ProviderModelCatalog()
        }
    }

    suspend fun setColorTheme(theme: AppColorTheme) {
        store.edit { it[Keys.COLOR_THEME] = theme.name }
    }

    val bubblePosFlow: Flow<Pair<Int, Int>> = store.data.map { prefs ->
        Pair(prefs[Keys.BUBBLE_POS_X] ?: 0, prefs[Keys.BUBBLE_POS_Y] ?: 300)
    }

    suspend fun saveAgentList(agents: List<AgentConfig>) {
        store.edit { it[Keys.AGENT_LIST_JSON] = gson.toJson(agents) }
    }

    suspend fun setActiveAgentId(id: String?) {
        store.edit {
            if (id == null) it.remove(Keys.ACTIVE_AGENT_ID)
            else it[Keys.ACTIVE_AGENT_ID] = id
        }
    }

    suspend fun setBubbleEnabled(enabled: Boolean) {
        store.edit {
            it[Keys.BUBBLE_ENABLED] = enabled
            it[Keys.BUBBLE_USER_SET] = true
        }
    }

    suspend fun initializeMiniChatDefaultIfNeeded() {
        store.edit { prefs ->
            if (prefs[Keys.BUBBLE_DEFAULT_INITIALIZED] == true) return@edit
            if (!prefs.contains(Keys.BUBBLE_ENABLED)) {
                prefs[Keys.BUBBLE_ENABLED] = true
            }
            if (!prefs.contains(Keys.BUBBLE_USER_SET)) {
                prefs[Keys.BUBBLE_USER_SET] = false
            }
            prefs[Keys.BUBBLE_DEFAULT_INITIALIZED] = true
        }
    }

    suspend fun setMiniChatEntryTipDismissed(dismissed: Boolean) {
        store.edit { it[Keys.MINI_CHAT_ENTRY_TIP_DISMISSED] = dismissed }
    }

    suspend fun setMiniChatMinimizeTipDismissed(dismissed: Boolean) {
        store.edit { it[Keys.MINI_CHAT_MINIMIZE_TIP_DISMISSED] = dismissed }
    }

    suspend fun saveBubblePosition(x: Int, y: Int) {
        store.edit {
            it[Keys.BUBBLE_POS_X] = x
            it[Keys.BUBBLE_POS_Y] = y
        }
    }

    suspend fun saveOpenRouterCatalog(catalog: OpenRouterCatalog) {
        store.edit {
            it[Keys.OPENROUTER_CATALOG_JSON] = gson.toJson(catalog)
            it[Keys.OPENROUTER_CATALOG_FETCHED_AT] = catalog.fetchedAt
        }
    }

    suspend fun saveProviderModelCatalog(provider: AiProviderType, catalog: ProviderModelCatalog) {
        store.edit {
            when (provider) {
                AiProviderType.OPENAI -> {
                    it[Keys.OPENAI_MODEL_CATALOG_JSON] = gson.toJson(catalog)
                    it[Keys.OPENAI_MODEL_CATALOG_FETCHED_AT] = catalog.fetchedAt
                }
                AiProviderType.ANTHROPIC -> {
                    it[Keys.ANTHROPIC_MODEL_CATALOG_JSON] = gson.toJson(catalog)
                    it[Keys.ANTHROPIC_MODEL_CATALOG_FETCHED_AT] = catalog.fetchedAt
                }
                AiProviderType.OPENROUTER,
                AiProviderType.CUSTOM -> Unit
            }
        }
    }
}
