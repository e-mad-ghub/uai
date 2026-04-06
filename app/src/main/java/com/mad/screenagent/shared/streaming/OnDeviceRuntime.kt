package com.mad.screenagent.shared.streaming

import android.content.Context
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

interface OnDeviceRuntime {
    val runtimeProfileId: String

    suspend fun validateModel(modelPath: String): OnDeviceValidationResult

    fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String
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
    private var loadedSystemPrompt: String? = null

    override suspend fun validateModel(modelPath: String): OnDeviceValidationResult {
        val modelFile = File(modelPath)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            return OnDeviceValidationResult.failure(
                OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE,
                OnDeviceUserMessages.missingModelFile()
            )
        }

        return loadMutex.withLock {
            try {
                if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
                    runCatching { engine.cleanUp() }
                }
                engine.loadModel(modelPath)
                runCatching { engine.cleanUp() }
                loadedModelPath = null
                loadedSystemPrompt = null
                OnDeviceValidationResult.success()
            } catch (t: Throwable) {
                runCatching { engine.cleanUp() }
                loadedModelPath = null
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
        modelPath: String
    ): Flow<StreamChunk> = flow {
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

            ensureLoaded(modelPath = modelPath, config = config)
            val prompt = buildOnDevicePrompt(messages, systemPrompt = "")
            Log.i(
                TAG,
                "REQUEST model=${File(modelPath).name} messages=${messages.size} prompt_chars=${prompt.length} max_output_tokens=${config.onDevice.maxOutputTokens}"
            )
            var firstTokenAt = 0L
            var tokenCount = 0
            engine.sendUserPrompt(
                message = prompt,
                predictLength = config.onDevice.maxOutputTokens
            ).collect { token ->
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
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun ensureLoaded(modelPath: String, config: AgentConfig) {
        val desiredSystemPrompt = config.systemPrompt.trim()
        loadMutex.withLock {
            val currentState = engine.state.value
            if (currentState is InferenceEngine.State.Error) {
                runCatching { engine.cleanUp() }
            }

            val needsReload = loadedModelPath != modelPath ||
                loadedSystemPrompt != desiredSystemPrompt ||
                !engine.state.value.isModelLoaded

            if (!needsReload) return

            if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
                runCatching { engine.cleanUp() }
            }

            engine.loadModel(modelPath)
            if (desiredSystemPrompt.isNotBlank()) {
                engine.setSystemPrompt(desiredSystemPrompt)
            }
            loadedModelPath = modelPath
            loadedSystemPrompt = desiredSystemPrompt
        }
    }
}

private fun buildOnDevicePrompt(messages: List<ChatMessage>, systemPrompt: String): String =
    buildString {
        if (systemPrompt.isNotBlank()) {
            appendLine("System: ${systemPrompt.trim()}")
            appendLine()
        }

        messages.forEach { message ->
            val text = message.contentWithFileContext().trim().ifBlank {
                if (message.images.isNotEmpty()) {
                    "[Image attachment omitted in this on-device build]"
                } else {
                    ""
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
