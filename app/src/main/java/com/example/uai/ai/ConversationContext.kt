package com.example.uai.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ConversationIntent {
    NONE,
    STOCK_PRICE,
    CURRENT_TIME,
    GENERAL_WEB
}

data class ConversationWorkingState(
    val conversationKey: String,
    val activeIntent: ConversationIntent = ConversationIntent.NONE,
    val activeSubjects: List<String> = emptyList(),
    val activeLocation: String? = null,
    val freshnessRequired: Boolean = false,
    val lastGroundedQueries: List<String> = emptyList(),
    val lastMode: WebTurnMode = WebTurnMode.NONE,
    val lastResolvedUserText: String? = null,
    val lastUpdatedAt: Long = 0L
)

data class ResolvedConversationTurn(
    val state: ConversationWorkingState?,
    val intent: ConversationIntent = ConversationIntent.NONE,
    val queries: List<String> = emptyList(),
    val usedFollowUpContext: Boolean = false
)

internal fun ChatMessage.hasDirectAttachmentContext(): Boolean {
    return images.isNotEmpty() || fileAttachment != null || !documentBase64.isNullOrBlank()
}

class ConversationContextStore {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, ConversationWorkingState>()

    suspend fun get(key: String?): ConversationWorkingState? {
        if (key.isNullOrBlank()) return null
        return mutex.withLock { sessions[key] }
    }

    suspend fun put(state: ConversationWorkingState) {
        if (state.conversationKey.isBlank()) return
        mutex.withLock {
            sessions[state.conversationKey] = state
        }
    }
}

class ConversationContextResolver {

    fun resolve(
        conversationKey: String?,
        messages: List<ChatMessage>,
        previousState: ConversationWorkingState?
    ): ResolvedConversationTurn {
        val lastUserMessage = messages.lastOrNull { it.role == "user" } ?: return ResolvedConversationTurn(previousState)
        val currentUserText = normalizeConversationText(lastUserMessage.content)
        val historicalState = previousState ?: deriveHistoricalState(conversationKey, messages)
        val baseState = baseState(conversationKey, historicalState, currentUserText)

        if (currentUserText.isBlank()) {
            return ResolvedConversationTurn(state = historicalState)
        }

        if (lastUserMessage.hasDirectAttachmentContext()) {
            return ResolvedConversationTurn(state = baseState)
        }

        val explicitStockSubjects = extractExplicitStockSubjects(currentUserText)
        if (explicitStockSubjects.isNotEmpty()) {
            return ResolvedConversationTurn(
                state = baseState.copy(
                    activeIntent = ConversationIntent.STOCK_PRICE,
                    activeSubjects = explicitStockSubjects,
                    activeLocation = null,
                    freshnessRequired = true
                ),
                intent = ConversationIntent.STOCK_PRICE,
                queries = explicitStockSubjects.map(::stockPriceQuery)
            )
        }

        val explicitTimeLocation = extractExplicitTimeLocation(currentUserText)
        if (explicitTimeLocation != null) {
            return ResolvedConversationTurn(
                state = baseState.copy(
                    activeIntent = ConversationIntent.CURRENT_TIME,
                    activeSubjects = listOf(explicitTimeLocation),
                    activeLocation = explicitTimeLocation,
                    freshnessRequired = true
                ),
                intent = ConversationIntent.CURRENT_TIME,
                queries = listOf(currentTimeQuery(explicitTimeLocation))
            )
        }

        if (historicalState?.activeIntent == ConversationIntent.STOCK_PRICE) {
            val followUpSubjects = if (looksLikeEntityFollowUp(currentUserText)) {
                extractContextFollowUpSubjects(currentUserText)
            } else {
                emptyList()
            }
            if (followUpSubjects.isNotEmpty()) {
                return ResolvedConversationTurn(
                    state = baseState.copy(
                        activeIntent = ConversationIntent.STOCK_PRICE,
                        activeSubjects = followUpSubjects,
                        activeLocation = null,
                        freshnessRequired = true
                    ),
                    intent = ConversationIntent.STOCK_PRICE,
                    queries = followUpSubjects.map(::stockPriceQuery),
                    usedFollowUpContext = true
                )
            }

            if (isVagueStockPricePrompt(currentUserText) && historicalState.activeSubjects.isNotEmpty()) {
                return ResolvedConversationTurn(
                    state = baseState.copy(
                        activeIntent = ConversationIntent.STOCK_PRICE,
                        activeSubjects = historicalState.activeSubjects,
                        activeLocation = null,
                        freshnessRequired = true
                    ),
                    intent = ConversationIntent.STOCK_PRICE,
                    queries = historicalState.activeSubjects.map(::stockPriceQuery),
                    usedFollowUpContext = true
                )
            }
        }

        if (historicalState?.activeIntent == ConversationIntent.CURRENT_TIME &&
            historicalState.activeLocation != null &&
            isImplicitTimeFollowUp(currentUserText)
        ) {
            return ResolvedConversationTurn(
                state = baseState.copy(
                    activeIntent = ConversationIntent.CURRENT_TIME,
                    activeSubjects = listOf(historicalState.activeLocation),
                    activeLocation = historicalState.activeLocation,
                    freshnessRequired = true
                ),
                intent = ConversationIntent.CURRENT_TIME,
                queries = listOf(currentTimeQuery(historicalState.activeLocation)),
                usedFollowUpContext = true
            )
        }

        if (historicalState?.activeIntent == ConversationIntent.GENERAL_WEB) {
            val contextualQueries = deriveContextualGeneralWebQueries(currentUserText, historicalState)
            if (contextualQueries.isNotEmpty()) {
                return ResolvedConversationTurn(
                    state = baseState.copy(
                        activeIntent = ConversationIntent.GENERAL_WEB,
                        activeSubjects = contextualQueries,
                        activeLocation = historicalState.activeLocation,
                        freshnessRequired = true
                    ),
                    intent = ConversationIntent.GENERAL_WEB,
                    queries = contextualQueries,
                    usedFollowUpContext = true
                )
            }
        }

        if (isLikelyGeneralKnowledgeRequest(currentUserText)) {
            val query = deriveGeneralKnowledgeQuery(currentUserText)
            if (query != null) {
                return ResolvedConversationTurn(
                    state = baseState.copy(
                        activeIntent = ConversationIntent.GENERAL_WEB,
                        activeSubjects = listOf(query),
                        activeLocation = historicalState?.activeLocation,
                        freshnessRequired = true
                    ),
                    intent = ConversationIntent.GENERAL_WEB,
                    queries = listOf(query)
                )
            }
        }

        val fallbackQueries = deriveWebSearchQueries(messages)
        if (fallbackQueries.isNotEmpty()) {
            return ResolvedConversationTurn(
                state = baseState.copy(
                    activeIntent = ConversationIntent.GENERAL_WEB,
                    activeSubjects = fallbackQueries,
                    activeLocation = historicalState?.activeLocation,
                    freshnessRequired = true
                ),
                intent = ConversationIntent.GENERAL_WEB,
                queries = fallbackQueries,
                usedFollowUpContext = isLikelyWebGroundingFollowUp(
                    userText = currentUserText,
                    previousUserText = previousComparableUserText(messages)
                )
            )
        }

        return ResolvedConversationTurn(state = historicalState)
    }

