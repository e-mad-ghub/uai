package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.repository.OnDeviceModelSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

class OnDeviceProvider(
    private val modelRepository: OnDeviceModelSource,
    private val runtime: OnDeviceRuntime = UnavailableOnDeviceRuntime()
) : AiProvider {

    override fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> =
        flow {
            val modelId = config.onDevice.selectedModelId.takeIf { it.isNotBlank() } ?: config.model.trim()
            if (modelId.isBlank()) {
                emit(StreamChunk.Error(IllegalStateException("Choose an On-Device model before sending a message.")))
                return@flow
            }

            val installed = modelRepository.getInstalledModel(modelId)
            if (installed == null) {
                emit(StreamChunk.Error(IllegalStateException("The selected On-Device model is not installed yet.")))
                return@flow
            }

            runtime.streamResponse(
                messages = messages,
                config = config.copy(
                    model = modelId,
                    onDevice = config.onDevice.copy(selectedModelId = modelId)
                ),
                modelPath = installed.localPath
            ).collect { emit(it) }
        }.flowOn(Dispatchers.IO)
}
