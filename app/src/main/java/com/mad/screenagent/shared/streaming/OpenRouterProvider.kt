package com.mad.screenagent.shared.streaming

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.OpenRouterCatalog
import com.mad.screenagent.data.model.OpenRouterFreeRoutingBucket
import com.mad.screenagent.data.repository.OpenRouterCatalogRepository
import com.mad.screenagent.data.model.SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
import com.mad.screenagent.data.model.isOpenRouterConcreteFreeModel
import com.mad.screenagent.data.model.openRouterFreeSupportsVision
import com.mad.screenagent.data.model.openRouterFreeFallbackModels
import com.mad.screenagent.data.model.openRouterBestFreeCandidates
import com.mad.screenagent.data.model.shouldRetryOpenRouterFreeFallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient

internal fun classifyOpenRouterRequestBucket(messages: List<ChatMessage>): OpenRouterFreeRoutingBucket {
    val userMessages = messages.filter { it.role == "user" }
    val lastUserMessage = userMessages.lastOrNull()
    val lastUserContent = lastUserMessage?.content.orEmpty()
    return when {
        // Only check the current turn for images so prior image turns don't force VISION routing
        lastUserMessage?.images?.isNotEmpty() == true -> OpenRouterFreeRoutingBucket.VISION
        userMessages.any { it.fileAttachment != null } ||
            lastUserContent.contains("<attached_file ", ignoreCase = true) ->
            OpenRouterFreeRoutingBucket.DOCUMENT
        else -> OpenRouterFreeRoutingBucket.GENERAL
    }
}

internal fun shouldRefreshOpenRouterCatalogForBucket(
    bucket: OpenRouterFreeRoutingBucket,
    cachedCatalog: OpenRouterCatalog
): Boolean {
    if (bucket == OpenRouterFreeRoutingBucket.GENERAL) return false
    if (cachedCatalog.models.isEmpty()) return true
    if (bucket != OpenRouterFreeRoutingBucket.VISION) return false

    return cachedCatalog.models.none { entry ->
        entry.isFree && openRouterFreeSupportsVision(entry.id, cachedCatalog.models)
    }
}

