package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val TOOL_REQUEST_TAG = "tool_request"
private const val SEARCH_TOOL_NAME = "search_web"

data class AssistantToolRequest(
    val tool: String,
    val query: String,
    val reason: String? = null
)

fun interface SearchToolExecutor {
    suspend fun search(
        conversationKey: String?,
        query: String,
        onStatusChanged: (String?) -> Unit
    ): WebGroundingResult?
}

class WebGatewaySearchToolExecutor(
    private val webGateway: WebGateway
) : SearchToolExecutor {
    override suspend fun search(
        conversationKey: String?,
        query: String,
        onStatusChanged: (String?) -> Unit
    ): WebGroundingResult? = webGateway.executeSearchTool(
        conversationKey = conversationKey,
        query = query,
        onStatusChanged = onStatusChanged
    )
}

internal fun buildToolAwareSystemPrompt(baseSystemPrompt: String): String = buildString {
    if (baseSystemPrompt.isNotBlank()) {
        append(baseSystemPrompt.trim())
        append("\n\n")
    }
    appendLine("ScreenAgent app environment:")
    appendLine("- You may request one app-owned tool when fresh external information is needed.")
    appendLine("- Available tool: search_web(query)")
    appendLine("- If the user attached an image, screenshot, or file, inspect that attachment first before deciding whether web search is needed.")
    appendLine("- Use it for current events, live prices, recent changes, or when the user asks for up-to-date information.")
    appendLine("- If you need the tool, respond with ONLY this block and no extra prose:")
    appendLine("<tool_request>{\"tool\":\"search_web\",\"query\":\"your precise query\"}</tool_request>")
    appendLine("- After a <tool_result> block arrives, answer the user naturally.")
    appendLine("- Never mention tool protocols, hidden context, provided context, shared context, or internal search packaging.")
    append("- Cite source titles or domains naturally when useful.")
}

internal fun parseAssistantToolRequest(raw: String): AssistantToolRequest? {
    val match = Regex("""(?s)<tool_request>\s*(\{.*?\})\s*</tool_request>""")
        .find(raw.trim())
        ?: return null
    val jsonText = match.groupValues.getOrNull(1).orEmpty()
    if (jsonText.isBlank()) return null

    return runCatching {
        val json = Gson().fromJson(jsonText, JsonObject::class.java)
        val tool = json?.get("tool")?.asString?.trim().orEmpty()
        val query = json?.get("query")?.asString?.trim().orEmpty()
        val reason = json?.get("reason")?.asString?.trim()?.takeIf { it.isNotBlank() }
        if (tool.isBlank() || query.isBlank()) null
        else AssistantToolRequest(tool = tool, query = query, reason = reason)
    }.getOrNull()
}

internal fun WebGroundingResult.toToolResultBlock(requestedQuery: String): String = buildString {
    appendLine("<tool_result name=\"$SEARCH_TOOL_NAME\">")
    appendLine("Query: $requestedQuery")
    if (facts.isNotEmpty()) {
        appendLine("Resolved facts:")
        facts.forEachIndexed { index, fact ->
            appendLine("${index + 1}. ${fact.label}: ${fact.value}")
            appendLine("Source: ${fact.sourceTitle}")
            appendLine("URL: ${fact.sourceUrl}")
            appendLine()
        }
    }
    if (sources.isNotEmpty()) {
        appendLine("Search sources:")
        sources.forEachIndexed { index, source ->
            appendLine("${index + 1}. ${source.title}")
            appendLine("URL: ${source.url}")
            if (source.snippet.isNotBlank()) {
                appendLine("Snippet: ${source.snippet}")
            }
            appendLine()
        }
    }
    appendLine("</tool_result>")
    append("Use the tool result above to answer the original user request naturally. Cite source titles or domains when useful. Do not mention tool names or hidden context.")
}

private fun buildToolFailureMessage(toolRequest: AssistantToolRequest): ChatMessage {
    val reason = if (toolRequest.tool != SEARCH_TOOL_NAME) {
        "The requested tool is not available."
    } else {
        "No fresh results were available for that query."
    }
    return ChatMessage(
        role = "user",
        content = buildString {
            appendLine("<tool_result name=\"${toolRequest.tool}\">")
            appendLine("Query: ${toolRequest.query}")
            appendLine("Status: unavailable")
            appendLine("Reason: $reason")
            appendLine("</tool_result>")
            append("Answer the original user request naturally without mentioning tool protocols or hidden context. If needed, say you could not verify from the sources you checked.")
        }
    )
}

