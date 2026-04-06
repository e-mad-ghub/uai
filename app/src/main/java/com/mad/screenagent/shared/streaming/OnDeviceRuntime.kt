package com.mad.screenagent.shared.streaming

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
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
    suspend fun validateModel(modelPath: String): String?

    fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String
    ): Flow<StreamChunk>
}

class LlamaCppOnDeviceRuntime(
    context: Context
) : OnDeviceRuntime {
    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    private val engine by lazy { AiChat.getInferenceEngine(appContext) }
    private var loadedModelPath: String? = null
    private var loadedSystemPrompt: String? = null

    override suspend fun validateModel(modelPath: String): String? {
        val modelFile = File(modelPath)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            return "The On-Device GGUF model file is missing at $modelPath."
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
                null
            } catch (t: Throwable) {
                runCatching { engine.cleanUp() }
                loadedModelPath = null
                loadedSystemPrompt = null
                t.unwrapOnDeviceThrowable().message
                    ?: "The selected GGUF model could not be opened by the on-device llama runtime."
            }
        }
    }

    override fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String
    ): Flow<StreamChunk> = flow {
        try {
            val modelFile = File(modelPath)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException("On-Device GGUF model file is missing at $modelPath.")
                    )
                )
                return@flow
            }

            ensureLoaded(modelPath = modelPath, config = config)
            val prompt = buildOnDevicePrompt(messages, systemPrompt = "")
            engine.sendUserPrompt(
                message = prompt,
                predictLength = config.onDevice.maxOutputTokens
            ).collect { token ->
                if (token.isNotBlank()) {
                    emit(StreamChunk.Token(token))
                }
            }
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

private fun Throwable.withFallbackMessage(): Throwable =
    if (!message.isNullOrBlank()) {
        this
    } else {
        IllegalStateException(
            "The on-device llama runtime failed to generate a response.",
            this
        )
    }