    private fun baseState(
        conversationKey: String?,
        historicalState: ConversationWorkingState?,
        currentUserText: String
    ): ConversationWorkingState {
        val key = conversationKey
            ?: historicalState?.conversationKey
            ?: ""
        return historicalState?.copy(
            conversationKey = key,
            lastResolvedUserText = currentUserText
        ) ?: ConversationWorkingState(
            conversationKey = key,
            lastResolvedUserText = currentUserText
        )
    }

    private fun deriveHistoricalState(
        conversationKey: String?,
        messages: List<ChatMessage>
    ): ConversationWorkingState? {
        val userMessages = messages.filter { it.role == "user" }
        if (userMessages.isEmpty()) return null

        val key = conversationKey ?: ""
        val priorUserTurns = if (messages.lastOrNull { it.role == "user" } == userMessages.last()) {
            userMessages.dropLast(1)
        } else {
            userMessages
        }

        priorUserTurns.asReversed().forEach { message ->
            val text = normalizeConversationText(message.content)
            if (text.isBlank()) return@forEach

            val stockSubjects = extractExplicitStockSubjects(text)
            if (stockSubjects.isNotEmpty()) {
                return ConversationWorkingState(
                    conversationKey = key,
                    activeIntent = ConversationIntent.STOCK_PRICE,
                    activeSubjects = stockSubjects,
                    freshnessRequired = true,
                    lastResolvedUserText = text
                )
            }

            val timeLocation = extractExplicitTimeLocation(text)
            if (timeLocation != null) {
                return ConversationWorkingState(
                    conversationKey = key,
                    activeIntent = ConversationIntent.CURRENT_TIME,
                    activeSubjects = listOf(timeLocation),
                    activeLocation = timeLocation,
                    freshnessRequired = true,
                    lastResolvedUserText = text
                )
            }

            val genericQueries = deriveWebSearchQueries(
                listOf(ChatMessage(role = "user", content = text))
            )
            if (genericQueries.isNotEmpty()) {
                return ConversationWorkingState(
                    conversationKey = key,
                    activeIntent = ConversationIntent.GENERAL_WEB,
                    activeSubjects = genericQueries,
                    freshnessRequired = true,
                    lastResolvedUserText = text
                )
            }
        }

        return null
    }
}

