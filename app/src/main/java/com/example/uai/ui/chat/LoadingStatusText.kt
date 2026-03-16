package com.example.uai.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

private val defaultLoadingPhrases = listOf(
    "Thinking…",
    "Thinking, because loading is lame :D …",
    "Consulting the ancient scrolls of the internet…",
    "Bribing my neurons with coffee…",
    "Summoning an answer from the void…",
    "Herding words into sentences…",
    "Pretending I know what I'm doing…",
    "Running on vibes and matrix math…",
    "Staring at your message really hard…",
    "Definitely not just making this up…",
    "Cross-referencing my 800 billion parameters…",
    "No, I'm not sleeping. This is called deep thinking.",
    "Hallucinating responsibly…",
    "Statistically predicting something useful…",
    "Not Googling. Totally not Googling.",
    "Asking the rubber duck…",
    "Cooking…",
    "Two sips of coffee away…",
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
        val shuffled = defaultLoadingPhrases
            .filterNot { primaryStatus?.equals(it, ignoreCase = true) == true }
            .shuffled()
        buildList {
            primaryStatus?.let { add(it) }
            addAll(shuffled)
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
            delay(if (nextIndex == 0 && primaryStatus != null) 2000L else 3200L)
            nextIndex = (nextIndex + 1) % phrases.size
            value = nextIndex
        }
    }

    return phrases.getOrNull(stageIndex)
}
