package com.mad.screenagent.shared.streaming

import android.content.Context
import android.util.Base64
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.mad.screenagent.data.model.OnDeviceFailureKind
import com.arm.aichat.isModelLoaded
import com.mad.screenagent.data.model.AgentConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

interface OnDeviceRuntime {
    val runtimeProfileId: String

    suspend fun validateModel(modelPath: String, visionProjectorPath: String? = null): OnDeviceValidationResult

    fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String,
        visionProjectorPath: String? = null
    ): Flow<StreamChunk>
}

data class OnDeviceValidationResult(
    val isSuccess: Boolean,
    val failureKind: OnDeviceFailureKind = OnDeviceFailureKind.NONE,
    val message: String? = null
) {
    companion object {
        fun success(): OnDeviceValidationResult = OnDeviceValidationResult(isSuccess = true)

        fun failure(
            failureKind: OnDeviceFailureKind,
            message: String
        ): OnDeviceValidationResult = OnDeviceValidationResult(
            isSuccess = false,
            failureKind = failureKind,
            message = message
        )
    }
}

class LlamaCppOnDeviceRuntime(
    context: Context
) : OnDeviceRuntime {
    private companion object {
        private const val TAG = "OnDevicePerf"
    }

    override val runtimeProfileId: String =
        "llama.android-af76639-arm64-v8a-kleidiai-openmp"

    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    private val engine by lazy { AiChat.getInferenceEngine(appContext) }
    private var loadedModelPath: String? = null
    private var loadedVisionProjectorPath: String? = null
    private var loadedSystemPrompt: String? = null

    override suspend fun validateModel(
        modelPath: String,
        visionProjectorPath: String?
    ): OnDeviceValidationResult {
        val modelFile = File(modelPath)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            return OnDeviceValidationResult.failure(
                OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE,
                OnDeviceUserMessages.missingModelFile()
            )
        }
        if (visionProjectorPath != null) {
            val projectorFile = File(visionProjectorPath)
            if (!projectorFile.exists() || projectorFile.length() == 0L) {
                return OnDeviceValidationResult.failure(
                    OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE,
                    OnDeviceUserMessages.missingModelFile()
                )
            }
        }

        return loadMutex.withLock {
            try {
                if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
                    runCatching { engine.cleanUp() }
                }
                engine.loadModel(modelPath, visionProjectorPath)
                runCatching { engine.cleanUp() }
                loadedModelPath = null
                loadedVisionProjectorPath = null
                loadedSystemPrompt = null
                OnDeviceValidationResult.success()
            } catch (t: Throwable) {
                runCatching { engine.cleanUp() }
                loadedModelPath = null
                loadedVisionProjectorPath = null
                loadedSystemPrompt = null
                val cause = t.unwrapOnDeviceThrowable()
                OnDeviceValidationResult.failure(
                    failureKind = cause.toFailureKind(),
                    message = OnDeviceUserMessages.validationMessage(
                        cause.toFailureKind(),
                        cause.message
                    )
                )
            }
        }
    }

    override fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String,
        visionProjectorPath: String?
    ): Flow<StreamChunk> = flow {
        val tempImageFiles = mutableListOf<File>()
        try {
            val startedAt = System.nanoTime()
            val modelFile = File(modelPath)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(OnDeviceUserMessages.missingModelFile())
                    )
                )
                return@flow
            }

            ensureLoaded(modelPath = modelPath, visionProjectorPath = visionProjectorPath, config = config)
            val visionImages = messages.flatMap { it.images }
            if (visionImages.isNotEmpty() && visionProjectorPath == null) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(OnDeviceUserMessages.imageAttachmentsRequireVisionModel())
                    )
                )
                return@flow
            }
            val promptBundle = if (visionProjectorPath != null && visionImages.isNotEmpty()) {
                buildOnDeviceVisionPrompt(
                    messages = messages,
                    systemPrompt = "",
                    tempRootDir = File(appContext.cacheDir, "on-device-vision")
                ).also { tempImageFiles.addAll(it.imagePaths) }
            } else {
                buildOnDevicePrompt(messages = messages, systemPrompt = "")
            }
            val effectivePredictLength = resolveOnDevicePredictLength(messages, config)
            Log.i(
                TAG,
                "REQUEST model=${File(modelPath).name} messages=${messages.size} prompt_chars=${promptBundle.prompt.length} max_output_tokens=${config.onDevice.maxOutputTokens} effective_predict_length=$effectivePredictLength"
            )
            var firstTokenAt = 0L
            var tokenCount = 0
            val tokenFlow = if (promptBundle.imagePaths.isNotEmpty()) {
                engine.sendUserPromptWithImages(
                    message = promptBundle.prompt,
                    imagePaths = promptBundle.imagePaths.map { it.absolutePath },
                    predictLength = effectivePredictLength
                )
            } else {
                engine.sendUserPrompt(
                    message = promptBundle.prompt,
                    predictLength = effectivePredictLength
                )
            }
            tokenFlow.collect { token ->
                if (token.isNotBlank()) {
                    if (firstTokenAt == 0L) {
                        firstTokenAt = System.nanoTime()
                        Log.i(TAG, "TTFT ms=${(firstTokenAt - startedAt) / 1_000_000L}")
                    }
                    tokenCount += 1
                    emit(StreamChunk.Token(token))
                }
            }
            Log.i(
                TAG,
                "COMPLETE total_ms=${(System.nanoTime() - startedAt) / 1_000_000L} output_tokens=$tokenCount model=${File(modelPath).name}"
            )
            emit(StreamChunk.Done)
        } catch (t: Throwable) {
            emit(StreamChunk.Error(t.unwrapOnDeviceThrowable()))
        } finally {
            tempImageFiles.forEach { file ->
                runCatching { file.delete() }
                runCatching { file.parentFile?.deleteRecursively() }
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun ensureLoaded(modelPath: String, visionProjectorPath: String?, config: AgentConfig) {
        val desiredSystemPrompt = config.systemPrompt.trim()
        loadMutex.withLock {
            val currentState = engine.state.value
            if (currentState is InferenceEngine.State.Error) {
                runCatching { engine.cleanUp() }
            }

            val needsReload = loadedModelPath != modelPath ||
                loadedVisionProjectorPath != visionProjectorPath ||
                loadedSystemPrompt != desiredSystemPrompt ||
                !engine.state.value.isModelLoaded

            if (!needsReload) return

            if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
                runCatching { engine.cleanUp() }
            }

            engine.loadModel(modelPath, visionProjectorPath)
            if (desiredSystemPrompt.isNotBlank()) {
                engine.setSystemPrompt(desiredSystemPrompt)
            }
            loadedModelPath = modelPath
            loadedVisionProjectorPath = visionProjectorPath
            loadedSystemPrompt = desiredSystemPrompt
        }
    }
}

