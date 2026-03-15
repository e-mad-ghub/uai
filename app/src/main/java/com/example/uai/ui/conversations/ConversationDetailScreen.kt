package com.example.uai.ui.conversations

import android.app.Activity
import android.content.Intent
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.R
import com.example.uai.UaiApplication
import com.example.uai.ai.FileAttachmentContext
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.service.FloatingBubbleService
import com.example.uai.ui.components.ProductEmptyStateCard
import com.example.uai.ui.components.ProductInlineHintStrip
import com.example.uai.ui.components.ProductPill
import com.example.uai.ui.components.ProductTopBarTitle
import com.example.uai.ui.chat.FileAttachmentImportResult
import com.example.uai.ui.chat.ChatInputBar
import com.example.uai.ui.chat.ChatMessageList
import com.example.uai.ui.chat.MessageBubble
import com.example.uai.ui.chat.buildQuotedReplyContext
import com.example.uai.ui.chat.importFileAttachment
import com.example.uai.ui.chat.persistImageAttachment
import com.example.uai.ui.chat.rememberCameraPermissionRequester
import com.example.uai.ui.chat.rememberChatMessageListBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.widget.Toast

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    viewModel: ConversationDetailViewModel,
    openDrawer: () -> Unit,
    onOpenAssistants: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContainer = (context.applicationContext as UaiApplication).container
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val onlineSearchStatus by viewModel.onlineSearchStatus.collectAsStateWithLifecycle()
    val activeAgent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val bubbleEnabled by appContainer.agentRepository
        .bubbleEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val miniChatEntryTipDismissed by appContainer.preferences
        .miniChatEntryTipDismissedFlow
        .collectAsStateWithLifecycle(initialValue = false)

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var agentMenuExpanded by remember { mutableStateOf(false) }
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Switch Model",
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                agentMenuExpanded = true
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.assistantRepairEvent.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Image attachment
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImageBase64 by remember { mutableStateOf<String?>(null) }
    var pendingImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var attachmentPreparationLabel by remember { mutableStateOf<String?>(null) }

    // File attachment (text or PDF)
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var pendingFileText by remember { mutableStateOf<String?>(null) }
    val isPreparingAttachment = attachmentPreparationLabel != null

    fun clearAttachments() {
        pendingImageUri = null
        pendingImageBase64 = null
        pendingImageBitmap = null
        pendingFileName = null
        pendingFileText = null
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFileName = null; pendingFileText = null
        pendingImageUri = uri
        pendingImageBase64 = null
        pendingImageBitmap = null
        if (uri != null) {
            attachmentPreparationLabel = "Preparing image…"
            scope.launch {
                val (base64, bmp) = withContext(Dispatchers.IO) { encodeImageForApi(context, uri) }
                pendingImageBase64 = base64
                pendingImageBitmap = bmp
                if (base64 == null || bmp == null) {
                    pendingImageUri = null
                    snackbarHostState.showSnackbar("Could not prepare that image.")
                }
                attachmentPreparationLabel = null
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            pendingFileName = null; pendingFileText = null
            pendingImageUri = null
            attachmentPreparationLabel = "Preparing photo…"
            scope.launch(Dispatchers.IO) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    pendingImageBase64 = base64
                    pendingImageBitmap = bitmap.asImageBitmap()
                    attachmentPreparationLabel = null
                }
            }
        }
    }

    val requestCameraPermission = rememberCameraPermissionRequester(
        onGranted = { cameraLauncher.launch(null) },
        onDenied = {
            scope.launch {
                snackbarHostState.showSnackbar("Camera permission is required to take a photo.")
            }
        }
    )

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImageUri = null; pendingImageBase64 = null; pendingImageBitmap = null
        scope.launch {
            attachmentPreparationLabel = "Importing file…"
            when (val result = importFileAttachment(context, uri)) {
                is FileAttachmentImportResult.Success -> {
                    pendingFileName = result.attachment.displayName
                    pendingFileText = result.attachment.extractedText
                }
                is FileAttachmentImportResult.Unsupported -> {
                    snackbarHostState.showSnackbar(result.message)
                }
                is FileAttachmentImportResult.Failure -> {
                    snackbarHostState.showSnackbar(result.message)
                }
            }
            attachmentPreparationLabel = null
        }
    }

    val hasAttachment = pendingImageBitmap != null || pendingFileName != null
    val canTransferToMiniChat = bubbleEnabled &&
        Settings.canDrawOverlays(context)
    val showMiniChatEntryTip = bubbleEnabled && !miniChatEntryTipDismissed

    fun openOverlaySettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun minimizeIntoMiniChat() {
        val conversationId = conversation?.id
        if (conversationId != null) {
            FloatingBubbleService.openConversation(context, conversationId)
        } else {
            FloatingBubbleService.openDraftConversation(
                context = context,
                assistantId = activeAgent?.id
            )
        }
        generateSequence(context) { current ->
            (current as? ContextWrapper)?.baseContext
        }
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.moveTaskToBack(true)
    }

    fun doSend() {
        if (isPreparingAttachment) return
        val image = pendingImageBase64
        val existingImageUri = pendingImageUri?.toString()
        val attachedFile = pendingFileText?.let {
            FileAttachmentContext(
                displayName = pendingFileName ?: "file",
                extractedText = it
            )
        }
        val replyContext = replyToMessage?.let { buildQuotedReplyContext(it) }.orEmpty()
        val fullText = replyContext + inputText
        val titleHint = inputText.trim().ifBlank { pendingFileName.orEmpty() }
        val persistedImageUri = image?.let { persistImageAttachment(context, it) }
        clearAttachments()
        replyToMessage = null
        viewModel.sendMessage(
            text = fullText,
            imageBase64 = image,
            imageUri = persistedImageUri ?: existingImageUri,
            titleHint = titleHint,
            attachedFile = attachedFile
        )
    }

    val messageListBehavior = rememberChatMessageListBehavior(messages)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    ProductTopBarTitle(
                        title = conversation?.title ?: "New Chat",
                        subtitle = activeAgent?.let { "${it.name} · ${it.provider.displayName}" }
                            ?: stringResource(R.string.chat_choose_assistant_subtitle)
                    )
                },
                navigationIcon = { IconButton(onClick = openDrawer) { Icon(Icons.Default.Menu, "Menu") } },
                actions = {
                    if (agents.isNotEmpty()) {
                        Box {
                            TextButton(onClick = { agentMenuExpanded = true }) {
                                Text(
                                    activeAgent?.name ?: "Select assistant",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(Icons.Default.ArrowDropDown, "Switch agent", tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(expanded = agentMenuExpanded, onDismissRequest = { agentMenuExpanded = false }) {
                                agents.forEach { agent ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(agent.name)
                                                    if (agent.canHandleImageRequests()) Icon(Icons.Default.Image, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                                }
                                                Text("${agent.provider.displayName} · ${agent.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        },
                                        onClick = { viewModel.setActiveAgent(agent); agentMenuExpanded = false },
                                        trailingIcon = if (agent.id == activeAgent?.id) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showMiniChatEntryTip) {
                ProductInlineHintStrip(
                    message = stringResource(
                        if (canTransferToMiniChat) {
                            R.string.mini_chat_entry_hint_ready
                        } else {
                            R.string.mini_chat_entry_hint_permission
                        }
                    ),
                    actionLabel = if (canTransferToMiniChat) null else stringResource(R.string.action_allow_display_over_other_apps),
                    onAction = if (canTransferToMiniChat) null else ::openOverlaySettings,
                    onDismiss = {
                        scope.launch {
                            appContainer.preferences.setMiniChatEntryTipDismissed(true)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (messages.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            if (canTransferToMiniChat) {
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onDoubleClick = { minimizeIntoMiniChat() }
                                )
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (activeAgent == null) {
                        AssistantSetupPromptCard(
                            hasAnyAssistants = agents.isNotEmpty(),
                            onOpenAssistants = onOpenAssistants,
                            modifier = Modifier.padding(24.dp)
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(32.dp)) {
                            Text("What can I help you with?", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                            Text("Type a message below to get started.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProductPill(label = activeAgent!!.name, emphasized = true)
                                ProductPill(label = activeAgent!!.provider.displayName)
                            }
                        }
                    }
                }
            } else {
                ChatMessageList(
                    messages = messages,
                    isLoading = isLoading,
                    loadingStatusText = onlineSearchStatus,
                    behavior = messageListBehavior,
                    modifier = Modifier.weight(1f),
                    onBackgroundDoubleTap = if (canTransferToMiniChat) ({ minimizeIntoMiniChat() }) else null,
                    onMessageDoubleTap = if (canTransferToMiniChat) ({ minimizeIntoMiniChat() }) else null,
                    replyActionForMessage = { msg ->
                        if (msg.role != "user" && !msg.isStreaming) ({ replyToMessage = msg }) else null
                    }
                )
            }

            if (activeAgent == null) {
                val noAssistantMessage = when {
                    agents.isEmpty() -> "Set up an assistant to start chatting."
                    conversation != null || messages.isNotEmpty() -> "Choose an assistant for this conversation."
                    else -> "Choose which assistant this new chat should use."
                }
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            noAssistantMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = onOpenAssistants) {
                            Text("Open Assistants")
                        }
                    }
                }
            } else {
                ChatInputBar(
                    isLoading = isLoading,
                    hasAttachment = hasAttachment,
                    isPreparingAttachment = isPreparingAttachment,
                    preparingAttachmentLabel = attachmentPreparationLabel ?: "Preparing attachment…",
                    pendingImages = if (pendingImageBitmap != null) listOf(pendingImageBitmap) else emptyList(),
                    pendingFileName = pendingFileName,
                    replyToMessage = replyToMessage,
                    replyLabel = replyToMessage?.agentName ?: activeAgent?.name ?: "Assistant",
                    onPickCamera = requestCameraPermission,
                    onPickGallery = { imagePicker.launch("image/*") },
                    onPickFile = { filePicker.launch("*/*") },
                    onClearAttachment = { clearAttachments() },
                    onCancelReply = { replyToMessage = null },
                    onStop = { viewModel.stopResponse() },
                    onSend = { doSend() },
                    sendEnabled = (inputText.isNotBlank() || hasAttachment) &&
                        activeAgent != null &&
                        !isPreparingAttachment,
                    modifier = Modifier.imePadding()
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = viewModel::onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when {
                                    pendingImageBitmap != null -> "Ask about this image…"
                                    pendingFileName != null -> "Ask about this file…"
                                    else -> "Message…"
                                },
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
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { doSend() }),
                        maxLines = 5,
                        enabled = !isLoading
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantSetupPromptCard(
    hasAnyAssistants: Boolean,
    onOpenAssistants: () -> Unit,
    modifier: Modifier = Modifier
) {
    ProductEmptyStateCard(
        title = if (hasAnyAssistants) {
            "Choose an assistant for this chat"
        } else {
            "Create your first assistant"
        },
        body = if (hasAnyAssistants) {
            "You already have assistants configured. Choose one for this chat from the top bar, or open Assistants to set a default for future chats."
        } else {
            "Add one connection, choose a role, and ScreenAgent will be ready for new chats."
        },
        actionLabel = "Open Assistants",
        onAction = onOpenAssistants,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        titleAlign = TextAlign.Center,
        bodyAlign = TextAlign.Center
    )
}

private fun encodeImageForApi(context: android.content.Context, uri: Uri): Pair<String?, ImageBitmap?> {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1024)
        val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
        val bmp: Bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) } ?: return null to null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to bmp.asImageBitmap()
    } catch (_: Exception) { null to null }
}
