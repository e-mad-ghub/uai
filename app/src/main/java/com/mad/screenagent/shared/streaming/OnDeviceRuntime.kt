package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

interface OnDeviceRuntime {
    fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String
    ): Flow<StreamChunk>
}

class UnavailableOnDeviceRuntime : OnDeviceRuntime {
    override fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String
    ): Flow<StreamChunk> = flow {
        emit(
            StreamChunk.Error(
                IllegalStateException(
                    "On-Device runtime is not wired yet. Install and wire a local inference backend for $modelPath."
                )
            )
        )
    }.flowOn(Dispatchers.IO)
}
