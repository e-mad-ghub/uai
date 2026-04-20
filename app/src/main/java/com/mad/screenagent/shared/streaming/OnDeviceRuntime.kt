package com.mad.screenagent.shared.streaming

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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

    suspend fun validateModel(modelPath: String): OnDeviceValidationResult

    suspend fun validateVisionModel(
        modelPath: String,
        visionProjectorPath: String
    ): OnDeviceValidationResult

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

    private fun loadVisionSmokeTestAttachment(): ImageAttachment {
        val base64 = appContext.assets.open(ON_DEVICE_VISION_SMOKE_TEST_ASSET)
            .bufferedReader()
            .use { it.readText() }
            .trim()
        return ImageAttachment(
            base64 = base64,
            mimeType = "image/png"
        )
    }

    override suspend fun validateModel(
        modelPath: String
    ): OnDeviceValidationResult {
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
                engine.loadModel(modelPath, null)
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

    override suspend fun validateVisionModel(
        modelPath: String,
        visionProjectorPath: String
    ): OnDeviceValidationResult {
        val modelFile = File(modelPath)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            return OnDeviceValidationResult.failure(
                OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE,
                OnDeviceUserMessages.missingModelFile()
            )
        }
        val projectorFile = File(visionProjectorPath)
        if (!projectorFile.exists() || projectorFile.length() == 0L) {
            return OnDeviceValidationResult.failure(
                OnDeviceFailureKind.UNAVAILABLE_ON_DEVICE,
                OnDeviceUserMessages.missingVisionSupportFile()
            )
        }

        return loadMutex.withLock {
            try {
                if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
                    runCatching { engine.cleanUp() }
                }
                engine.loadModel(modelPath, visionProjectorPath)
                val promptBundle = buildOnDeviceVisionPrompt(
                    messages = listOf(
                        ChatMessage(
                            role = "user",
                            content = "Describe this image briefly.",
                            images = listOf(loadVisionSmokeTestAttachment())
                        )
                    ),
                    systemPrompt = "",
                    tempRootDir = File(appContext.cacheDir, "on-device-vision-smoke")
                )
                val imageBitmap = promptBundle.visionBitmap
                    ?: error("Vision smoke test prompt is missing its bitmap payload.")
                engine.sendUserPromptWithVisionBitmap(
                    message = promptBundle.prompt,
                    width = imageBitmap.width,
                    height = imageBitmap.height,
                    rgbBytes = imageBitmap.rgbBytes,
                    predictLength = 8
                ).collect { }
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
                    message = OnDeviceUserMessages.visionValidationMessage(
                        cause.toFailureKind(),
                        cause.message
                    )
                )
            } finally {
            }
        }
    }

    override fun streamResponse(
        messages: List<ChatMessage>,
        config: AgentConfig,
        modelPath: String,
        visionProjectorPath: String?
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

            val visionImages = messages.flatMap { it.images }
            val hasDocumentContext = messages.any {
                it.fileAttachment != null || !it.documentBase64.isNullOrBlank()
            }
            if (visionImages.size > 1) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(OnDeviceUserMessages.singleImageOnly())
                    )
                )
                return@flow
            }
            if (visionImages.isNotEmpty() && hasDocumentContext) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(OnDeviceUserMessages.mixedImageAndDocumentUnsupported())
                    )
                )
                return@flow
            }
            if (visionImages.isNotEmpty() && visionProjectorPath == null) {
                emit(
                    StreamChunk.Error(
                        IllegalStateException(OnDeviceUserMessages.imageAttachmentsRequireVisionModel())
                    )
                )
                return@flow
            }
            ensureLoaded(modelPath = modelPath, visionProjectorPath = visionProjectorPath, config = config)
            val promptBundle = if (visionProjectorPath != null && visionImages.isNotEmpty()) {
                buildOnDeviceVisionPrompt(
                    messages = messages,
                    systemPrompt = "",
                    tempRootDir = File(appContext.cacheDir, "on-device-vision")
                )
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
            val tokenFlow = if (promptBundle.visionBitmap != null) {
                val imageBitmap = promptBundle.visionBitmap
                engine.sendUserPromptWithVisionBitmap(
                    message = promptBundle.prompt,
                    width = imageBitmap.width,
                    height = imageBitmap.height,
                    rgbBytes = imageBitmap.rgbBytes,
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
    val visionBitmap: OnDeviceVisionBitmap? = null
)

private data class OnDeviceVisionBitmap(
    val width: Int,
    val height: Int,
    val rgbBytes: ByteArray
)

private const val ON_DEVICE_VISION_SMOKE_TEST_ASSET = "on_device_vision_smoke_test.base64"
private const val ON_DEVICE_ATTACHMENT_TEXT_LIMIT = 4_000
private const val ON_DEVICE_ATTACHMENT_TRUNCATION_NOTE =
    "\n\n[Attachment text truncated for on-device processing.]"
private const val ON_DEVICE_VISION_MAX_EDGE = 1024
private const val ON_DEVICE_RUNTIME_TAG = "OnDevicePerf"

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
    var visionBitmap: OnDeviceVisionBitmap? = null
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
        messages.forEach { message ->
            message.images.forEach { image ->
                val bytes = runCatching {
                    Base64.decode(image.base64, Base64.DEFAULT)
                }.getOrNull()
                if (bytes != null) {
                    visionBitmap = createNormalizedOnDeviceVisionBitmap(
                        bytes = bytes,
                        mimeType = image.mimeType,
                        tempDir = tempRootDir ?: File("/data/local/tmp"),
                        index = 0
                    )
                }
            }
        }
    }

    return OnDevicePromptBundle(prompt = promptText, visionBitmap = visionBitmap)
}

