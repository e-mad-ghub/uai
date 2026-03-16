package com.example.uai.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.uai.data.db.MessageEntity

private suspend fun LazyListState.scrollToConversationEnd() {
    scroll {
        while (scrollBy(10_000f) > 0f) {
            // Keep consuming until the list clamps at the real bottom.
        }
    }
}

@Stable
class ChatMessageListBehavior internal constructor(
    val listState: LazyListState,
    val isAtBottom: Boolean,
    internal val nestedScrollConnection: NestedScrollConnection
)

@Composable
fun rememberChatMessageListBehavior(messages: List<MessageEntity>): ChatMessageListBehavior {
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val isAtBottom by remember(listState) {
        derivedStateOf {
            !listState.canScrollForward
        }
    }

    var autoScrollEnabled by rememberSaveable { mutableStateOf(true) }
    var lastSeenConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSeenMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastViewportHeight by rememberSaveable { mutableStateOf<Int?>(null) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    autoScrollEnabled = false
                }
                return Offset.Zero
            }
        }
    }

    val latestMessage = messages.lastOrNull()

    LaunchedEffect(isAtBottom, listState.isScrollInProgress) {
        if (isAtBottom && !listState.isScrollInProgress) {
            autoScrollEnabled = true
        }
    }

    LaunchedEffect(latestMessage?.id) {
        val currentConversationId = latestMessage?.conversationId
        if (currentConversationId == null) {
            lastSeenConversationId = null
            lastSeenMessageId = null
            return@LaunchedEffect
        }

        if (lastSeenConversationId != currentConversationId) {
            lastSeenConversationId = currentConversationId
            lastSeenMessageId = latestMessage.id
            return@LaunchedEffect
        }

        if (latestMessage.id != lastSeenMessageId && latestMessage.role == "user") {
            autoScrollEnabled = true
        }

        lastSeenMessageId = latestMessage.id
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && autoScrollEnabled) {
            listState.animateScrollToItem(messages.lastIndex)
            listState.scrollToConversationEnd()
        }
    }

    val lastMessageContent = latestMessage?.content
    LaunchedEffect(lastMessageContent) {
        if (latestMessage?.isStreaming == true && autoScrollEnabled) {
            listState.scrollToConversationEnd()
        }
    }

    val latestAutoScrollEnabled by rememberUpdatedState(autoScrollEnabled)
    val latestIsAtBottom by rememberUpdatedState(isAtBottom)
    val hasMessages by rememberUpdatedState(messages.isNotEmpty())
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        }.collect { viewportHeight ->
            val previousViewportHeight = lastViewportHeight
            lastViewportHeight = viewportHeight

            if (previousViewportHeight == null) return@collect

            val viewportDelta = viewportHeight - previousViewportHeight
            when {
                hasMessages && (latestAutoScrollEnabled || latestIsAtBottom) -> {
                    listState.scrollToConversationEnd()
                }
                viewportDelta != 0 -> {
                // The mini chat panel grows upward from the bottom. Compensate the list scroll
                // by the same amount so the text the user is reading stays visually anchored.
                    listState.scrollBy(-viewportDelta.toFloat())
                }
            }
        }
    }

    return remember(listState, isAtBottom, nestedScrollConnection) {
        ChatMessageListBehavior(
            listState = listState,
            isAtBottom = isAtBottom,
            nestedScrollConnection = nestedScrollConnection
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageList(
    messages: List<MessageEntity>,
    isLoading: Boolean,
    loadingStatusText: String? = null,
    behavior: ChatMessageListBehavior,
    modifier: Modifier = Modifier,
    messageThumbnails: Map<String, List<ImageBitmap>> = emptyMap(),
    onBackgroundDoubleTap: (() -> Unit)? = null,
    onBackgroundLongPress: (() -> Unit)? = null,
    onMessageDoubleTap: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    verticalSpacing: Dp = 6.dp,
    emptyContent: (@Composable () -> Unit)? = null,
    overlayContent: (@Composable BoxScope.(isAtBottom: Boolean) -> Unit)? = null,
    replyActionForMessage: (MessageEntity) -> (() -> Unit)? = { null }
) {
    val animatedLoadingStatusText = rememberLoadingStatusLabel(
        isLoading = isLoading,
        baseStatusText = loadingStatusText
    )
    val showAssistantNames = remember(messages) {
        messages
            .asSequence()
            .filter { it.role == "assistant" }
            .map { it.agentName }
            .distinct()
            .take(2)
            .count() > 1
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = behavior.listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(behavior.nestedScrollConnection)
                .then(
                    if (onBackgroundDoubleTap != null || onBackgroundLongPress != null) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onDoubleClick = onBackgroundDoubleTap,
                            onLongClick = onBackgroundLongPress
                        )
                    } else {
                        Modifier
                    }
                ),
            contentPadding = contentPadding,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(verticalSpacing)
        ) {
            if (messages.isEmpty() && emptyContent != null) {
                item(contentType = "empty") { emptyContent() }
            }

            items(
                items = messages,
                key = { it.id },
                contentType = { it.role }
            ) { message ->
                MessageBubble(
                    message = message,
                    showAgentName = message.role != "assistant" || showAssistantNames,
                    thumbnails = messageThumbnails[message.id] ?: emptyList(),
                    streamingStatusText = if (message.role == "assistant" && message.isStreaming && message.content.isEmpty()) {
                        animatedLoadingStatusText
                    } else {
                        null
                    },
                    onDoubleTap = onMessageDoubleTap,
                    onReply = replyActionForMessage(message)
                )
            }

            if (isLoading && messages.lastOrNull()?.isStreaming != true) {
                item(contentType = "loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        if (!animatedLoadingStatusText.isNullOrBlank()) {
                            Text(
                                text = animatedLoadingStatusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        overlayContent?.invoke(this, behavior.isAtBottom)
    }
}
