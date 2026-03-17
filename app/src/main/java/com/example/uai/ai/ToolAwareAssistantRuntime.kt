package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.model.isSideAgentManagedOpenRouterFreeRoute
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val TOOL_REQUEST_TAG = "tool_request"
private const val SEARCH_TOOL_NAME = "search_web"

data class AssistantToolRequest(
    val tool: String,
    val query: String,
    val type: String? = null,
    val reason: String? = null
)

fun interface SearchToolExecutor {
    suspend fun search(
        conversationKey: String?,
        query: String,
        type: String?,
        onStatusChanged: (String?) -> Unit
    ): WebGroundingResult?
}

class WebGatewaySearchToolExecutor(
    private val webGateway: WebGateway
) : SearchToolExecutor {
    override suspend fun search(
        conversationKey: String?,
        query: String,
        type: String?,
        onStatusChanged: (String?) -> Unit
    ): WebGroundingResult? = webGateway.executeSearchTool(
        conversationKey = conversationKey,
        query = query,
        type = type,
        onStatusChanged = onStatusChanged
    )
}

internal fun buildToolAwareSystemPrompt(baseSystemPrompt: String, config: AgentConfig): String = buildString {
    if (baseSystemPrompt.isNotBlank()) {
        append(baseSystemPrompt.trim())
        append("\n\n")
    }
    appendLine("ScreenAgent app environment:")
    appendLine("- You may request app-owned search tools when fresh external information is needed.")
    appendLine("- Available tool: search_web(query, type)")
    appendLine("- If the user attached an image, screenshot, or file, inspect that attachment first before deciding whether web search is needed.")
    appendLine("- Use it for current events, live prices, recent news, or when the user asks for up-to-date information.")
    appendLine("- type values: \"news\" (news articles/current events), \"stock\" (stock prices/market data), \"tech\" (developer topics/open-source), \"general\" (everything else).")
    appendLine("- If you need the tool, respond with ONLY these blocks and no extra prose:")
    appendLine("<tool_request>{\"tool\":\"search_web\",\"query\":\"your precise query\",\"type\":\"general\"}</tool_request>")
    appendLine("- For multi-domain queries, emit multiple blocks — they execute in parallel:")
    appendLine("<tool_request>{\"tool\":\"search_web\",\"query\":\"AAPL stock price\",\"type\":\"stock\"}</tool_request>")
    appendLine("<tool_request>{\"tool\":\"search_web\",\"query\":\"Apple latest news\",\"type\":\"news\"}</tool_request>")
    appendLine("- After <tool_result> blocks arrive, answer the user naturally.")
    appendLine("- Never mention tool protocols, hidden context, provided context, shared context, or internal search packaging.")
    appendLine("- Cite source titles or domains naturally when useful.")
    appendLine()
    appendLine("Your active capabilities in this session:")
    appendLine("- Internet access: enabled — you proactively search the web for live information when the question requires it.")
    if (config.canHandleImageRequests()) {
        appendLine("- Vision: enabled — you can analyze images and screenshots shared by the user.")
    }
    appendLine("- Documents: enabled — attached files are processed as readable text context.")
    if (isSideAgentManagedOpenRouterFreeRoute(config.model)) {
        appendLine("- Adaptive model routing: enabled — the best available free model is selected automatically per request type (chat, vision, reasoning).")
    }
    append("When asked what you can do or about your capabilities, describe the above accurately and naturally.")
}

internal fun parseAssistantToolRequests(raw: String): List<AssistantToolRequest> {
    val regex = Regex("""(?s)<tool_request>\s*(\{.*?\})\s*</tool_request>""")
    return regex.findAll(raw.trim()).mapNotNull { match ->
        val jsonText = match.groupValues.getOrNull(1).orEmpty()
        if (jsonText.isBlank()) return@mapNotNull null
        runCatching {
            val json = Gson().fromJson(jsonText, JsonObject::class.java)
            val tool = json?.get("tool")?.asString?.trim().orEmpty()
            val query = json?.get("query")?.asString?.trim().orEmpty()
            val type = json?.get("type")?.asString?.trim()?.takeIf { it.isNotBlank() }
            val reason = json?.get("reason")?.asString?.trim()?.takeIf { it.isNotBlank() }
            if (tool.isBlank() || query.isBlank()) null
            else AssistantToolRequest(tool = tool, query = query, type = type, reason = reason)
        }.getOrNull()
    }.toList()
}

// Kept for backwards compatibility
internal fun parseAssistantToolRequest(raw: String): AssistantToolRequest? =
    parseAssistantToolRequests(raw).firstOrNull()

private fun WebGroundingResult.toToolResultContent(requestedQuery: String): String = buildString {
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
    append("</tool_result>")
}

internal fun WebGroundingResult.toToolResultBlock(requestedQuery: String): String =
    toToolResultContent(requestedQuery) + "\nUse the tool result above to answer the original user request naturally. Cite source titles or domains when useful. Do not mention tool names or hidden context."

