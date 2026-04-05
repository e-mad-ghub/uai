package com.mad.screenagent.shared.streaming

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.mad.screenagent.data.model.AgentConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

interface OnDeviceRuntime {
    fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String
    ): Flow<StreamChunk>
}

class MediaPipeOnDeviceRuntime(
    private val context: Context
) : OnDeviceRuntime {

    override fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String
    ): Flow<StreamChunk> = callbackFlow {
        val modelFile = File(modelPath)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            trySend(
                StreamChunk.Error(
                    IllegalStateException("On-Device model file is missing at $modelPath.")
                )
            )
            close()
            return@callbackFlow
        }

        val inference = try {
            LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(config.onDevice.maxOutputTokens)
                    .setMaxTopK(config.onDevice.topK)
                    .build()
            )
        } catch (t: Throwable) {
            trySend(StreamChunk.Error(t.unwrapOnDeviceThrowable()))
            close()
            return@callbackFlow
        }

        val session = try {
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(config.onDevice.topK)
                .setTopP(0.95f)
                .setTemperature(config.onDevice.temperature)
                .setRandomSeed(config.onDevice.randomSeed)
            config.onDevice.loraAdapterId?.takeIf { it.isNotBlank() }?.let(sessionOptions::setLoraPath)
            LlmInferenceSession.createFromOptions(inference, sessionOptions.build())
        } catch (t: Throwable) {
            inference.close()
            trySend(StreamChunk.Error(t.unwrapOnDeviceThrowable()))
            close()
            return@callbackFlow
        }

        val prompt = buildOnDevicePrompt(messages, config.systemPrompt)
        val emittedText = AtomicReference("")
        val emittedAnyToken = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val closed = AtomicBoolean(false)

        fun closeResources() {
            if (closed.compareAndSet(false, true)) {
                runCatching { session.close() }
                runCatching { inference.close() }
            }
        }

        val future = try {
            session.addQueryChunk(prompt)
            session.generateResponseAsync { partialResult, done ->
                val previous = emittedText.getAndSet(partialResult)
                val delta = if (partialResult.startsWith(previous)) {
                    partialResult.removePrefix(previous)
                } else {
                    partialResult
                }
                if (delta.isNotBlank()) {
                    emittedAnyToken.set(true)
                    trySend(StreamChunk.Token(delta))
                }
                if (done && completed.compareAndSet(false, true)) {
                    trySend(StreamChunk.Done)
                }
            }
        } catch (t: Throwable) {
            closeResources()
            trySend(StreamChunk.Error(t.unwrapOnDeviceThrowable()))
            close()
            return@callbackFlow
        }

        launch(Dispatchers.IO) {
            try {
                val finalText = future.get()
                if (!emittedAnyToken.get() && finalText.isNotBlank()) {
                    trySend(StreamChunk.Token(finalText))
                }
                if (completed.compareAndSet(false, true)) {
                    trySend(StreamChunk.Done)
                }
                close()
            } catch (t: Throwable) {
                trySend(StreamChunk.Error(t.unwrapOnDeviceThrowable()))
                close()
            } finally {
                closeResources()
            }
        }

        awaitClose {
            future.cancel(true)
            closeResources()
        }
    }.flowOn(Dispatchers.IO)
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
    (cause ?: this).takeUnless { it === this } ?: this
