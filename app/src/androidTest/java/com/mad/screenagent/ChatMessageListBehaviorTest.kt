package com.mad.screenagent

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mad.screenagent.data.db.MessageEntity
import com.mad.screenagent.shared.chatui.ChatMessageList
import com.mad.screenagent.shared.chatui.rememberChatMessageListBehavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatMessageListBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun conversationSwitchWithSameMessageCount_resetsToLatestMessage() {
        val conversationKey = mutableStateOf("conversation-a")
        val messages = mutableStateOf(buildMessages("conversation-a", 40))
        var listState: LazyListState? = null
        var coroutineScope: CoroutineScope? = null

        composeRule.setContent {
            coroutineScope = rememberCoroutineScope()
            val behavior = rememberChatMessageListBehavior(
                messages = messages.value,
                conversationKey = conversationKey.value
            )
            SideEffect { listState = behavior.listState }
            MaterialTheme {
                ChatMessageList(
                    messages = messages.value,
                    isLoading = false,
                    behavior = behavior,
                    modifier = Modifier.height(240.dp),
                    messageThumbnails = emptyMap<String, List<ImageBitmap>>()
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { listState?.canScrollForward == false }

        composeRule.runOnIdle {
            coroutineScope!!.launch { listState!!.scrollToItem(0) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) { listState?.firstVisibleItemIndex == 0 }

        composeRule.runOnIdle {
            conversationKey.value = "conversation-b"
            messages.value = buildMessages("conversation-b", 40)
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { listState?.canScrollForward == false }
    }

    @Test
    fun customScrollTrigger_scrollsNewConversationToBottomAfterSwap() {
        val conversationKey = mutableStateOf("conversation-a")
        val messages = mutableStateOf(buildMessages("conversation-a", 40))
        var scrollTrigger by mutableIntStateOf(0)
        var listState: LazyListState? = null
        var coroutineScope: CoroutineScope? = null

        composeRule.setContent {
            coroutineScope = rememberCoroutineScope()
            val behavior = rememberChatMessageListBehavior(
                messages = messages.value,
                conversationKey = conversationKey.value,
                scrollToBottomTrigger = scrollTrigger
            )
            SideEffect { listState = behavior.listState }
            MaterialTheme {
                ChatMessageList(
                    messages = messages.value,
                    isLoading = false,
                    behavior = behavior,
                    modifier = Modifier.height(240.dp),
                    messageThumbnails = emptyMap<String, List<ImageBitmap>>()
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { listState?.canScrollForward == false }

        composeRule.runOnIdle {
            coroutineScope!!.launch { listState!!.scrollToItem(0) }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) { listState?.firstVisibleItemIndex == 0 }

        composeRule.runOnIdle {
            conversationKey.value = "conversation-b"
            messages.value = buildMessages("conversation-b", 40)
            scrollTrigger++
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { listState?.canScrollForward == false }
    }

    @Test
    fun expandingEarlierMessageWhilePinnedToBottom_keepsLatestVisible() {
        val conversationKey = mutableStateOf("conversation-a")
        val messages = mutableStateOf(buildMessages("conversation-a", 40))
        var listState: LazyListState? = null

        composeRule.setContent {
            val behavior = rememberChatMessageListBehavior(
                messages = messages.value,
                conversationKey = conversationKey.value
            )
            SideEffect { listState = behavior.listState }
            MaterialTheme {
                ChatMessageList(
                    messages = messages.value,
                    isLoading = false,
                    behavior = behavior,
                    modifier = Modifier.height(240.dp),
                    messageThumbnails = emptyMap<String, List<ImageBitmap>>()
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { listState?.canScrollForward == false }

        composeRule.runOnIdle {
            messages.value = messages.value.mapIndexed { index, message ->
                if (index == messages.value.lastIndex - 1) {
                    message.copy(content = List(60) { "Expanded line $it" }.joinToString("\n"))
                } else {
                    message
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) { listState?.canScrollForward == false }
    }

    @Test
    fun duplicateMessageIds_renderOnlyLatestCopy() {
        val messages = mutableStateOf(
            listOf(
                MessageEntity(
                    id = "dup-message",
                    conversationId = "conversation-a",
                    role = "user",
                    content = "Old copy",
                    createdAt = 1L
                ),
                MessageEntity(
                    id = "dup-message",
                    conversationId = "conversation-a",
                    role = "user",
                    content = "Latest copy",
                    createdAt = 2L
                ),
                MessageEntity(
                    id = "assistant-1",
                    conversationId = "conversation-a",
                    role = "assistant",
                    content = "Assistant reply",
                    createdAt = 3L
                )
            )
        )

        composeRule.setContent {
            val behavior = rememberChatMessageListBehavior(
                messages = messages.value,
                conversationKey = "conversation-a"
            )
            MaterialTheme {
                ChatMessageList(
                    messages = messages.value,
                    isLoading = false,
                    behavior = behavior,
                    modifier = Modifier.height(240.dp),
                    messageThumbnails = emptyMap<String, List<ImageBitmap>>()
                )
            }
        }

        composeRule.waitForIdle()
        assertEquals(1, composeRule.onAllNodesWithText("Latest copy").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Old copy").fetchSemanticsNodes().size)
    }

    private fun buildMessages(conversationId: String, count: Int): List<MessageEntity> {
        return List(count) { index ->
            MessageEntity(
                id = "$conversationId-$index",
                conversationId = conversationId,
                role = if (index % 2 == 0) "user" else "assistant",
                content = "Message $index",
                createdAt = index.toLong()
            )
        }
    }
}
