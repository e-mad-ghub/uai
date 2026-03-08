package com.example.uai.ui.conversations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.ui.chat.MessageBubble

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    viewModel: ConversationDetailViewModel,
    openDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val activeAgent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()

    var agentMenuExpanded by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // True when the last item is already visible
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    // Auto-scroll flag: disabled when user scrolls up, re-enabled when they reach the bottom
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Detect upward user scroll — disable auto-scroll
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) {
                    autoScrollEnabled = false
                }
                return Offset.Zero
            }
        }
    }

    // Re-enable auto-scroll when user scrolls back to the bottom
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) autoScrollEnabled = true
    }

    // Animate to bottom when a brand-new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            autoScrollEnabled = true
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Follow streaming content without animation (avoids jitter on every token)
    val lastMessageContent = messages.lastOrNull()?.content
    LaunchedEffect(lastMessageContent) {
        if (messages.lastOrNull()?.isStreaming == true && autoScrollEnabled) {
            listState.scroll { scrollBy(100_000f) }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(conversation?.title ?: "New Chat") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, "Menu")
                    }
                },
                actions = {
                    if (activeAgent != null) {
                        Box {
                            TextButton(onClick = { agentMenuExpanded = true }) {
                                Text(
                                    activeAgent!!.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Switch agent",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            DropdownMenu(
                                expanded = agentMenuExpanded,
                                onDismissRequest = { agentMenuExpanded = false }
                            ) {
                                agents.forEach { agent ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(agent.name)
                                                Text(
                                                    "${agent.provider.displayName} · ${agent.model}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.setActiveAgent(agent)
                                            agentMenuExpanded = false
                                        },
                                        trailingIcon = if (agent.id == activeAgent?.id) {
                                            { Text("✓", color = MaterialTheme.colorScheme.primary) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Message list or welcome empty state
            if (messages.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "What can I help you with?",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Type a message below to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (activeAgent != null) {
                            Spacer(Modifier.height(4.dp))
                            AssistChip(
                                onClick = { agentMenuExpanded = true },
                                label = {
                                    Text(
                                        "${activeAgent!!.name} · ${activeAgent!!.provider.displayName}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).nestedScroll(nestedScrollConnection),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                    if (isLoading && messages.lastOrNull()?.isStreaming != true) {
                        item {
                            Row(Modifier.fillMaxWidth()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }

            // Input area
            if (activeAgent == null) {
                Text(
                    "No active agent. Go to Agents to set one up.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .imePadding(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = viewModel::onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "Message…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                            viewModel.sendMessage(inputText)
                        }),
                        maxLines = 5,
                        enabled = !isLoading
                    )
                    if (isLoading) {
                        FilledIconButton(
                            onClick = { viewModel.stopResponse() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.StopCircle, "Stop")
                        }
                    } else {
                        FilledIconButton(
                            onClick = { viewModel.sendMessage(inputText) },
                            enabled = inputText.isNotBlank() && activeAgent != null
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            }
        }
    }
}