private data class OnDevicePromptBundle(
    val prompt: String,
    val imagePaths: List<File>
)

private const val ON_DEVICE_ATTACHMENT_TEXT_LIMIT = 4_000
private const val ON_DEVICE_ATTACHMENT_TRUNCATION_NOTE =
    "\n\n[Attachment text truncated for on-device processing.]"

private fun buildOnDevicePrompt(messages: List<ChatMessage>, systemPrompt: String): OnDevicePromptBundle =
    buildOnDevicePrompt(messages, systemPrompt, includeImageMarkers = false, tempRootDir = null)

private fun buildOnDeviceVisionPrompt(
    messages: List<ChatMessage>,
    systemPrompt: String,
    tempRootDir: File
): OnDevicePromptBundle =
    buildOnDevicePrompt(messages, systemPrompt, includeImageMarkers = true, tempRootDir = tempRootDir)

private fun buildOnDevicePrompt(
    messages: List<ChatMessage>,
    systemPrompt: String,
    includeImageMarkers: Boolean,
    tempRootDir: File?
): OnDevicePromptBundle {
    val tempImageFiles = mutableListOf<File>()
    val promptText = buildString {
        if (systemPrompt.isNotBlank()) {
            appendLine("System: ${systemPrompt.trim()}")
            appendLine()
        }

        messages.forEach { message ->
            val baseText = message.contentWithFileContext()
                .trim()
                .limitOnDeviceAttachmentText()
            val text = if (includeImageMarkers && message.images.isNotEmpty()) {
                buildString {
                    if (baseText.isNotBlank()) {
                        append(baseText)
                        appendLine()
                    }
                    message.images.forEachIndexed { index, _ ->
                        if (index > 0) appendLine()
                        appendLine(mtmdMarker())
                    }
                }.trim()
            } else {
                baseText.ifBlank {
                    if (message.images.isNotEmpty()) {
                        "[Image attachment omitted in this on-device build]"
                    } else {
                        ""
                    }
                }
            }
            if (text.isBlank()) return@forEach

            val roleLabel = when (message.role.lowercase()) {
                "user" -> "User"
                "assistant" -> "Assistant"
                "system" -> "System"
                else -> message.role.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                }
            }
            appendLine("$roleLabel: $text")
        }

        append("Assistant:")
    }

    if (includeImageMarkers) {
        val baseDir = tempRootDir ?: File("/data/local/tmp")
        messages.forEach { message ->
            message.images.forEach { image ->
                val bytes = runCatching {
                    Base64.decode(image.base64, Base64.DEFAULT)
                }.getOrNull()
                if (bytes != null) {
                    val ext = image.mimeType.lowercase().let { mime ->
                        when {
                            mime.contains("png") -> ".png"
                            mime.contains("webp") -> ".webp"
                            mime.contains("gif") -> ".gif"
                            else -> ".jpg"
                        }
                    }
                    val tempDir = File(baseDir, UUID.randomUUID().toString())
                    tempDir.mkdirs()
                    val file = File(tempDir, "image_${tempImageFiles.size}$ext")
                    file.writeBytes(bytes)
                    tempImageFiles.add(file)
                }
            }
        }
    }

    return OnDevicePromptBundle(prompt = promptText, imagePaths = tempImageFiles)
}

