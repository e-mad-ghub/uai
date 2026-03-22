package com.example.uai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.uai.data.model.AppColorTheme
import com.example.uai.data.repository.AgentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: AgentRepository) : ViewModel() {

    val bubbleEnabled = repo.bubbleEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val colorTheme = repo.colorThemeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppColorTheme.DEFAULT)

    fun setBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch { repo.setBubbleEnabled(enabled) }
    }

    fun setColorTheme(theme: AppColorTheme) {
        viewModelScope.launch { repo.setColorTheme(theme) }
    }

    class Factory(private val repo: AgentRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = SettingsViewModel(repo) as T
    }
}
