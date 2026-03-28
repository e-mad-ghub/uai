package com.mad.screenagent.feature.bubble

import com.mad.screenagent.shared.chatui.ChatInputBar
import com.mad.screenagent.shared.chatui.ChatMessageList
import com.mad.screenagent.shared.chatui.OverlayTextToolbar
import com.mad.screenagent.shared.chatui.buildQuotedReplyContext
import com.mad.screenagent.shared.chatui.rememberChatMessageListBehavior
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.PopupProperties
import com.mad.screenagent.design.components.BrandMarkIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mad.screenagent.R
import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.db.MessageEntity
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.canHandleImageRequests
import com.mad.screenagent.design.components.ProductHintPill
import com.mad.screenagent.design.components.ProductInlineHintStrip
import com.mad.screenagent.design.components.ProductInputHintStrip

@Composable
fun ChatPanel(
    messages: List<MessageEntity>,
    conversationKey: String?,
    inputText: String,
    isLoading: Boolean,
    agentName: String,
    agentTokenInfo: String? = null,
    agentTokenInfoColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    selectedAgentId: String?,
    hasSelectedAgent: Boolean,
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
    onMinimize: () -> Unit,
    onOpenInApp: (() -> Unit)? = null,
    onAgentSelect: (AgentConfig) -> Unit,
    onConversationSelect: (String?) -> Unit,
    onNewConversation: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    onPickFile: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onClearAttachment: () -> Unit,
    onRemoveImage: ((Int) -> Unit)? = null,
    showMiniChatMinimizeTip: Boolean = false,
    onDismissMiniChatMinimizeTip: (() -> Unit)? = null,
    screenshotHintMessage: String? = null,
    errorHintMessage: String? = null,
    onDismissError: (() -> Unit)? = null,
    loadingStatusText: String? = null,
    // Bug Fix 1: Increment this to force-scroll to the latest message (e.g. after a quick action).
    scrollToBottomTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val messageListBehavior = rememberChatMessageListBehavior(
        messages = messages,
        conversationKey = conversationKey,
        scrollToBottomTrigger = scrollToBottomTrigger
    )
    val maxMsgHeight = (configuration.screenHeightDp.dp * 0.64f).coerceIn(280.dp, 560.dp)
    var renderedScreenshotHint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(screenshotHintMessage) {
        if (screenshotHintMessage != null) {
            renderedScreenshotHint = screenshotHintMessage
        }
    }

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
        // Custom TextToolbar: TYPE_APPLICATION_OVERLAY windows can't use system ActionMode,
        // so we provide our own toolbar rendered as a DropdownMenu inside the Compose tree.
        val textToolbar = remember { OverlayTextToolbar() }
        CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
        Box(modifier = Modifier.fillMaxWidth()) {

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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.widthIn(max = 172.dp)) {
                            TextButton(
                                onClick = {
                                    if (hasExistingConversations) {
                                        conversationDropdownExpanded = true
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = currentConversationTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
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
                                onDismissRequest = { conversationDropdownExpanded = false },
                                properties = PopupProperties(focusable = false)
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
                                        trailingIcon = if (conversation.id == currentConversationId) ({
                                            Text("✓", color = MaterialTheme.colorScheme.primary)
                                        }) else null
                                    )
                                }
                            }
                        }

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

                        Box {
                            Column(horizontalAlignment = Alignment.End) {
                            TextButton(
                                onClick = {
                                    if (agents.isNotEmpty()) {
                                        agentDropdownExpanded = true
                                    }
                                }
                            ) {
                                Text(
                                    text = agentName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 128.dp)
                                )
                                if (agents.isNotEmpty()) {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Select assistant",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (!agentTokenInfo.isNullOrBlank()) {
                                Text(
                                    text = agentTokenInfo,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (agentTokenInfoColor == androidx.compose.ui.graphics.Color.Unspecified)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else agentTokenInfoColor,
                                    modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                                )
                            }
                            } // end Column
                            DropdownMenu(
                                expanded = agentDropdownExpanded,
                                onDismissRequest = { agentDropdownExpanded = false },
                                properties = PopupProperties(focusable = false)
                            ) {
                                agents.forEach { agent ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(agent.name)
                                                    if (agent.canHandleImageRequests()) {
                                                        Icon(
                                                            Icons.Default.Image,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(14.dp),
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                                Text(
                                                    "${agent.provider.displayName} · ${agent.model}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            agentDropdownExpanded = false
                                            onAgentSelect(agent)
                                        },
                                        trailingIcon = if (agent.id == selectedAgentId) ({
                                            Text("✓", color = MaterialTheme.colorScheme.primary)
                                        }) else null
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }

                // Slot 1: messages list
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    if (showMiniChatMinimizeTip && onDismissMiniChatMinimizeTip != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            ProductHintPill(
                                message = stringResource(R.string.mini_chat_panel_hint),
                                onDismiss = onDismissMiniChatMinimizeTip
                            )
                        }
                    }
                    ChatMessageList(
                        messages = messages,
                        isLoading = isLoading,
                        loadingStatusText = loadingStatusText,
                        behavior = messageListBehavior,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        messageThumbnails = messageThumbnails,
                        onBackgroundDoubleTap = onMinimize,
                        onMessageDoubleTap = onMinimize,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        emptyContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (hasSelectedAgent) {
                                        "Start a conversation with $agentName"
                                    } else {
                                        "Choose an assistant to start this chat"
                                    },
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
                            androidx.compose.animation.AnimatedVisibility(
                                visible = screenshotHintMessage != null,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                enter = fadeIn(animationSpec = tween(180)) +
                                    slideInVertically(
                                        initialOffsetY = { it / 2 },
                                        animationSpec = tween(220)
                                    ),
                                exit = fadeOut(animationSpec = tween(140)) +
                                    slideOutVertically(
                                        targetOffsetY = { it / 2 },
                                        animationSpec = tween(180)
                                    )
                            ) {
                                val hintMessage = renderedScreenshotHint
                                if (hintMessage != null) {
                                    ProductInputHintStrip(message = hintMessage)
                                }
                            }
                        },
                        replyActionForMessage = { message ->
                            if (!message.isStreaming) {
                                { replyToMessage = message }
                            } else {
                                null
                            }
                        }
                    )
                }

                // Slot 2: error strip (optional) + divider + ChatInputBar (unified input chrome)
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = errorHintMessage != null,
                        enter = fadeIn(animationSpec = tween(180)) +
                            expandVertically(animationSpec = tween(220)),
                        exit = fadeOut(animationSpec = tween(140)) +
                            shrinkVertically(animationSpec = tween(180))
                    ) {
                        val msg = errorHintMessage
                        if (msg != null) {
                            ProductInlineHintStrip(
                                message = msg,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                onDismiss = onDismissError
                            )
                        }
                    }
                    HorizontalDivider()
                    ChatInputBar(
                        isLoading = isLoading,
                        hasAttachment = hasAttachment,
                        pendingImages = pendingImages.map { it.second },
                        pendingFileName = pendingFileName,
                        replyToMessage = replyToMessage,
                        replyLabel = when {
                            replyToMessage?.role == "user" -> "You"
                            !replyToMessage?.agentName.isNullOrBlank() -> replyToMessage?.agentName.orEmpty()
                            else -> agentName
                        },
                        onPickCamera = onPickCamera,
                        onPickGallery = onPickGallery,
                        onPickFile = onPickFile,
                        onTakeScreenshot = onTakeScreenshot,
                        onClearAttachment = onClearAttachment,
                        onRemoveImage = onRemoveImage,
                        onCancelReply = { replyToMessage = null },
                        onStop = onStop,
                        onSend = {
                            val replyContext = replyToMessage?.let { buildQuotedReplyContext(it) }.orEmpty()
                            onSend(replyContext + inputText)
                            replyToMessage = null
                        },
                        disableScreenshotRipple = true,
                        dropdownMenuFocusable = false,
                        sendEnabled = (inputText.isNotBlank() || hasAttachment) && hasSelectedAgent
                    ) {
                        val placeholder = when {
                            pendingImages.size > 1 -> "Ask about these images…"
                            pendingImages.size == 1 -> "Ask about this image…"
                            pendingFileName != null -> "Ask about this file…"
                            else -> "Message…"
                        }
                        val clipboardManager = LocalClipboardManager.current
                        var tfv by remember { mutableStateOf(TextFieldValue(inputText)) }
                        LaunchedEffect(inputText) {
                            if (tfv.text != inputText) tfv = TextFieldValue(inputText, TextRange(inputText.length))
                        }
                        TextField(
                            value = tfv,
                            onValueChange = { new -> tfv = new; onInputChange(new.text) },
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
                                val replyContext = replyToMessage?.let { buildQuotedReplyContext(it) }.orEmpty()
                                onSend(replyContext + inputText)
                                replyToMessage = null
                            }),
                            maxLines = 5,
                            enabled = !isLoading
                        )
                        val clipText = clipboardManager.getText()?.text?.takeIf { it.isNotBlank() }
                        if (clipText != null) {
                            IconButton(
                                onClick = {
                                    val newText = inputText + clipText
                                    tfv = TextFieldValue(newText, TextRange(newText.length))
                                    onInputChange(newText)
                                },
                                modifier = Modifier.size(36.dp),
                                enabled = !isLoading
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val unbounded = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            val headerP: Placeable = measurables[0].measure(unbounded)
            val footerP: Placeable = measurables[2].measure(unbounded)

            val minMsgPx = 100.dp.roundToPx()
            val maxMsgPx = maxMsgHeight.roundToPx()
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

        // Text-selection toolbar rendered as a DropdownMenu.
        // Anchored to the top of the panel (offset to sit just below the header).
        DropdownMenu(
            expanded = textToolbar.showMenu,
            onDismissRequest = { textToolbar.hide() },
            properties = PopupProperties(focusable = false)
        ) {
            textToolbar.copyAction?.let { copy ->
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = { copy(); textToolbar.hide() }
                )
            }
            textToolbar.cutAction?.let { cut ->
                DropdownMenuItem(
                    text = { Text("Cut") },
                    leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                    onClick = { cut(); textToolbar.hide() }
                )
            }
            textToolbar.pasteAction?.let { paste ->
                DropdownMenuItem(
                    text = { Text("Paste") },
                    leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                    onClick = { paste(); textToolbar.hide() }
                )
            }
            textToolbar.selectAllAction?.let { selectAll ->
                DropdownMenuItem(
                    text = { Text("Select all") },
                    leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                    onClick = { selectAll(); textToolbar.hide() }
                )
            }
        }

        } // Box
        } // CompositionLocalProvider
    }
}

@Composable
fun BubbleContent(isLoading: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        BrandMarkIcon(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                strokeWidth = 2.5.dp
            )
        }
    }
}
