package com.mad.screenagent.shared.streaming

import android.util.Log
import com.mad.screenagent.data.model.AgentConfig

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
            ConversationIntent.NEWS -> "Fetching the latest news…"
            ConversationIntent.TECH_SEARCH -> "Searching tech sources…"
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

private fun logGateway(message: String) {
    try { Log.d("UAI_WEB", message) } catch (_: RuntimeException) {}
}

class WebGateway(
    private val groundingService: WebGroundingService,
    private val searchPlanningService: SearchPlanningService? = null,
    private val sessionStore: ConversationContextStore = ConversationContextStore(),
    private val planner: WebTurnPlanner = WebTurnPlanner()
) {
    private data class HeuristicResolution(
        val previousSession: ConversationWorkingState?,
        val plannedTurn: PlannedWebTurn
    )

    private suspend fun resolveHeuristicTurn(
        conversationKey: String?,
        messages: List<ChatMessage>
    ): HeuristicResolution {
        val previousSession = sessionStore.get(conversationKey)
        return HeuristicResolution(
            previousSession = previousSession,
            plannedTurn = planner.planResolved(
                conversationKey = conversationKey,
                messages = messages,
                sessionState = previousSession
            )
        )
    }

    suspend fun shouldPrepareTurn(
        conversationKey: String?,
        messages: List<ChatMessage>
    ): Boolean {
        val heuristic = resolveHeuristicTurn(
            conversationKey = conversationKey,
            messages = messages
        )
        val plan = heuristic.plannedTurn.plan
        logGateway("precheck mode=${plan.mode} queries=${plan.queries.joinToString("|")}")
        return plan.mode != WebTurnMode.NONE
    }

    private fun inferIntentFromQueries(queries: List<String>): ConversationIntent {
        if (queries.isEmpty()) return ConversationIntent.NONE
        val allCurrentTime = queries.all { it.startsWith("current time in ", ignoreCase = true) }
        if (allCurrentTime) return ConversationIntent.CURRENT_TIME

        val allStock = queries.all { query ->
            query.contains("stock price", ignoreCase = true) ||
                query.contains("share price", ignoreCase = true)
        }
        if (allStock) return ConversationIntent.STOCK_PRICE

        return ConversationIntent.GENERAL_WEB
    }

    private fun buildPlanFromQueries(queries: List<String>): WebTurnPlan {
        val distinctQueries = queries.filter { it.isNotBlank() }.distinct()
        val intent = inferIntentFromQueries(distinctQueries)
        val mode = when {
            distinctQueries.isEmpty() -> WebTurnMode.NONE
            distinctQueries.size > 1 -> WebTurnMode.TOOL_SEARCH
            else -> WebTurnMode.AUTO_GROUND
        }
        val statusText = when (intent) {
            ConversationIntent.STOCK_PRICE -> {
                if (distinctQueries.size > 1) {
                    "Looking up the latest market prices…"
                } else {
                    "Looking up the latest market price…"
                }
            }
            ConversationIntent.CURRENT_TIME -> "Checking the current time…"
            ConversationIntent.NEWS -> "Fetching the latest news…"
            ConversationIntent.TECH_SEARCH -> "Searching tech sources…"
            else -> when (mode) {
                WebTurnMode.NONE -> null
                WebTurnMode.AUTO_GROUND -> "Looking online for fresh results…"
                WebTurnMode.TOOL_SEARCH -> {
                    if (distinctQueries.size == 1) {
                        "Searching online for a targeted result…"
                    } else {
                        "Searching online across ${distinctQueries.size} targeted lookups…"
                    }
                }
            }
        }
        return WebTurnPlan(
            mode = mode,
            queries = distinctQueries,
            statusText = statusText,
            intent = intent
        )
    }

    suspend fun prepareTurn(
        conversationKey: String?,
        messages: List<ChatMessage>,
        planningConfig: AgentConfig? = null,
        onStatusChanged: (String?) -> Unit = {}
    ): PreparedWebTurn {
        val heuristic = resolveHeuristicTurn(
            conversationKey = conversationKey,
            messages = messages
        )
        val previousSession = heuristic.previousSession
        val heuristicTurn = heuristic.plannedTurn
        logGateway("heuristic mode=${heuristicTurn.plan.mode} queries=${heuristicTurn.plan.queries.joinToString("|")}")
        val plannedSearches = if (planningConfig != null) {
            searchPlanningService?.planSearches(
                messages = messages,
                config = planningConfig,
                previousState = previousSession,
                onStatusChanged = onStatusChanged
            )
        } else {
            null
        }
        logGateway("AI planning needsSearch=${plannedSearches?.needsSearch} queries=${plannedSearches?.queries?.joinToString("|").orEmpty()}")
        val plan = when {
            plannedSearches?.needsSearch == true && plannedSearches.queries.isNotEmpty() ->
                buildPlanFromQueries(plannedSearches.queries)
            else -> heuristicTurn.plan
        }
        logGateway("final plan mode=${plan.mode} queries=${plan.queries.joinToString("|")}")
        val resolvedState = heuristicTurn.sessionState

        if (plan.mode == WebTurnMode.NONE) {
            logGateway("skipping web search — no search needed for this turn")
            val inertState = resolvedState?.copy(
                lastUpdatedAt = System.currentTimeMillis()
            )
            inertState?.also { sessionStore.put(it) }
            return PreparedWebTurn(
                messages = messages,
                grounding = null,
                plan = plan,
                sessionState = inertState
            )
        }

        val prepared = groundingService.prepareMessagesIfNeeded(
            messages = messages,
            plannedQueries = plan.queries,
            statusText = plan.statusText ?: "Looking online for fresh results…",
            onStatusChanged = onStatusChanged,
            intent = plan.intent
        )

        val sessionKey = conversationKey ?: resolvedState?.conversationKey.orEmpty()
        val nextSession = ConversationWorkingState(
            conversationKey = sessionKey,
            activeIntent = plan.intent,
            activeSubjects = when (plan.intent) {
                ConversationIntent.STOCK_PRICE -> plan.queries.map { query ->
                    query.replace(Regex("""(?i)\bstock price yahoo finance\b"""), " ")
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                }
                ConversationIntent.CURRENT_TIME -> plan.queries.map { query ->
                    query.replace(Regex("""(?i)^current time in\s+"""), "")
                        .trim()
                }
                else -> plan.queries
            },
            activeLocation = when (plan.intent) {
                ConversationIntent.CURRENT_TIME -> plan.queries.firstOrNull()
                    ?.replace(Regex("""(?i)^current time in\s+"""), "")
                    ?.trim()
                else -> resolvedState?.activeLocation
            },
            freshnessRequired = plan.mode != WebTurnMode.NONE,
            lastGroundedQueries = plan.queries,
            lastMode = plan.mode,
            lastResolvedUserText = normalizeConversationText(
                messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            ),
            lastUpdatedAt = System.currentTimeMillis()
        ).takeIf { it.conversationKey.isNotBlank() }
            ?.also { sessionStore.put(it) }

        logGateway("grounding=${if (prepared.grounding != null) "found sources=${prepared.grounding.sources.size} facts=${prepared.grounding.facts.size}" else "null — no sources returned"}")
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

    private fun typeToIntent(type: String?): ConversationIntent? = when (type) {
        "news" -> ConversationIntent.NEWS
        "stock" -> ConversationIntent.STOCK_PRICE
        "tech" -> ConversationIntent.TECH_SEARCH
        "general" -> ConversationIntent.GENERAL_WEB
        else -> null
    }

    suspend fun executeSearchTool(
        conversationKey: String?,
        query: String,
        type: String? = null,
        onStatusChanged: (String?) -> Unit = {}
    ): WebGroundingResult? {
        val toolMessages = listOf(ChatMessage(role = "user", content = query))
        val previousSession = sessionStore.get(conversationKey)
        val plannedTurn = planner.planResolved(
            conversationKey = conversationKey,
            messages = toolMessages,
            sessionState = previousSession
        )

        // Model-declared type overrides heuristic intent
        val declaredIntent = typeToIntent(type)
        val statusText = when (declaredIntent) {
            ConversationIntent.NEWS -> "Fetching latest news…"
            ConversationIntent.STOCK_PRICE -> "Looking up the latest market price…"
            ConversationIntent.TECH_SEARCH -> "Searching tech sources…"
            else -> "Searching online for requested info…"
        }

        val fallbackPlan = WebTurnPlan(
            mode = WebTurnMode.TOOL_SEARCH,
            queries = listOf(query),
            statusText = statusText,
            intent = declaredIntent ?: ConversationIntent.GENERAL_WEB
        )
        val basePlan = plannedTurn.plan.takeIf { it.queries.isNotEmpty() } ?: fallbackPlan
        // Apply declared intent if model specified a type
        val effectivePlan = if (declaredIntent != null) {
            basePlan.copy(intent = declaredIntent, statusText = statusText)
        } else {
            basePlan
        }
        val grounded = groundingService.prepareMessagesIfNeeded(
            messages = toolMessages,
            plannedQueries = effectivePlan.queries,
            statusText = effectivePlan.statusText ?: "Searching online for requested info…",
            onStatusChanged = onStatusChanged,
            intent = effectivePlan.intent
        ).grounding

        val nextSession = plannedTurn.sessionState
            ?.takeIf { it.conversationKey.isNotBlank() }
            ?.copy(
                activeIntent = effectivePlan.intent,
                activeSubjects = effectivePlan.queries,
                freshnessRequired = true,
                lastMode = WebTurnMode.TOOL_SEARCH,
                lastGroundedQueries = effectivePlan.queries,
                lastResolvedUserText = query,
                lastUpdatedAt = System.currentTimeMillis()
            )

        if (nextSession != null) {
            sessionStore.put(nextSession)
        }

        return grounded
    }
}
