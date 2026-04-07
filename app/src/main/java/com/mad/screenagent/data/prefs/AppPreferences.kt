package com.mad.screenagent.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.AppColorTheme
import com.mad.screenagent.data.model.InstalledOnDeviceModel
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceModelCatalog
import com.mad.screenagent.data.model.OpenRouterCatalog
import com.mad.screenagent.data.model.ProviderModelCatalog
import com.mad.screenagent.data.model.normalizeOnDeviceCatalog
import com.mad.screenagent.data.model.QuickActionConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val BUBBLE_POS_PORTRAIT_X = intPreferencesKey("bubble_pos_portrait_x")
        val BUBBLE_POS_PORTRAIT_Y = intPreferencesKey("bubble_pos_portrait_y")
        val BUBBLE_POS_WIDE_X = intPreferencesKey("bubble_pos_wide_x")
        val BUBBLE_POS_WIDE_Y = intPreferencesKey("bubble_pos_wide_y")
        val COLOR_THEME = stringPreferencesKey("color_theme")
        val MINI_CHAT_ENTRY_TIP_DISMISSED = booleanPreferencesKey("mini_chat_entry_tip_dismissed")
        val MINI_CHAT_MINIMIZE_TIP_DISMISSED = booleanPreferencesKey("mini_chat_minimize_tip_dismissed")
        val OPENROUTER_CATALOG_JSON = stringPreferencesKey("openrouter_catalog_json")
        val OPENROUTER_CATALOG_FETCHED_AT = longPreferencesKey("openrouter_catalog_fetched_at")
        val OPENAI_MODEL_CATALOG_JSON = stringPreferencesKey("openai_model_catalog_json")
        val OPENAI_MODEL_CATALOG_FETCHED_AT = longPreferencesKey("openai_model_catalog_fetched_at")
        val ANTHROPIC_MODEL_CATALOG_JSON = stringPreferencesKey("anthropic_model_catalog_json")
        val ANTHROPIC_MODEL_CATALOG_FETCHED_AT = longPreferencesKey("anthropic_model_catalog_fetched_at")
        val ON_DEVICE_MODEL_CATALOG_JSON = stringPreferencesKey("on_device_model_catalog_json")
        val ON_DEVICE_MODEL_CATALOG_FETCHED_AT = longPreferencesKey("on_device_model_catalog_fetched_at")
        val ON_DEVICE_INSTALLED_MODELS_JSON = stringPreferencesKey("on_device_installed_models_json")
        val ON_DEVICE_DOWNLOAD_STATE_JSON = stringPreferencesKey("on_device_download_state_json")
        val QUICK_ACTIONS_JSON = stringPreferencesKey("quick_actions_json")
        val LAST_ACTIVE_BUBBLE_CONVERSATION_ID = stringPreferencesKey("last_active_bubble_conversation_id")
    }

    private fun normalizeAgentList(agents: List<AgentConfig>): List<AgentConfig> =
        agents.distinctBy { it.id }

    val agentListFlow: Flow<List<AgentConfig>> = store.data.map { prefs ->
        val json = prefs[Keys.AGENT_LIST_JSON] ?: return@map emptyList()
        val type = object : TypeToken<List<AgentConfig>>() {}.type
        val agents = gson.fromJson<List<AgentConfig>>(json, type) ?: emptyList()
        normalizeAgentList(agents)
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

    val onDeviceModelCatalogFlow: Flow<OnDeviceModelCatalog> = store.data.map { prefs ->
        val json = prefs[Keys.ON_DEVICE_MODEL_CATALOG_JSON]
        val catalog = if (json.isNullOrBlank()) {
            OnDeviceModelCatalog()
        } else {
            gson.fromJson(json, OnDeviceModelCatalog::class.java)?.copy(
                fetchedAt = prefs[Keys.ON_DEVICE_MODEL_CATALOG_FETCHED_AT] ?: 0L
            ) ?: OnDeviceModelCatalog()
        }
        normalizeOnDeviceCatalog(catalog)
    }

    val installedOnDeviceModelsFlow: Flow<List<InstalledOnDeviceModel>> = store.data.map { prefs ->
        val json = prefs[Keys.ON_DEVICE_INSTALLED_MODELS_JSON]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            val type = object : TypeToken<List<InstalledOnDeviceModel>>() {}.type
            gson.fromJson<List<InstalledOnDeviceModel>>(json, type) ?: emptyList()
        }
    }

    val onDeviceDownloadStateFlow: Flow<OnDeviceDownloadState> = store.data.map { prefs ->
        val json = prefs[Keys.ON_DEVICE_DOWNLOAD_STATE_JSON]
        if (json.isNullOrBlank()) {
            OnDeviceDownloadState.NOT_DOWNLOADED
        } else {
            gson.fromJson(json, OnDeviceDownloadState::class.java) ?: OnDeviceDownloadState.NOT_DOWNLOADED
        }
    }

    suspend fun setColorTheme(theme: AppColorTheme) {
        store.edit { it[Keys.COLOR_THEME] = theme.name }
    }

    val bubblePosFlow: Flow<Pair<Int, Int>> = store.data.map { prefs ->
        Pair(prefs[Keys.BUBBLE_POS_X] ?: 0, prefs[Keys.BUBBLE_POS_Y] ?: 300)
    }

    suspend fun saveAgentList(agents: List<AgentConfig>) {
        store.edit { it[Keys.AGENT_LIST_JSON] = gson.toJson(normalizeAgentList(agents)) }
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

    suspend fun getBubblePositionForMode(isWideMode: Boolean): Pair<Int, Int>? {
        val prefs = store.data.first()
        val xKey = if (isWideMode) Keys.BUBBLE_POS_WIDE_X else Keys.BUBBLE_POS_PORTRAIT_X
        val yKey = if (isWideMode) Keys.BUBBLE_POS_WIDE_Y else Keys.BUBBLE_POS_PORTRAIT_Y
        val modeX = prefs[xKey]
        val modeY = prefs[yKey]
        if (modeX != null && modeY != null) {
            return modeX to modeY
        }
        return null
    }

    suspend fun getLegacyBubblePosition(): Pair<Int, Int>? {
        val prefs = store.data.first()
        val legacyX = prefs[Keys.BUBBLE_POS_X]
        val legacyY = prefs[Keys.BUBBLE_POS_Y]
        return if (legacyX != null && legacyY != null) {
            legacyX to legacyY
        } else {
            null
        }
    }

    suspend fun saveBubblePositionForMode(x: Int, y: Int, isWideMode: Boolean) {
        store.edit {
            val xKey = if (isWideMode) Keys.BUBBLE_POS_WIDE_X else Keys.BUBBLE_POS_PORTRAIT_X
            val yKey = if (isWideMode) Keys.BUBBLE_POS_WIDE_Y else Keys.BUBBLE_POS_PORTRAIT_Y
            it[xKey] = x
            it[yKey] = y
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
                AiProviderType.ON_DEVICE,
                AiProviderType.OPENROUTER,
                AiProviderType.CUSTOM -> Unit
            }
        }
    }

    suspend fun saveOnDeviceModelCatalog(catalog: OnDeviceModelCatalog) {
        store.edit {
            it[Keys.ON_DEVICE_MODEL_CATALOG_JSON] = gson.toJson(catalog)
            it[Keys.ON_DEVICE_MODEL_CATALOG_FETCHED_AT] = catalog.fetchedAt
        }
    }

    suspend fun saveInstalledOnDeviceModels(models: List<InstalledOnDeviceModel>) {
        store.edit {
            it[Keys.ON_DEVICE_INSTALLED_MODELS_JSON] = gson.toJson(models)
        }
    }

    suspend fun saveOnDeviceDownloadState(state: OnDeviceDownloadState) {
        store.edit {
            it[Keys.ON_DEVICE_DOWNLOAD_STATE_JSON] = gson.toJson(state)
        }
    }

    // ----- Quick Actions -----

    val quickActionsFlow: Flow<List<QuickActionConfig>> = store.data.map { prefs ->
        val json = prefs[Keys.QUICK_ACTIONS_JSON] ?: return@map emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<QuickActionConfig>>() {}.type
        val actions: List<QuickActionConfig> = gson.fromJson(json, type) ?: emptyList()
        // Migrate legacy actions that pre-date the slotIndex field: assign their current
        // list position as an explicit slot so deleting one never shifts the others.
        if (actions.any { it.slotIndex == null }) {
            actions.mapIndexed { i, a -> if (a.slotIndex == null) a.copy(slotIndex = i) else a }
        } else {
            actions
        }
    }

    suspend fun saveQuickActions(actions: List<QuickActionConfig>) {
        store.edit { it[Keys.QUICK_ACTIONS_JSON] = gson.toJson(actions) }
    }

    // ----- Last active bubble conversation -----

    val lastActiveBubbleConversationIdFlow: Flow<String?> =
        store.data.map { it[Keys.LAST_ACTIVE_BUBBLE_CONVERSATION_ID] }

    suspend fun saveLastActiveBubbleConversationId(id: String?) {
        store.edit {
            if (id == null) it.remove(Keys.LAST_ACTIVE_BUBBLE_CONVERSATION_ID)
            else it[Keys.LAST_ACTIVE_BUBBLE_CONVERSATION_ID] = id
        }
    }
}
