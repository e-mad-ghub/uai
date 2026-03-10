package com.example.uai.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

@Stable
class ChatMessageListBehavior internal constructor(
    val listState: LazyListState,
    val isAtBottom: Boolean,
    internal val nestedScrollConnection: NestedScrollConnection
)

@Composable
fun rememberChatMessageListBehavior(
    messages: List<MessageEntity>
): ChatMessageListBehavior {
    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val isAtBottom by remember(listState) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    var autoScrollEnabled by rememberSaveable { mutableStateOf(true) }
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

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) autoScrollEnabled = true
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && autoScrollEnabled) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val lastMessageContent = messages.lastOrNull()?.content
    LaunchedEffect(lastMessageContent) {
        if (messages.lastOrNull()?.isStreaming == true && autoScrollEnabled) {
            listState.scroll { scrollBy(100_000f) }
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

@Composable
fun ChatMessageList(
    messages: List<MessageEntity>,
    isLoading: Boolean,
    behavior: ChatMessageListBehavior,
    modifier: Modifier = Modifier,
    messageThumbnails: Map<String, List<ImageBitmap>> = emptyMap(),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    verticalSpacing: Dp = 6.dp,
    emptyContent: (@Composable () -> Unit)? = null,
    overlayContent: (@Composable BoxScope.(isAtBottom: Boolean) -> Unit)? = null,
    replyActionForMessage: (MessageEntity) -> (() -> Unit)? = { null }
) {
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = behavior.listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(behavior.nestedScrollConnection),
            contentPadding = contentPadding,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(verticalSpacing)
        ) {
            if (messages.isEmpty() && emptyContent != null) {
                item(contentType = "empty") { emptyContent() }
            }

            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    thumbnails = messageThumbnails[message.id] ?: emptyList(),
                    onReply = replyActionForMessage(message)
                )
            }

            if (isLoading && messages.lastOrNull()?.isStreaming != true) {
                item(contentType = "loading") {
                    Row(Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        overlayContent?.invoke(this, behavior.isAtBottom)
    }
}
