package com.example.uai.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.uai.data.model.AgentConfig
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
        val BUBBLE_POS_X = intPreferencesKey("bubble_pos_x")
        val BUBBLE_POS_Y = intPreferencesKey("bubble_pos_y")
    }

    val agentListFlow: Flow<List<AgentConfig>> = store.data.map { prefs ->
        val json = prefs[Keys.AGENT_LIST_JSON] ?: return@map emptyList()
        val type = object : TypeToken<List<AgentConfig>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    val activeAgentIdFlow: Flow<String?> = store.data.map { it[Keys.ACTIVE_AGENT_ID] }

    val bubbleEnabledFlow: Flow<Boolean> = store.data.map { it[Keys.BUBBLE_ENABLED] ?: false }

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
        store.edit { it[Keys.BUBBLE_ENABLED] = enabled }
    }

    suspend fun saveBubblePosition(x: Int, y: Int) {
        store.edit {
            it[Keys.BUBBLE_POS_X] = x
            it[Keys.BUBBLE_POS_Y] = y
        }
    }
}
