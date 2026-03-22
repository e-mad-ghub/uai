package com.mad.screenagent.feature.agora

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.repository.ConversationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AgoraListViewModel(
    private val repo: ConversationRepository
) : ViewModel() {

    val agoraRooms = repo.getAllConversations()
        .map { it.filter { c -> c.isAgora } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(conv: ConversationEntity) {
        viewModelScope.launch { repo.deleteConversation(conv) }
    }

    class Factory(private val repo: ConversationRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = AgoraListViewModel(repo) as T
    }
}
