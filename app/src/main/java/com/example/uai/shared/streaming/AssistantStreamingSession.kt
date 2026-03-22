package com.example.uai.shared.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Central streaming display controller shared by all three chat contexts
 * (ConversationDetailViewModel, AgoraDetailViewModel, FloatingBubbleService).
 *
 * A typewriter loop reveals content at [charsPerTick] characters every [tickMs] milliseconds,
 * smoothing out token-delivery bursts from the network so the user sees a steady flow of text
 * instead of large chunks appearing all at once.
 *
 * State lifecycle:
 *   create → start() → onToken()* → (finalize() | markStopped() | markDeleted())
 *
 * After finalize/markStopped/markDeleted, the session is done and should not be reused.
 */
class AssistantStreamingSession(
    val messageId: String,
    private val tickMs: Long = 80L,
    private val charsPerTick: Int = 4
) {
    data class State(
        val messageId: String,
        val content: String,
        val isStreaming: Boolean = true,
        /** When true the message is excluded from the list, suppressing the typing indicator
         *  even before Room propagates a deletion. */
        val hidden: Boolean = false
    )

    private val _state = MutableStateFlow(State(messageId, ""))
    val state: StateFlow<State> = _state

    @Volatile private var latestContent = ""
    private var displayLength = 0
    private var typewriterJob: Job? = null

    /** Launches the typewriter loop in [scope]. Must be called once before the first token. */
    fun start(scope: CoroutineScope) {
        typewriterJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                val target = latestContent
                if (displayLength < target.length) {
                    displayLength = minOf(displayLength + charsPerTick, target.length)
                    _state.update { it.copy(content = target.substring(0, displayLength)) }
                }
            }
        }
    }

    /**
     * Called on every new token with the FULL sanitized accumulated content so far.
     * The typewriter will gradually reveal up to this point.
     */
    fun onToken(sanitizedAccumulatedContent: String) {
        latestContent = sanitizedAccumulatedContent
    }

    /**
     * Called when the user presses Stop.
     * Cancels the typewriter and immediately shows the full accumulated content.
     * If nothing was received yet, hides the placeholder immediately so no empty
     * bubble flashes while the finally block runs the DB cleanup.
     */
    fun markStopped() {
        typewriterJob?.cancel()
        typewriterJob = null
        if (latestContent.isBlank()) {
            _state.update { it.copy(isStreaming = false, hidden = true) }
        } else {
            _state.update { it.copy(content = latestContent, isStreaming = false) }
        }
    }

    /**
     * Called in the finally block when the response was blank (deleted from Room).
     * Hides the message immediately via the overlay — no typing indicator flash even
     * if the Room Flow emission lags behind.
     */
    fun markDeleted() {
        typewriterJob?.cancel()
        typewriterJob = null
        _state.update { it.copy(isStreaming = false, hidden = true) }
    }

    /**
     * Called in the finally block after the Room write completes.
     * Cancels the typewriter and locks in the final content.
     */
    fun finalize(finalContent: String) {
        typewriterJob?.cancel()
        typewriterJob = null
        _state.update { it.copy(content = finalContent, isStreaming = false) }
    }
}
