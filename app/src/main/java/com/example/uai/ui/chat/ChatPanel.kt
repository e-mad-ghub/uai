package com.example.uai.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.model.AgentConfig

@Composable
fun ChatPanel(
    messages: List<MessageEntity>,
    inputText: String,
    isLoading: Boolean,
    agentName: String,
    agents: List<AgentConfig>,
    pendingImageBitmap: ImageBitmap?,
    pendingFileName: String?,
    hasAttachment: Boolean,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onAgentSelect: (AgentConfig) -> Unit,
    onNewConversation: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    onPickFile: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onClearAttachment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val lastMessageContent = messages.lastOrNull()?.content
    LaunchedEffect(messages.size, lastMessageContent) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    var agentDropdownExpanded by remember { mutableStateOf(false) }
    var attachMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        // Custom layout: header + divider fixed at top, input + divider fixed at bottom,
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
                        // New chat button — right of agent dropdown
                        FilledTonalButton(
                            onClick = onNewConversation,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Start a conversation with $agentName",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                }

                // Slot 2: attachment preview + bottom divider + input row
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Attachment preview strip
                    if (hasAttachment) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (pendingImageBitmap != null) {
                                Image(
                                    bitmap = pendingImageBitmap,
                                    contentDescription = "Attached image",
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else if (pendingFileName != null) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.AttachFile,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            pendingFileName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = onClearAttachment,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    "Clear attachment",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // "+" attachment picker button
                        Box {
                            IconButton(
                                onClick = { attachMenuExpanded = true },
                                enabled = !isLoading,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    "Attach",
                                    tint = if (hasAttachment) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = attachMenuExpanded,
                                onDismissRequest = { attachMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Camera") },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                    onClick = { onPickCamera(); attachMenuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Gallery") },
                                    leadingIcon = { Icon(Icons.Default.Image, null) },
                                    onClick = { onPickGallery(); attachMenuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("File") },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                                    onClick = { onPickFile(); attachMenuExpanded = false }
                                )
                            }
                        }
                        // Screenshot button (floating chat exclusive)
                        IconButton(
                            onClick = onTakeScreenshot,
                            enabled = !isLoading,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Screenshot,
                                "Screenshot",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = onInputChange,
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    when {
                                        pendingImageBitmap != null -> "Ask about this image…"
                                        pendingFileName != null    -> "Ask about this file…"
                                        else                       -> "Message…"
                                    }
                                )
                            },
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(onSend = { onSend(inputText) }),
                            maxLines = 5,
                            enabled = !isLoading
                        )
                        Spacer(Modifier.width(6.dp))
                        if (isLoading) {
                            FilledIconButton(
                                onClick = onStop,
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Default.Stop, "Stop", modifier = Modifier.size(20.dp))
                            }
                        } else {
                            FilledIconButton(
                                onClick = { onSend(inputText) },
                                enabled = inputText.isNotBlank() || hasAttachment,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    "Send",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            // Measure header and footer at their natural heights (unbounded)
            val unbounded = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
            val headerP: Placeable = measurables[0].measure(unbounded)
            val footerP: Placeable = measurables[2].measure(unbounded)

            // Give messages whatever height remains; clamp between 100dp and 380dp
            val minMsgPx = 100.dp.roundToPx()
            val maxMsgPx = 380.dp.roundToPx()
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