private fun createNormalizedOnDeviceVisionBitmap(
    bytes: ByteArray,
    mimeType: String,
    tempDir: File,
    index: Int
): OnDeviceVisionBitmap {
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (decoded == null) {
        val ext = mimeType.lowercase().let { mime ->
            when {
                mime.contains("png") -> ".png"
                mime.contains("webp") -> ".webp"
                mime.contains("gif") -> ".gif"
                else -> ".jpg"
            }
        }
        val fallbackFile = File(tempDir, "${UUID.randomUUID()}_image_$index$ext").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val fallbackDecoded = BitmapFactory.decodeFile(fallbackFile.absolutePath)
            ?: error("Unable to decode normalized on-device vision bitmap.")
        return fallbackDecoded.toNormalizedOnDeviceVisionBitmap().also {
            fallbackDecoded.recycle()
            runCatching { fallbackFile.delete() }
        }
    }
    return decoded.toNormalizedOnDeviceVisionBitmap().also {
        decoded.recycle()
    }
}

private fun Bitmap.scaleForOnDeviceVision(): Bitmap {
    val longestEdge = maxOf(width, height)
    if (width >= 2 && height >= 2 && longestEdge <= ON_DEVICE_VISION_MAX_EDGE) return this
    val scale = ON_DEVICE_VISION_MAX_EDGE.toFloat() / longestEdge.toFloat()
    val clampedScale = if (longestEdge <= ON_DEVICE_VISION_MAX_EDGE) 1f else scale
    val targetWidth = maxOf(2, (width * clampedScale).toInt())
    val targetHeight = maxOf(2, (height * clampedScale).toInt())
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

private fun Bitmap.toNormalizedOnDeviceVisionBitmap(): OnDeviceVisionBitmap {
    val scaled = scaleForOnDeviceVision()
    val flattened = Bitmap.createBitmap(
        maxOf(2, scaled.width),
        maxOf(2, scaled.height),
        Bitmap.Config.ARGB_8888
    )
    Canvas(flattened).apply {
        drawColor(Color.WHITE)
        drawBitmap(scaled, 0f, 0f, null)
    }

    val width = flattened.width
    val height = flattened.height
    val pixels = IntArray(width * height)
    flattened.getPixels(pixels, 0, width, 0, 0, width, height)
    val rgb = ByteArray(width * height * 3)
    pixels.forEachIndexed { index, pixel ->
        val offset = index * 3
        rgb[offset] = Color.red(pixel).toByte()
        rgb[offset + 1] = Color.green(pixel).toByte()
        rgb[offset + 2] = Color.blue(pixel).toByte()
    }

    Log.i(ON_DEVICE_RUNTIME_TAG, "Normalized on-device vision bitmap ${width}x$height (${rgb.size} bytes)")

    flattened.recycle()
    if (scaled !== this) {
        scaled.recycle()
    }

    return OnDeviceVisionBitmap(
        width = width,
        height = height,
        rgbBytes = rgb
    )
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
