package com.example.uai.ai

enum class WebTurnMode {
    NONE,
    AUTO_GROUND,
    TOOL_SEARCH
}

data class WebTurnPlan(
    val mode: WebTurnMode,
    val queries: List<String>,
    val statusText: String?,
    val intent: ConversationIntent = ConversationIntent.NONE
)

data class PlannedWebTurn(
    val plan: WebTurnPlan,
    val sessionState: ConversationWorkingState?
)

data class PreparedWebTurn(
    val messages: List<ChatMessage>,
    val grounding: WebGroundingResult?,
    val plan: WebTurnPlan,
    val sessionState: ConversationWorkingState?
)

class WebTurnPlanner(
    private val contextResolver: ConversationContextResolver = ConversationContextResolver()
) {
    fun planResolved(
        conversationKey: String?,
        messages: List<ChatMessage>,
        sessionState: ConversationWorkingState? = null
    ): PlannedWebTurn {
        val resolvedTurn = contextResolver.resolve(conversationKey, messages, sessionState)
        val queries = resolvedTurn.queries.distinct()
        if (queries.isEmpty()) {
            return PlannedWebTurn(
                plan = WebTurnPlan(
                    mode = WebTurnMode.NONE,
                    queries = emptyList(),
                    statusText = null,
                    intent = resolvedTurn.intent
                ),
                sessionState = resolvedTurn.state
            )
        }

        val mode = when {
            queries.size > 1 -> WebTurnMode.TOOL_SEARCH
            resolvedTurn.usedFollowUpContext && sessionState?.lastMode == WebTurnMode.TOOL_SEARCH ->
                WebTurnMode.TOOL_SEARCH
            else -> WebTurnMode.AUTO_GROUND
        }

        val statusText = when (resolvedTurn.intent) {
            ConversationIntent.STOCK_PRICE -> {
                if (queries.size > 1) {
                    "Looking up the latest market prices…"
                } else {
                    "Looking up the latest market price…"
                }
            }
            ConversationIntent.CURRENT_TIME -> "Checking the current time…"
            else -> when (mode) {
                WebTurnMode.NONE -> null
                WebTurnMode.AUTO_GROUND -> "Looking online for fresh results…"
                WebTurnMode.TOOL_SEARCH -> {
                    if (queries.size == 1) {
                        "Searching online for a targeted result…"
                    } else {
                        "Searching online across ${queries.size} targeted lookups…"
                    }
                }
            }
        }

        return PlannedWebTurn(
            plan = WebTurnPlan(
                mode = mode,
                queries = queries,
                statusText = statusText,
                intent = resolvedTurn.intent
            ),
            sessionState = resolvedTurn.state
        )
    }

    fun plan(
        messages: List<ChatMessage>,
        sessionState: ConversationWorkingState? = null
    ): WebTurnPlan {
        return planResolved(
            conversationKey = sessionState?.conversationKey,
            messages = messages,
            sessionState = sessionState
        ).plan
    }
}

class WebGateway(
    private val groundingService: WebGroundingService,
    private val sessionStore: ConversationContextStore = ConversationContextStore(),
    private val planner: WebTurnPlanner = WebTurnPlanner()
) {

    suspend fun prepareTurn(
        conversationKey: String?,
        messages: List<ChatMessage>,
        onStatusChanged: (String?) -> Unit = {}
    ): PreparedWebTurn {
        val previousSession = sessionStore.get(conversationKey)
        val plannedTurn = planner.planResolved(
            conversationKey = conversationKey,
            messages = messages,
            sessionState = previousSession
        )
        val plan = plannedTurn.plan
        val resolvedState = plannedTurn.sessionState

        if (plan.mode == WebTurnMode.NONE) {
            resolvedState?.copy(
                lastUpdatedAt = System.currentTimeMillis()
            )?.also { sessionStore.put(it) }
            return PreparedWebTurn(
                messages = messages,
                grounding = null,
                plan = plan,
                sessionState = resolvedState
            )
        }

        val prepared = groundingService.prepareMessagesIfNeeded(
            messages = messages,
            plannedQueries = plan.queries,
            statusText = plan.statusText ?: "Looking online for fresh results…",
            onStatusChanged = onStatusChanged,
            intent = plan.intent
        )

        val nextSession = resolvedState
            ?.takeIf { it.conversationKey.isNotBlank() }
            ?.copy(
                lastMode = plan.mode,
                lastGroundedQueries = plan.queries,
                lastUpdatedAt = System.currentTimeMillis()
            )
            ?.also { sessionStore.put(it) }

        return PreparedWebTurn(
            messages = prepared.messages,
            grounding = prepared.grounding,
            plan = plan,
            sessionState = nextSession
        )
    }

    fun applyGrounding(
        messages: List<ChatMessage>,
        grounding: WebGroundingResult
    ): List<ChatMessage> = groundingService.applyGrounding(messages, grounding)
}
