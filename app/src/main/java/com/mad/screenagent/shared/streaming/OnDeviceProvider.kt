package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.OnDeviceFailureKind
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.repository.OnDeviceModelSource
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

class OnDeviceProvider(
    private val modelRepository: OnDeviceModelSource,
    private val runtime: OnDeviceRuntime
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
            if (installed.downloadState == OnDeviceDownloadState.DOWNLOADING ||
                installed.downloadState == OnDeviceDownloadState.VALIDATING
            ) {
                emit(StreamChunk.Error(IllegalStateException("The selected On-Device model is still downloading.")))
                return@flow
            }
            if (!installed.downloadState.isReadyForUse) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(
                            installed.errorMessage ?: "The selected On-Device model is not ready yet."
                        )
                    )
                )
                return@flow
            }
            if (!File(installed.localPath).exists() || File(installed.localPath).length() == 0L) {
                val reason = "The selected On-Device model file is missing at ${installed.localPath}."
                modelRepository.markModelUnavailable(modelId, reason, OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE)
                emit(StreamChunk.Error(IllegalStateException(reason)))
                return@flow
            }
            if (installed.validatedRuntimeProfileId != null &&
                installed.validatedRuntimeProfileId != runtime.runtimeProfileId
            ) {
                val runtimeValidation = runtime.validateModel(installed.localPath)
                if (!runtimeValidation.isSuccess) {
                    val reason = runtimeValidation.message ?: "The selected On-Device model is runtime incompatible on this device."
                    modelRepository.markModelUnavailable(
                        modelId,
                        reason,
                        runtimeValidation.failureKind
                    )
                    emit(StreamChunk.Error(IllegalStateException(reason)))
                    return@flow
                }
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
