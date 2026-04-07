package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.OnDeviceFailureKind
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.looksLikeVisionCapableOnDeviceModel
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
                emit(StreamChunk.Error(IllegalStateException(OnDeviceUserMessages.chooseModel())))
                return@flow
            }

            val installed = modelRepository.getInstalledModel(modelId)
            if (installed == null) {
                emit(StreamChunk.Error(IllegalStateException(OnDeviceUserMessages.downloadModelFirst())))
                return@flow
            }
            if (installed.downloadState == OnDeviceDownloadState.DOWNLOADING ||
                installed.downloadState == OnDeviceDownloadState.VALIDATING
            ) {
                emit(StreamChunk.Error(IllegalStateException(OnDeviceUserMessages.modelStillDownloading())))
                return@flow
            }
            if (!installed.downloadState.isReadyForUse) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(
                            OnDeviceUserMessages.validationMessage(
                                installed.failureKind,
                                installed.errorMessage
                            )
                        )
                    )
                )
                return@flow
            }
            val modelFile = File(installed.localPath)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                val reason = OnDeviceUserMessages.missingModelFile()
                modelRepository.markModelUnavailable(modelId, reason, OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE)
                emit(StreamChunk.Error(IllegalStateException(reason)))
                return@flow
            }
            val visionProjectorPath = installed.visionProjectorPath?.takeIf { it.isNotBlank() }
            val runtimeSupportsVision = config.onDevice.selectedModelSupportsVision ||
                visionProjectorPath != null ||
                looksLikeVisionCapableOnDeviceModel(modelId)
            if (messages.any { it.images.isNotEmpty() } && !runtimeSupportsVision) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(OnDeviceUserMessages.imageAttachmentsRequireVisionModel())
                    )
                )
                return@flow
            }
            if (runtimeSupportsVision && visionProjectorPath == null) {
                val reason = OnDeviceUserMessages.missingModelFile()
                modelRepository.markModelUnavailable(modelId, reason, OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE)
                emit(StreamChunk.Error(IllegalStateException(reason)))
                return@flow
            }
            if (installed.validatedRuntimeProfileId != null &&
                installed.validatedRuntimeProfileId != runtime.runtimeProfileId
            ) {
                val runtimeValidation = runtime.validateModel(
                    modelPath = installed.localPath,
                    visionProjectorPath = visionProjectorPath
                )
                if (!runtimeValidation.isSuccess) {
                    val reason = runtimeValidation.message ?: OnDeviceUserMessages.validationMessage(
                        runtimeValidation.failureKind
                    )
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
                modelPath = installed.localPath,
                visionProjectorPath = visionProjectorPath
            ).collect { emit(it) }
        }.flowOn(Dispatchers.IO)
}