private data class CollectedProviderResponse(
    val text: String,
    val modelSelections: List<StreamChunk.ModelSelection>,
    val error: Throwable? = null
)

class ToolAwareAssistantRuntime(
    private val providerFactory: (AgentConfig) -> AiProvider,
    private val searchToolExecutor: SearchToolExecutor,
    private val maxToolRounds: Int = 2
) {

    private fun shouldBypassToolLoop(messages: List<ChatMessage>): Boolean {
        val lastUserMessage = messages.lastOrNull { it.role == "user" } ?: return false
        if (!lastUserMessage.hasDirectAttachmentContext()) return false
        return !shouldUseWebGrounding(lastUserMessage.content)
    }

    fun streamResponse(
        conversationKey: String?,
        messages: List<ChatMessage>,
        config: AgentConfig,
        onStatusChanged: (String?) -> Unit = {}
    ): Flow<StreamChunk> = flow {
        if (shouldBypassToolLoop(messages)) {
            providerFactory(config)
                .streamResponse(messages, config)
                .collect { emit(it) }
            return@flow
        }

        var workingMessages = messages
        val toolAwareConfig = config.copy(
            systemPrompt = buildToolAwareSystemPrompt(config.systemPrompt)
        )

        repeat(maxToolRounds + 1) { round ->
            onStatusChanged("Generating the best answer…")
            val provider = providerFactory(toolAwareConfig)
            val collected = collectProviderResponse(provider, workingMessages, toolAwareConfig)

            collected.modelSelections.forEach { emit(it) }

            val toolRequest = parseAssistantToolRequest(collected.text)
            if (toolRequest != null && round < maxToolRounds) {
                onStatusChanged("Looking up one more detail online…")
                val toolResult = if (toolRequest.tool == SEARCH_TOOL_NAME) {
                    searchToolExecutor.search(
                        conversationKey = conversationKey,
                        query = toolRequest.query,
                        onStatusChanged = onStatusChanged
                    )
                } else {
                    null
                }

                workingMessages = buildList {
                    addAll(workingMessages)
                    add(
                        ChatMessage(
                            role = "assistant",
                            content = "<$TOOL_REQUEST_TAG>{\"tool\":\"${toolRequest.tool}\",\"query\":\"${toolRequest.query}\"}</$TOOL_REQUEST_TAG>"
                        )
                    )
                    add(toolResult?.toToolResultBlock(toolRequest.query)?.let { result ->
                        ChatMessage(role = "user", content = result)
                    } ?: buildToolFailureMessage(toolRequest))
                }
                return@repeat
            }

            val sanitized = sanitizeGroundedAssistantResponse(collected.text)
            if (sanitized.isNotBlank()) {
                emit(StreamChunk.Token(sanitized))
            }
            if (collected.error != null) {
                emit(StreamChunk.Error(collected.error))
            } else {
                emit(StreamChunk.Done)
            }
            return@flow
        }

        emit(StreamChunk.Error(IllegalStateException("ScreenAgent tool loop exceeded the allowed number of rounds.")))
    }.flowOn(Dispatchers.IO)

    private suspend fun collectProviderResponse(
        provider: AiProvider,
        messages: List<ChatMessage>,
        config: AgentConfig
    ): CollectedProviderResponse {
        val text = StringBuilder()
        val modelSelections = mutableListOf<StreamChunk.ModelSelection>()
        var error: Throwable? = null

        provider.streamResponse(messages, config).collect { chunk ->
            when (chunk) {
                is StreamChunk.Token -> text.append(chunk.text)
                is StreamChunk.ModelSelection -> modelSelections += chunk
                is StreamChunk.Error -> error = chunk.cause
                is StreamChunk.Done -> Unit
            }
        }

        return CollectedProviderResponse(
            text = text.toString().trim(),
            modelSelections = modelSelections,
            error = error
        )
    }
}
