package com.example.uai.ai

import com.example.uai.data.model.AgentConfig
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.collect

data class SearchPlanningResult(
    val needsSearch: Boolean,
    val queries: List<String>
)

private data class CollectedPlanningResponse(
    val text: String,
    val error: Throwable? = null
)

internal fun buildSearchPlanningSystemPrompt(): String = """
    You are ScreenAgent's internet search planner.
    Your job is only to decide what fresh public-web lookups are needed before answering the user.
    Never answer the user directly.

    Return ONLY one block in this exact format:
    <search_plan>{"needs_search":true,"queries":["query one","query two"]}</search_plan>

    Rules:
    - If no internet lookup is needed, return:
      <search_plan>{"needs_search":false,"queries":[]}</search_plan>
    - Decompose mixed requests into distinct search points.
    - Use current conversation context to resolve vague follow-ups like "tell me the price" or "what about Tesla".
    - Prefer precise search strings:
      - Stock prices: "<company or ticker> stock price yahoo finance"
      - Current time: "current time in <location>"
      - News/current events: "latest news about <topic>"
      - Current person/role: "current <role> of <entity>"
    - Keep to at most 6 distinct queries.
    - Do not include explanations, markdown, code fences, or any text outside <search_plan>...</search_plan>.
""".trimIndent()

internal fun parseSearchPlanningResult(raw: String): SearchPlanningResult? {
    val match = Regex("""(?s)<search_plan>\s*(\{.*?\})\s*</search_plan>""")
        .find(raw.trim())
        ?: return null
    val jsonText = match.groupValues.getOrNull(1).orEmpty()
    if (jsonText.isBlank()) return null

    return runCatching {
        val json = Gson().fromJson(jsonText, JsonObject::class.java)
        val needsSearch = json?.get("needs_search")?.asBoolean ?: false
        val queries = json?.getAsJsonArray("queries")
            ?.mapNotNull(JsonElement::getAsString)
            .orEmpty()
            .map { normalizeConversationText(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)

        SearchPlanningResult(
            needsSearch = needsSearch && queries.isNotEmpty(),
            queries = if (needsSearch) queries else emptyList()
        )
    }.getOrNull()
}

private fun buildSearchPlanningPrompt(
    messages: List<ChatMessage>,
    previousState: ConversationWorkingState?
): String {
    val recentMessages = messages.takeLast(8)
        .joinToString("\n") { message ->
            val content = normalizeConversationText(message.content).take(500)
            "${message.role}: $content"
        }
    val stateSummary = previousState?.let { state ->
        buildString {
            appendLine("Conversation working state:")
            appendLine("- active_intent: ${state.activeIntent}")
            if (state.activeSubjects.isNotEmpty()) {
                appendLine("- active_subjects: ${state.activeSubjects.joinToString(" | ")}")
            }
            state.activeLocation?.let { appendLine("- active_location: $it") }
            if (state.lastGroundedQueries.isNotEmpty()) {
                appendLine("- last_grounded_queries: ${state.lastGroundedQueries.joinToString(" | ")}")
            }
        }.trim()
    }.orEmpty()

    return buildString {
        if (stateSummary.isNotBlank()) {
            appendLine(stateSummary)
            appendLine()
        }
        appendLine("Recent conversation:")
        appendLine(recentMessages)
        appendLine()
        append("Return the <search_plan> block now.")
    }.trim()
}

class SearchPlanningService(
    private val providerFactory: (AgentConfig) -> AiProvider
) {
    suspend fun planSearches(
        messages: List<ChatMessage>,
        config: AgentConfig,
        previousState: ConversationWorkingState?,
        onStatusChanged: (String?) -> Unit = {}
    ): SearchPlanningResult? {
        val latestUserMessage = messages.lastOrNull { it.role == "user" }
            ?: return SearchPlanningResult(needsSearch = false, queries = emptyList())
        if (latestUserMessage.hasDirectAttachmentContext()) {
            return SearchPlanningResult(needsSearch = false, queries = emptyList())
        }

        val latestUserText = latestUserMessage.content
        val normalized = normalizeConversationText(latestUserText)
        if (normalized.isBlank() || isLikelyLocalOnlyRequest(normalized)) {
            return SearchPlanningResult(needsSearch = false, queries = emptyList())
        }

        onStatusChanged("Planning what to look up online…")
        return try {
            val plannerConfig = config.copy(
                systemPrompt = buildSearchPlanningSystemPrompt(),
                temperature = 0f
            )
            val planningMessages = listOf(
                ChatMessage(
                    role = "user",
                    content = buildSearchPlanningPrompt(messages, previousState)
                )
            )
            val collected = collectPlanningResponse(
                provider = providerFactory(plannerConfig),
                messages = planningMessages,
                config = plannerConfig
            )
            if (collected.error != null) null else parseSearchPlanningResult(collected.text)
        } finally {
            onStatusChanged(null)
        }
    }

    private suspend fun collectPlanningResponse(
        provider: AiProvider,
        messages: List<ChatMessage>,
        config: AgentConfig
    ): CollectedPlanningResponse {
        val text = StringBuilder()
        var error: Throwable? = null
        provider.streamResponse(messages, config).collect { chunk ->
            when (chunk) {
                is StreamChunk.Token -> text.append(chunk.text)
                is StreamChunk.Error -> error = chunk.cause
                is StreamChunk.ModelSelection -> Unit
                is StreamChunk.Done -> Unit
            }
        }
        return CollectedPlanningResponse(
            text = text.toString().trim(),
            error = error
        )
    }
}
