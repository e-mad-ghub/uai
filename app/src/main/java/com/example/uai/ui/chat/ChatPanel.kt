package com.example.uai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.uai.data.db.ConversationEntity
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.model.AgentConfig

@Composable
fun ChatPanel(
    messages: List<MessageEntity>,
    inputText: String,
    isLoading: Boolean,
    agentName: String,
    agents: List<AgentConfig>,
    conversations: List<ConversationEntity>,
    currentConversationId: String?,
    pendingImages: List<Triple<String, ImageBitmap?, String?>>,
    pendingFileName: String?,
    hasAttachment: Boolean,
    messageThumbnails: Map<String, List<ImageBitmap>> = emptyMap(),
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenInApp: (() -> Unit)? = null,
    onAgentSelect: (AgentConfig) -> Unit,
    onConversationSelect: (String?) -> Unit,
    onNewConversation: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    onPickFile: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onClearAttachment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val messageListBehavior = rememberChatMessageListBehavior(messages)
    val expandedMsgHeight = (configuration.screenHeightDp.dp * 0.64f).coerceIn(280.dp, 560.dp)
    val compactMsgHeight = (expandedMsgHeight * 0.5f).coerceAtLeast(160.dp)
    val animatedMaxMsgHeight by animateDpAsState(
        targetValue = if (messageListBehavior.shouldUseCompactViewport) compactMsgHeight else expandedMsgHeight,
        animationSpec = tween(durationMillis = 220),
        label = "miniChatMessageAreaHeight"
    )

    var agentDropdownExpanded by remember { mutableStateOf(false) }
    var conversationDropdownExpanded by remember { mutableStateOf(false) }
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }
    val hasExistingConversations = conversations.isNotEmpty()
    val currentConversationTitle = conversations
        .firstOrNull { it.id == currentConversationId }
        ?.title
        ?: "New Chat"

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        // Custom layout: header fixed at top, input fixed at bottom,
        // messages fills whatever remains — no overflow or squishing with keyboard.
        Layout(
            content = {
                // Slot 0: header + top divider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            Row(
                                modifier = Modifier
                                    .clickable(enabled = agents.size > 1) { agentDropdownExpanded = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(agentName, style = MaterialTheme.typography.titleMedium)
                                if (agents.size > 1) {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Select agent",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = agentDropdownExpanded,
                                onDismissRequest = { agentDropdownExpanded = false }
                            ) {
                                agents.forEach { agent ->
                                    DropdownMenuItem(
                                        text = { Text(agent.name) },
                                        onClick = {
                                            agentDropdownExpanded = false
                                            onAgentSelect(agent)
                                        },
                                        leadingIcon = if (agent.name == agentName) ({
                                            Icon(
                                                Icons.Default.SmartToy,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }) else null
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = hasExistingConversations) {
                                        conversationDropdownExpanded = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentConversationTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 120.dp)
                                )
                                if (hasExistingConversations) {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Select chat",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = conversationDropdownExpanded,
                                onDismissRequest = { conversationDropdownExpanded = false }
                            ) {
                                conversations.forEach { conversation ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                conversation.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            conversationDropdownExpanded = false
                                            onConversationSelect(conversation.id)
                                        },
                                        leadingIcon = if (conversation.id == currentConversationId) ({
                                            Icon(
                                                Icons.Default.SmartToy,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }) else null
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = onNewConversation,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New chat", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }
                    HorizontalDivider()
                }

                // Slot 1: messages list
                ChatMessageList(
                    messages = messages,
                    isLoading = isLoading,
                    behavior = messageListBehavior,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                    messageThumbnails = messageThumbnails,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    emptyContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Start a conversation with $agentName",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 56.dp)
                            )
                        }
                    },
                    overlayContent = { isAtBottom ->
                        if (messages.isNotEmpty() && !isAtBottom && onOpenInApp != null) {
                            FilledTonalButton(
                                onClick = onOpenInApp,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    Icons.Default.OpenInFull,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Open in app", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    replyActionForMessage = { message ->
                        if (!message.isStreaming && message.role == "assistant") {
                            { replyToMessage = message }
                        } else {
                            null
                        }
                    }
                )

                // Slot 2: divider + ChatInputBar (unified input chrome)
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    ChatInputBar(
                        isLoading = isLoading,
                        hasAttachment = hasAttachment,
                        pendingImages = pendingImages.map { it.second },
                        pendingFileName = pendingFileName,
                        replyToMessage = replyToMessage,
                        replyLabel = agentName,
                        onPickCamera = onPickCamera,
                        onPickGallery = onPickGallery,
                        onPickFile = onPickFile,
                        onTakeScreenshot = onTakeScreenshot,
                        onClearAttachment = onClearAttachment,
                        onCancelReply = { replyToMessage = null },
                        onStop = onStop,
                        onSend = {
                            val replyContext = replyToMessage
                                ?.let { "> ${it.content.take(200).replace("\n", " ")}\n\n" }
                                ?: ""
                            onSend(replyContext + inputText)
                            replyToMessage = null
                        },
                        disableScreenshotRipple = true,
                        sendEnabled = inputText.isNotBlank() || hasAttachment
                    ) {
                        val placeholder = when {
                            pendingImages.size > 1 -> "Ask about these images…"
                            pendingImages.size == 1 -> "Ask about this image…"
                            pendingFileName != null -> "Ask about this file…"
                            else -> "Message…"
                        }
                        TextField(
                            value = inputText,
                            onValueChange = onInputChange,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(onSend = {
                                val replyContext = replyToMessage
                                    ?.let { "> ${it.content.take(200).replace("\n", " ")}\n\n" }
                                    ?: ""
                                onSend(replyContext + inputText)
                                replyToMessage = null
                            }),
                            maxLines = 5,
                            enabled = !isLoading
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val unbounded = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            val headerP: Placeable = measurables[0].measure(unbounded)
            val footerP: Placeable = measurables[2].measure(unbounded)

            val minMsgPx = 100.dp.roundToPx()
            val maxMsgPx = animatedMaxMsgHeight.roundToPx()
            val remaining = if (constraints.maxHeight == Constraints.Infinity) {
                maxMsgPx
            } else {
                (constraints.maxHeight - headerP.height - footerP.height)
                    .coerceIn(minMsgPx, maxMsgPx)
            }
            val messagesP: Placeable = measurables[1].measure(
                constraints.copy(minHeight = remaining, maxHeight = remaining)
            )

            val totalHeight = headerP.height + messagesP.height + footerP.height
            layout(constraints.maxWidth, totalHeight) {
                headerP.placeRelative(0, 0)
                messagesP.placeRelative(0, headerP.height)
                footerP.placeRelative(0, headerP.height + messagesP.height)
            }
        }
    }
}

@Composable
fun BubbleContent(isLoading: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF6750A4), Color(0xFF9C27B0))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
            )
        } else {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = "AI Chat",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}
