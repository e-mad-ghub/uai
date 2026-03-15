package com.example.uai.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

private val defaultLoadingPhrases = listOf(
    "Thinking…",
    "Processing your request…",
    "Generating the best answer…",
    "Putting the response together…"
)

@Composable
internal fun rememberLoadingStatusLabel(
    isLoading: Boolean,
    baseStatusText: String?
): String? {
    if (!isLoading) return null

    val primaryStatus = baseStatusText
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val phrases = remember(primaryStatus) {
        buildList {
            primaryStatus?.let { add(it) }
            addAll(defaultLoadingPhrases.filterNot { candidate ->
                primaryStatus?.equals(candidate, ignoreCase = true) == true
            })
        }
    }

    val stageIndex by produceState(
        initialValue = 0,
        key1 = isLoading,
        key2 = primaryStatus
    ) {
        var nextIndex = 0
        value = nextIndex
        while (isLoading && phrases.isNotEmpty()) {
            delay(if (nextIndex == 0 && primaryStatus != null) 1800L else 2200L)
            nextIndex = (nextIndex + 1) % phrases.size
            value = nextIndex
        }
    }

    return phrases.getOrNull(stageIndex)
}