class OpenRouterProvider(
    client: OkHttpClient,
    private val openRouterCatalogRepository: OpenRouterCatalogRepository? = null,
    private val bestFreeRoutingStateStore: OpenRouterBestFreeRoutingStateStore? = null
) : AiProvider {

    private val routedClient = client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("HTTP-Referer", "https://uai.app")
                .header("X-Title", "ScreenAgent")
                .build()
            chain.proceed(request)
        }
        .build()

    private val delegate = OpenAiProvider(
        client = routedClient,
        baseUrl = "https://openrouter.ai/api/v1"
    )

    override fun streamResponse(messages: List<ChatMessage>, config: AgentConfig): Flow<StreamChunk> = flow {
        val isBestFreeRoute = config.model == SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
        val requestBucket = classifyRequestBucket(messages)
        val cachedCatalog = openRouterCatalogRepository?.getCatalog() ?: OpenRouterCatalog()
        val shouldRefreshCatalog = shouldRefreshOpenRouterCatalogForBucket(
            bucket = requestBucket,
            cachedCatalog = cachedCatalog
        )
        val routingCatalog = if (openRouterCatalogRepository != null && shouldRefreshCatalog) {
            openRouterCatalogRepository.refreshCatalogIfStale(
                force = cachedCatalog.models.isEmpty() ||
                    requestBucket == OpenRouterFreeRoutingBucket.VISION
            )
        } else {
            cachedCatalog
        }
        val catalogEntries = routingCatalog.models.ifEmpty { cachedCatalog.models }
        val fetchedModelIds = catalogEntries.map { it.id }
        val freeModelIds = catalogEntries
            .asSequence()
            .filter { it.isFree }
            .map { it.id }
            .toSet()

        if (!isBestFreeRoute && !isOpenRouterConcreteFreeModel(config.model, freeModelIds)) {
            emit(StreamChunk.ModelSelection(config.model))
            delegate.streamResponse(messages, config).collect { emit(it) }
            return@flow
        }

        val candidates = if (isBestFreeRoute) {
            val startModelId = bestFreeRoutingStateStore?.lastSuccessfulModelId(
                assistantId = config.id,
                bucket = requestBucket,
                idleTimeoutMs = BEST_FREE_IDLE_TIMEOUT_MS
            )
            openRouterBestFreeCandidates(
                bucket = requestBucket,
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedModelIds,
                freeModelIds = freeModelIds,
                startModelId = startModelId
            )
        } else {
            openRouterFreeFallbackModels(
                catalogEntries = catalogEntries,
                fetchedOpenRouterModels = fetchedModelIds,
                freeModelIds = freeModelIds,
                currentModel = config.model,
                requireVision = requestBucket == OpenRouterFreeRoutingBucket.VISION
            )
        }

        when (
            val result = streamOpenRouterCandidatesWithFallback(
                candidates = candidates,
                emit = { emit(it) },
                streamCandidate = { candidate ->
                    delegate.streamResponse(messages, config.copy(model = candidate))
                },
                onCandidateCommitted = { candidate, index ->
                    if (isBestFreeRoute) {
                        bestFreeRoutingStateStore?.recordSuccess(
                            assistantId = config.id,
                            bucket = requestBucket,
                            modelId = candidate
                        )
                    }
                    emit(
                        StreamChunk.ModelSelection(
                            modelId = candidate,
                            viaFallback = index > 0
                        )
                    )
                },
                shouldRetryOnChunklessError = { failure ->
                    shouldRetryOpenRouterFreeFallback(
                        extractHttpCode(failure.message),
                        failure.message.orEmpty()
                    )
                }
            )
        ) {
            OpenRouterStreamingAttemptResult.Terminated -> return@flow
            is OpenRouterStreamingAttemptResult.Exhausted -> {
                val summary = if (requestBucket == OpenRouterFreeRoutingBucket.VISION) {
                    "No available free OpenRouter image model responded. ScreenAgent tried fallback models automatically."
                } else {
                    "No available free OpenRouter model responded. ScreenAgent tried fallback models automatically."
                }
                if (isBestFreeRoute) {
                    bestFreeRoutingStateStore?.clear(
                        assistantId = config.id,
                        bucket = requestBucket
                    )
                }
                emit(StreamChunk.Error(Exception(result.lastFailure?.message ?: summary)))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun extractHttpCode(message: String?): Int? {
        return Regex("""HTTP\s+(\d+)""")
            .find(message.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun classifyRequestBucket(messages: List<ChatMessage>): OpenRouterFreeRoutingBucket {
        return classifyOpenRouterRequestBucket(messages)
    }

    companion object {
        const val BEST_FREE_IDLE_TIMEOUT_MS = 10L * 60L * 1_000L
    }
}

internal sealed interface OpenRouterStreamingAttemptResult {
    data object Terminated : OpenRouterStreamingAttemptResult
    data class Exhausted(val lastFailure: Exception?) : OpenRouterStreamingAttemptResult
}

private class RetryNextOpenRouterCandidate(val failure: Exception) :
    ProviderFlowControlException(failure)

private class ExhaustedOpenRouterCandidates(val failure: Exception?) :
    ProviderFlowControlException(failure)

internal suspend fun streamOpenRouterCandidatesWithFallback(
    candidates: List<String>,
    emit: suspend (StreamChunk) -> Unit,
    streamCandidate: (String) -> Flow<StreamChunk>,
    onCandidateCommitted: suspend (candidate: String, index: Int) -> Unit = { _, _ -> },
    shouldRetryOnChunklessError: (Exception) -> Boolean
): OpenRouterStreamingAttemptResult {
    if (candidates.isEmpty()) {
        return OpenRouterStreamingAttemptResult.Exhausted(lastFailure = null)
    }

    var lastFailure: Exception? = null
    for ((index, candidate) in candidates.withIndex()) {
        var candidateCommitted = false
        try {
            streamCandidate(candidate).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Error -> {
                        val failure = chunk.cause as? Exception
                            ?: Exception(chunk.cause.message ?: "Unknown error", chunk.cause)
                        val canRetry = !candidateCommitted && shouldRetryOnChunklessError(failure)
                        if (canRetry) {
                            lastFailure = failure
                            if (index < candidates.lastIndex) {
                                throw RetryNextOpenRouterCandidate(failure)
                            } else {
                                throw ExhaustedOpenRouterCandidates(failure)
                            }
                        }
                        if (!candidateCommitted) {
                            onCandidateCommitted(candidate, index)
                            candidateCommitted = true
                        }
                        emit(chunk)
                    }

                    is StreamChunk.ModelSelection -> Unit

                    else -> {
                        if (!candidateCommitted) {
                            onCandidateCommitted(candidate, index)
                            candidateCommitted = true
                        }
                        emit(chunk)
                    }
                }
            }
            return OpenRouterStreamingAttemptResult.Terminated
        } catch (retry: RetryNextOpenRouterCandidate) {
            lastFailure = retry.failure
            continue
        } catch (exhausted: ExhaustedOpenRouterCandidates) {
            return OpenRouterStreamingAttemptResult.Exhausted(exhausted.failure ?: lastFailure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    return OpenRouterStreamingAttemptResult.Exhausted(lastFailure)
}
