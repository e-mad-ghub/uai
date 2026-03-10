package com.example.uai.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.uai.data.db.MessageEntity

/**
 * Unified chat input bar used by ConversationDetailScreen, AgoraDetailScreen, and ChatPanel.
 *
 * Renders (top to bottom):
 *   1. Reply preview bar   — when [replyToMessage] is non-null
 *   2. Attachment strip    — when [hasAttachment] is true
 *   3. Rounded surfaceVariant input row  — always
 *      └ [+] dropdown · [textFieldContent slot] · [Stop | Send] button
 *
 * The [textFieldContent] slot is a [RowScope] lambda so callers can supply a String-based
 * or TextFieldValue-based TextField with the correct weight and keyboard options.
 *
 * [onTakeScreenshot] is optional; when null the screenshot icon button is hidden.
 *   — Currently it is only enabled in the overlay mini-chat.
 * [modifier] — callers add .imePadding() when inside a Scaffold (not needed for the overlay service).
 */
@Composable
fun ChatInputBar(
    isLoading: Boolean,
    hasAttachment: Boolean,
    pendingImages: List<ImageBitmap?> = emptyList(),
    pendingFileName: String? = null,
    replyToMessage: MessageEntity? = null,
    replyLabel: String = "Assistant",
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
    onTakeScreenshot: (() -> Unit)? = null,
    onClearAttachment: () -> Unit,
    onCancelReply: () -> Unit = {},
    onStop: () -> Unit,
    onSend: () -> Unit,
    disableScreenshotRipple: Boolean = false,
    sendEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    textFieldContent: @Composable RowScope.() -> Unit
) {
    var attachMenuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {

        // --- Reply preview ---
        replyToMessage?.let { reply ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Replying to $replyLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            reply.content.take(80).let { if (reply.content.length > 80) "$it…" else it },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onCancelReply, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // --- Attachment strip ---
        if (hasAttachment) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (pendingImages.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (bmp in pendingImages) {
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                } else if (pendingFileName != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                pendingFileName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                IconButton(onClick = onClearAttachment) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear attachment",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- Input surface ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // [+] attachment dropdown
                Box {
                    IconButton(onClick = { attachMenuExpanded = true }, enabled = !isLoading) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Attach",
                            tint = if (hasAttachment) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = { Text("Photo") },
                            leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = { onPickGallery(); attachMenuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Document (text / PDF)") },
                            leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                            onClick = { onPickFile(); attachMenuExpanded = false }
                        )
                    }
                }

                // Screenshot icon button — visible outside "+" dropdown when available
                if (onTakeScreenshot != null) {
                    if (disableScreenshotRipple) {
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(
                                    enabled = !isLoading,
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = onTakeScreenshot
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Screenshot,
                                contentDescription = "Screenshot",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        IconButton(onClick = onTakeScreenshot, enabled = !isLoading) {
                            Icon(
                                Icons.Default.Screenshot,
                                contentDescription = "Screenshot",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Caller-supplied TextField
                textFieldContent()

                // Stop / Send button
                if (isLoading) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.StopCircle, contentDescription = "Stop")
                    }
                } else {
                    FilledIconButton(onClick = onSend, enabled = sendEnabled) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