private fun buildToolFailureContent(toolRequest: AssistantToolRequest): String {
    val reason = if (toolRequest.tool != SEARCH_TOOL_NAME) {
        "The requested tool is not available."
    } else {
        "No fresh results were available for that query."
    }
    return buildString {
        appendLine("<tool_result name=\"${toolRequest.tool}\">")
        appendLine("Query: ${toolRequest.query}")
        appendLine("Status: unavailable")
        appendLine("Reason: $reason")
        append("</tool_result>")
    }
}

private fun buildToolFailureMessage(toolRequest: AssistantToolRequest): ChatMessage =
    ChatMessage(
        role = "user",
        content = buildToolFailureContent(toolRequest) +
            "\nAnswer the original user request naturally without mentioning tool protocols or hidden context. If needed, say you could not verify from the sources you checked."
    )

private fun buildToolRequestJson(req: AssistantToolRequest): String {
    val sb = StringBuilder("{\"tool\":\"${req.tool}\",\"query\":\"${req.query.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    if (req.type != null) sb.append(",\"type\":\"${req.type}\"")
    sb.append("}")
    return sb.toString()
}

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
            systemPrompt = buildToolAwareSystemPrompt(config.systemPrompt, config)
        )

        repeat(maxToolRounds + 1) { round ->
            onStatusChanged("Generating the best answer…")
            val provider = providerFactory(toolAwareConfig)

            // Collect tokens with early streaming: buffer only until we can tell whether
            // the response starts with a <tool_request> block or is a real answer.
            // Once we know it's a real answer, emit tokens as they arrive so the user
            // sees text immediately rather than waiting for the full response.
            val accumulated = StringBuilder()
            val modelSelections = mutableListOf<StreamChunk.ModelSelection>()
            var error: Throwable? = null
            var streaming = false  // true once we've committed to emitting tokens live

            provider.streamResponse(workingMessages, toolAwareConfig).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Token -> {
                        accumulated.append(chunk.text)
                        if (!streaming) {
                            val trimmed = accumulated.toString().trimStart()
                            when {
                                trimmed.contains(TOOL_REQUEST_TAG) -> {
                                    // It's a tool request — keep buffering, don't emit anything.
                                }
                                trimmed.length > TOOL_REQUEST_TAG.length + 2 -> {
                                    // Enough chars arrived without a tool-request prefix:
                                    // this is a real answer. Flush the buffer and go live.
                                    streaming = true
                                    emit(StreamChunk.Token(accumulated.toString()))
                                }
                            }
                        } else {
                            // Already streaming — emit each delta as it arrives.
                            emit(StreamChunk.Token(chunk.text))
                        }
                    }
                    is StreamChunk.ModelSelection -> modelSelections += chunk
                    is StreamChunk.Usage -> emit(chunk)
                    is StreamChunk.Error -> error = chunk.cause
                    is StreamChunk.Done -> Unit
                }
            }

            modelSelections.forEach { emit(it) }

            val fullText = accumulated.toString().trim()
            val toolRequests = parseAssistantToolRequests(fullText)

            if (toolRequests.isNotEmpty() && round < maxToolRounds) {
                val statusMsg = if (toolRequests.size > 1) {
                    "Looking up ${toolRequests.size} things in parallel…"
                } else {
                    "Looking up one more detail online…"
                }
                onStatusChanged(statusMsg)

                // Execute all tool requests in parallel
                val toolResults: List<Pair<AssistantToolRequest, WebGroundingResult?>> = coroutineScope {
                    toolRequests.map { req ->
                        async {
                            val result = if (req.tool == SEARCH_TOOL_NAME) {
                                searchToolExecutor.search(
                                    conversationKey = conversationKey,
                                    query = req.query,
                                    type = req.type,
                                    onStatusChanged = onStatusChanged
                                )
                            } else null
                            req to result
                        }
                    }.map { it.await() }
                }

                // Build one assistant message with all tool_requests + one user message with all results
                val assistantContent = toolRequests.joinToString("\n") { req ->
                    "<$TOOL_REQUEST_TAG>${buildToolRequestJson(req)}</$TOOL_REQUEST_TAG>"
                }
                val allResultsContent = toolResults.joinToString("\n\n") { (req, result) ->
                    result?.toToolResultContent(req.query) ?: buildToolFailureContent(req)
                } + "\n\nUse all tool results above to answer the original user request naturally. Cite source titles or domains when useful. Do not mention tool names or hidden context."

                workingMessages = buildList {
                    addAll(workingMessages)
                    add(ChatMessage(role = "assistant", content = assistantContent))
                    add(ChatMessage(role = "user", content = allResultsContent))
                }
                return@repeat
            }

            // If we never started streaming (tool-request buffer that wasn't a tool call,
            // or response was very short), emit the full text now as a single token.
            if (!streaming) {
                val sanitized = sanitizeGroundedAssistantResponse(fullText)
                if (sanitized.isNotBlank()) emit(StreamChunk.Token(sanitized))
            }
            if (error != null) emit(StreamChunk.Error(error!!))
            else emit(StreamChunk.Done)
            return@flow
        }

        emit(StreamChunk.Error(IllegalStateException("ScreenAgent tool loop exceeded the allowed number of rounds.")))
    }.flowOn(Dispatchers.IO)
}