private fun mtmdMarker(): String = "<__media__>"

private fun resolveOnDevicePredictLength(messages: List<ChatMessage>, config: AgentConfig): Int {
    val hasAttachments = messages.any { message ->
        message.images.isNotEmpty() ||
            message.fileAttachment != null ||
            !message.documentBase64.isNullOrBlank()
    }
    val configured = config.onDevice.maxOutputTokens.coerceAtLeast(1)
    return if (hasAttachments) configured.coerceAtMost(128) else configured
}

private fun String.limitOnDeviceAttachmentText(): String {
    if (length <= ON_DEVICE_ATTACHMENT_TEXT_LIMIT) return this
    return take(ON_DEVICE_ATTACHMENT_TEXT_LIMIT) + ON_DEVICE_ATTACHMENT_TRUNCATION_NOTE
}

private fun Throwable.unwrapOnDeviceThrowable(): Throwable =
    (cause ?: this).takeUnless { it === this }?.withFallbackMessage() ?: this.withFallbackMessage()

private fun Throwable.toFailureKind(): OnDeviceFailureKind {
    val message = message.orEmpty()
    return when {
        message.contains("not a valid GGUF", ignoreCase = true) -> OnDeviceFailureKind.INVALID_GGUF
        message.contains("could not be opened by the on-device llama runtime", ignoreCase = true) ||
            message.contains("cannot be opened by the on-device llama runtime", ignoreCase = true) ->
            OnDeviceFailureKind.RUNTIME_INCOMPATIBLE
        message.contains("missing", ignoreCase = true) || message.contains("empty", ignoreCase = true) ->
            OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE
        else -> OnDeviceFailureKind.INTERNAL_RUNTIME_ERROR
    }
}

private fun Throwable.withFallbackMessage(): Throwable =
    if (!message.isNullOrBlank()) {
        this
    } else {
        IllegalStateException(
            OnDeviceUserMessages.runtimeUnavailable(),
            this
        )
    }
