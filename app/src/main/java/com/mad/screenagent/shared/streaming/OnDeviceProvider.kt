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
            val hasImages = messages.any { it.images.isNotEmpty() }
            val totalImages = messages.sumOf { it.images.size }
            val hasDocumentContext = messages.any {
                it.fileAttachment != null || !it.documentBase64.isNullOrBlank()
            }
            if (hasImages && totalImages > 1) {
                emit(StreamChunk.Error(IllegalStateException(OnDeviceUserMessages.singleImageOnly())))
                return@flow
            }
            if (hasImages && hasDocumentContext) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(OnDeviceUserMessages.mixedImageAndDocumentUnsupported())
                    )
                )
                return@flow
            }

            val visionProjectorPath = installed.visionProjectorPath?.takeIf { it.isNotBlank() }
            if (hasImages && visionProjectorPath == null) {
                val reason = OnDeviceUserMessages.imageAttachmentsRequireVisionModel()
                modelRepository.updateVisionValidation(
                    modelId = modelId,
                    visionReady = false,
                    failureKind = OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE,
                    message = reason,
                    validatedRuntimeProfileId = runtime.runtimeProfileId
                )
                emit(StreamChunk.Error(IllegalStateException(reason)))
                return@flow
            }
            if (hasImages) {
                val projectorFile = File(visionProjectorPath!!)
                if (!projectorFile.exists() || projectorFile.length() == 0L) {
                    val reason = OnDeviceUserMessages.missingVisionSupportFile()
                    modelRepository.updateVisionValidation(
                        modelId = modelId,
                        visionReady = false,
                        failureKind = OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE,
                        message = reason,
                        validatedRuntimeProfileId = runtime.runtimeProfileId
                    )
                    emit(StreamChunk.Error(IllegalStateException(reason)))
                    return@flow
                }
            }
            if (installed.validatedRuntimeProfileId != null &&
                installed.validatedRuntimeProfileId != runtime.runtimeProfileId
            ) {
                val runtimeValidation = runtime.validateModel(
                    modelPath = installed.localPath
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
            if (hasImages &&
                (
                    !installed.visionReady ||
                        installed.validatedVisionRuntimeProfileId != runtime.runtimeProfileId
                    )
            ) {
                val safeVisionProjectorPath = visionProjectorPath
                if (safeVisionProjectorPath == null) {
                    emit(
                        StreamChunk.Error(
                            IllegalStateException(OnDeviceUserMessages.imageAttachmentsRequireVisionModel())
                        )
                    )
                    return@flow
                }
                val visionValidation = runtime.validateVisionModel(
                    modelPath = installed.localPath,
                    visionProjectorPath = safeVisionProjectorPath
                )
                modelRepository.updateVisionValidation(
                    modelId = modelId,
                    visionReady = visionValidation.isSuccess,
                    failureKind = visionValidation.failureKind,
                    message = if (visionValidation.isSuccess) null else visionValidation.message,
                    validatedRuntimeProfileId = runtime.runtimeProfileId
                )
                if (!visionValidation.isSuccess) {
                    emit(
                        StreamChunk.Error(
                            IllegalStateException(
                                visionValidation.message ?: OnDeviceUserMessages.imageSupportNotReady()
                            )
                        )
                    )
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
                visionProjectorPath = if (hasImages) visionProjectorPath else null
            ).collect { emit(it) }
        }.flowOn(Dispatchers.IO)
}
