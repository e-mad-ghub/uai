package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: String, val content: String)

interface AiProvider {
    /**
     * Returns a cold Flow that streams the AI response token-by-token.
     * Emits StreamChunk.Token for each fragment, StreamChunk.Done on completion,
     * StreamChunk.Error on failure. The Flow runs on Dispatchers.IO.
     */
    fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk>
}
