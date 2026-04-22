package com.mad.screenagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.db.MessageEntity
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.feature.bubble.ChatPanel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniChatHeaderLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun headerNewConversationIcon_staysCloserToConversationThanAssistant() {
        val conversationA = ConversationEntity(
            id = "conversation-a",
            title = "Chat A",
            agentId = "agent-a",
            agentName = "Agent A",
            createdAt = 0L,
            updatedAt = 0L,
        )
        val conversationB = conversationA.copy(id = "conversation-b", title = "Chat B")

        val agent = AgentConfig(
            id = "agent-a",
            name = "Agent A"
        )

        var density: Density? = null
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            SideEffect { density = currentDensity }
            MaterialTheme {
                // Constrain width so the "trailing" behavior is measurable and stable.
                Box(modifier = Modifier.width(360.dp)) {
                    ChatPanel(
                        messages = emptyList<MessageEntity>(),
                        conversationKey = conversationA.id,
                        inputText = "",
                        isLoading = false,
                        agentName = agent.name,
                        agentTokenInfo = "200 / 1,000 tokens",
                        selectedAgentId = agent.id,
                        hasSelectedAgent = true,
                        agents = listOf(agent),
                        conversations = listOf(conversationA, conversationB),
                        currentConversationId = conversationA.id,
                        pendingImages = emptyList(),
                        pendingFileName = null,
                        hasAttachment = false,
                        onInputChange = {},
                        onSend = {},
                        onStop = {},
                        onMinimize = {},
                        onAgentSelect = {},
                        onConversationSelect = {},
                        onNewConversation = {},
                        onPickGallery = {},
                        onPickCamera = {},
                        onPickFile = {},
                        onTakeScreenshot = {},
                        onClearAttachment = {},
                    )
                }
            }
        }

        val conversationDropdownBounds = composeRule
            .onNodeWithContentDescription("Select chat")
            .fetchSemanticsNode()
            .boundsInRoot
        val newConversationBounds = composeRule
            .onNodeWithContentDescription("New conversation")
            .fetchSemanticsNode()
            .boundsInRoot
        val assistantDropdownBounds = composeRule
            .onNodeWithContentDescription("Select assistant")
            .fetchSemanticsNode()
            .boundsInRoot

        val maxConversationGapPx = with(density!!) { 24.dp.toPx() }
        val gapPx = newConversationBounds.left - conversationDropdownBounds.right
        val newCenterX = newConversationBounds.left + (newConversationBounds.width / 2f)
        val conversationCenterX = conversationDropdownBounds.left + (conversationDropdownBounds.width / 2f)
        val assistantCenterX = assistantDropdownBounds.left + (assistantDropdownBounds.width / 2f)
        val distanceToConversation = kotlin.math.abs(newCenterX - conversationCenterX)
        val distanceToAssistant = kotlin.math.abs(assistantCenterX - newCenterX)
        assertTrue(
            "Expected '+' icon to sit near conversation dropdown; gapPx=$gapPx",
            gapPx in 0f..maxConversationGapPx
        )
        assertTrue(
            "Expected '+' icon to be closer to conversation than assistant; " +
                "distanceToConversation=$distanceToConversation distanceToAssistant=$distanceToAssistant",
            distanceToConversation < distanceToAssistant
        )
    }
}