internal fun normalizeConversationText(raw: String): String {
    return stripInjectedWebGroundingContext(
        stripQuotedReplyContext(raw)
    ).replace(Regex("""\s+"""), " ").trim()
}

internal fun currentTimeQuery(location: String): String = "current time in $location"

internal fun stockPriceQuery(subject: String): String = "$subject stock price yahoo finance"

internal fun extractExplicitStockSubjects(userText: String): List<String> {
    val normalized = normalizeConversationText(userText)
    if (normalized.isBlank()) return emptyList()

    val lower = normalized.lowercase()
    val hasStockIntent = lower.contains("price") || lower.contains("stock") || lower.contains("share")
    if (!hasStockIntent) return emptyList()
    val isEntityFollowUp = lower.startsWith("what about ") || lower.startsWith("how about ") || lower.startsWith("and ")
    if (isEntityFollowUp && !lower.contains("stock") && !lower.contains("share")) {
        return emptyList()
    }

    val candidate = normalized
        .replace(Regex("""(?i)\b(ok|okay|so|then)\b"""), " ")
        .replace(Regex("""(?i)\b(what's|whats|what is|tell me|give me|show me|provide|get|please|can you|could you|do you know)\b"""), " ")
        .replace(Regex("""(?i)\b(the|latest|current|today|now|currently|right now)\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '?', '.', '!', ',', ':', ';')

    val intentPatterns = listOf(
        Regex("""(?i)^(?:stock\s+price|share\s+price|stock prices|share prices|price|prices|stock|stocks|share|shares|quote|quotes)\s+(?:of|for)\s+(.+)$"""),
        Regex("""(?i)^(.+?)\s+(?:stock\s+price|share\s+price|stock prices|share prices|price|prices|stock|stocks|share|shares|quote|quotes)$""")
    )

    val subjectRegion = intentPatterns.firstNotNullOfOrNull { pattern ->
        pattern.matchEntire(candidate)?.groupValues?.getOrNull(1)?.trim()
    } ?: return emptyList()

    return splitContextSubjectCandidates(subjectRegion)
}

internal fun extractContextFollowUpSubjects(userText: String): List<String> {
    val normalized = normalizeConversationText(userText)
    if (normalized.isBlank()) return emptyList()

    val subjectText = normalized
        .replace(Regex("""(?i)^(what about|how about|and then|and)\s+"""), "")
        .trim(' ', '?', '.', '!', ',', ':', ';')

    if (subjectText.isBlank()) return emptyList()
    return splitContextSubjectCandidates(subjectText)
}

internal fun looksLikeEntityFollowUp(userText: String): Boolean {
    val normalized = normalizeConversationText(userText).lowercase()
    if (normalized.isBlank()) return false
    if (
        normalized.startsWith("what about ") ||
        normalized.startsWith("how about ") ||
        normalized.startsWith("and ")
    ) {
        return true
    }

    val wordCount = normalized.split(Regex("""\s+""")).count { it.isNotBlank() }
    return wordCount in 1..3 &&
        !normalized.contains("price") &&
        !normalized.contains("stock") &&
        !normalized.contains("share") &&
        !normalized.contains("time")
}

internal fun isVagueStockPricePrompt(userText: String): Boolean {
    val normalized = normalizeConversationText(userText).lowercase()
    if (normalized.isBlank()) return false
    return normalized in setOf(
        "tell me the price",
        "what is the price",
        "what's the price",
        "the price",
        "price",
        "latest price",
        "tell me the stock price",
        "what is the stock price",
        "what's the stock price",
        "tell me the latest price",
        "what is the latest price"
    )
}

internal fun extractExplicitTimeLocation(userText: String): String? {
    val normalized = normalizeConversationText(userText)
    if (normalized.isBlank()) return null
    val lower = normalized.lowercase()
    if (!lower.contains("time")) return null

    val patterns = listOf(
        Regex("""(?i)\bwhat(?:'s| is)?\s+the\s+time\s+(?:in|at|for)\s+(.+)$"""),
        Regex("""(?i)\bwhat\s+time\s+is\s+it\s+(?:in|at|for)\s+(.+)$"""),
        Regex("""(?i)\bcurrent\s+time\s+(?:in|at|for)\s+(.+)$"""),
        Regex("""(?i)\blocal\s+time\s+(?:in|at|for)\s+(.+)$"""),
        Regex("""(?i)\btime\s+(?:in|at|for)\s+(.+)$""")
    )

    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(normalized)?.groupValues?.getOrNull(1)?.let(::cleanLocationCandidate)
    }
}

internal fun isImplicitTimeFollowUp(userText: String): Boolean {
    val normalized = normalizeConversationText(userText).lowercase()
    if (normalized.isBlank() || !normalized.contains("time")) return false
    if (normalized.contains("there")) return true
    return normalized in setOf(
        "what time is it",
        "what's the time",
        "the time",
        "time",
        "what time is it now",
        "what's the time now"
    )
}

internal fun isLikelyLocalOnlyRequest(userText: String): Boolean {
    val normalized = normalizeConversationText(userText).lowercase()
    if (normalized.isBlank()) return true

    val localPatterns = listOf(
        Regex("""\b(order|sort|alphabeti[sz]e|arrange)\b.*\b(by name|alphabetically)\b"""),
        Regex("""\b(rewrite|rephrase|paraphrase|translate|shorten|expand|improve|fix grammar|correct grammar|proofread)\b"""),
        Regex("""\b(summarize|summarise)\b.*\b(this|the following|attached|text|message|page)\b"""),
        Regex("""\b(compare)\b.*\b(these|the following)\b"""),
        Regex("""\bfill\s+(?:this|the)\s+form\b"""),
        Regex("""\b(current page|this page|screen|screenshot|attached file|attachment)\b"""),
        Regex("""<attached_file\b"""),
        Regex("""<web_search_context>""")
    )
    return localPatterns.any { it.containsMatchIn(normalized) }
}

internal fun isLikelyGeneralKnowledgeRequest(userText: String): Boolean {
    val normalized = normalizeConversationText(userText)
    val lower = normalized.lowercase()
    if (lower.isBlank()) return false
    if (isLikelyLocalOnlyRequest(lower)) return false

    if (extractExplicitStockSubjects(normalized).isNotEmpty()) return false
    if (extractExplicitTimeLocation(normalized) != null) return false

    val questionWords = listOf(
        "what",
        "who",
        "when",
        "where",
        "why",
        "how",
        "which",
        "tell me",
        "explain",
        "give me",
        "show me"
    )
    val currentSignals = listOf(
        "latest",
        "current",
        "today",
        "news",
        "war",
        "update",
        "happening",
        "happened",
        "status",
        "price",
        "release",
        "ceo",
        "president"
    )

    return normalized.endsWith("?") ||
        questionWords.any { lower.startsWith("$it ") } ||
        currentSignals.any { lower.contains(it) }
}

internal fun deriveGeneralKnowledgeQuery(userText: String): String? {
    val normalized = normalizeConversationText(userText)
    if (normalized.isBlank()) return null
    return deriveWebSearchQuery(normalized)
        ?: normalized.takeIf { it.isNotBlank() }?.take(180)
}

internal fun deriveContextualGeneralWebQueries(
    userText: String,
    historicalState: ConversationWorkingState
): List<String> {
    val normalized = normalizeConversationText(userText)
    if (normalized.isBlank()) return emptyList()

    if (normalized in setOf("what about that", "what about it", "and that", "and it", "and now", "what about now")) {
        return historicalState.lastGroundedQueries.ifEmpty { historicalState.activeSubjects }.take(1)
    }

    if (looksLikeEntityFollowUp(normalized)) {
        val subjects = extractContextFollowUpSubjects(normalized)
        if (subjects.isNotEmpty()) {
            val previousQuery = historicalState.lastGroundedQueries.firstOrNull()
                ?: historicalState.activeSubjects.firstOrNull()
                ?: return emptyList()
            val descriptor = deriveFollowUpQueryDescriptor(previousQuery)
            return subjects
                .map { "$it $descriptor".replace(Regex("""\s+"""), " ").trim() }
                .distinct()
                .take(4)
        }
    }

    if (normalized.endsWith("?") && normalized.split(Regex("""\s+""")).size <= 4) {
        return historicalState.lastGroundedQueries.ifEmpty { historicalState.activeSubjects }.take(1)
    }

    return emptyList()
}

internal fun splitContextSubjectCandidates(raw: String): List<String> {
    return raw
        .split(Regex("""\s*(?:,|/|&|\band\b|\bvs\b)\s*""", RegexOption.IGNORE_CASE))
        .map { part ->
            part
                .replace(
                    Regex(
                        """(?i)\b(the|latest|current|today|recent|now|right now|price|prices|stock|stocks|share|shares|quote|quotes|please|for me|of|for)\b"""
                    ),
                    " "
                )
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '?', '.', '!', ',', ':', ';')
        }
        .filter { it.isNotBlank() }
        .distinct()
}

private fun cleanLocationCandidate(raw: String): String? {
    return raw
        .replace(Regex("""(?i)\b(right now|now|currently|today)\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '?', '.', '!', ',', ':', ';')
        .takeIf { it.isNotBlank() }
        ?.take(120)
}
