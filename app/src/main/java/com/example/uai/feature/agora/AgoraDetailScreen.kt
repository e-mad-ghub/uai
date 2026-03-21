package com.example.uai.feature.agora

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.R
import com.example.uai.shared.streaming.FileAttachmentContext
import com.example.uai.data.db.MessageEntity
import com.example.uai.design.components.ProductScreenIntro
import com.example.uai.design.components.ProductTopBarTitle
import com.example.uai.shared.attachment.FileAttachmentImportResult
import com.example.uai.shared.chatui.ChatInputBar
import com.example.uai.shared.chatui.ChatMessageList
import com.example.uai.shared.chatui.MessageBubble
import com.example.uai.shared.chatui.buildQuotedReplyContext
import com.example.uai.shared.chatui.buildReplyPreviewText
import com.example.uai.shared.attachment.importFileAttachment
import com.example.uai.shared.attachment.persistImageAttachment
import com.example.uai.shared.attachment.rememberCameraPermissionRequester
import com.example.uai.shared.chatui.rememberChatMessageListBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgoraDetailScreen(
    viewModel: AgoraDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val onlineSearchStatus by viewModel.onlineSearchStatus.collectAsStateWithLifecycle()
    val participantNames by viewModel.participantNames.collectAsStateWithLifecycle()
    val allAvailableAgents by viewModel.allAvailableAgents.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Local TextFieldValue for cursor-aware control (text content kept in sync with ViewModel).
    var tfv by remember { mutableStateOf(TextFieldValue("")) }

    // When ViewModel resets inputText (e.g. after send), mirror that to the local state.
    LaunchedEffect(inputText) {
        if (tfv.text != inputText) {
            tfv = TextFieldValue(inputText, TextRange(inputText.length))
        }
    }

    // Insert "@Name " at the current cursor position and place cursor right after.
    fun insertMention(name: String) {
        val cursor = tfv.selection.end
        val text = tfv.text
        val needsSpace = cursor > 0 && text.getOrNull(cursor - 1) != ' '
        val insertion = (if (needsSpace) " " else "") + "@$name "
        val newText = text.substring(0, cursor) + insertion + text.substring(cursor)
        val newCursor = cursor + insertion.length
        tfv = TextFieldValue(newText, TextRange(newCursor))
        viewModel.onInputChange(newText)
    }

    // Replace the partial "@query" before the cursor with the selected full "@Name ".
    fun selectSuggestion(name: String) {
        val cursor = tfv.selection.end
        val textBefore = tfv.text.substring(0, cursor)
        val atIndex = textBefore.lastIndexOf('@')
        if (atIndex < 0) return
        val beforeAt = tfv.text.substring(0, atIndex)
        val afterCursor = tfv.text.substring(cursor)
        val insertion = "@$name "
        val newText = beforeAt + insertion + afterCursor
        val newCursor = beforeAt.length + insertion.length
        tfv = TextFieldValue(newText, TextRange(newCursor))
        viewModel.onInputChange(newText)
    }

    // Derive the partial query being typed after the last '@' before the cursor.
    val mentionQuery: String? by remember(tfv) {
        derivedStateOf {
            val cursor = tfv.selection.end
            if (cursor == 0) return@derivedStateOf null
            val textBefore = tfv.text.substring(0, cursor)
            val atIndex = textBefore.lastIndexOf('@')
            if (atIndex < 0) return@derivedStateOf null
            val partial = textBefore.substring(atIndex + 1)
            if (' ' in partial || '@' in partial) null else partial
        }
    }

    val autocompleteSuggestions = remember(mentionQuery, participantNames) {
        val q = mentionQuery ?: return@remember emptyList<String>()
        participantNames.filter { it.startsWith(q, ignoreCase = true) }
    }

    // Reply state
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }

    // Image attachment
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImageBase64 by remember { mutableStateOf<String?>(null) }
    var pendingImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var attachmentPreparationLabel by remember { mutableStateOf<String?>(null) }

    // File attachment (text or PDF)
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var pendingFileText by remember { mutableStateOf<String?>(null) }
    val isPreparingAttachment = attachmentPreparationLabel != null

    // Room settings bottom sheet
    var showSettings by remember { mutableStateOf(false) }

    fun clearAttachment() {
        pendingImageUri = null
        pendingImageBase64 = null
        pendingImageBitmap = null
        pendingFileName = null
        pendingFileText = null
    }

    // Error events
    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long
            )
        }
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

    fun doSend() {
        if (isPreparingAttachment) return
        val image = pendingImageBase64
        val existingImageUri = pendingImageUri?.toString()
        val replyTarget = replyToMessage
        val attachedFile = pendingFileText?.let {
            FileAttachmentContext(
                displayName = pendingFileName ?: "file",
                extractedText = it
            )
        }
        val replyContext = replyTarget?.let { buildQuotedReplyContext(it) }.orEmpty()
        val fullText = replyContext + tfv.text
        val persistedImageUri = image?.let { persistImageAttachment(context, it) }
        clearAttachment()
        replyToMessage = null
        viewModel.sendMessage(
            text = fullText,
            imageBase64 = image,
            imageUri = persistedImageUri ?: existingImageUri,
            replyToMessage = replyTarget,
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
                        title = conversation?.title ?: stringResource(R.string.feature_room),
                        subtitle = stringResource(
                            R.string.room_participants_subtitle,
                            participantNames.size
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.room_settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (messages.isEmpty() && !isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ProductScreenIntro(
                        eyebrow = stringResource(R.string.feature_room),
                        title = "Ask anything",
                        body = if (participantNames.isEmpty()) {
                            "No assistants are active in this Agora room yet. Open room settings to add participants."
                        } else {
                            "All assistants reply by default. Type @Name or tap a chip when you want to direct the next turn."
                        },
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                val context = LocalContext.current
                ChatMessageList(
                    messages = messages,
                    isLoading = isLoading,
                    loadingStatusText = onlineSearchStatus,
                    behavior = messageListBehavior,
                    modifier = Modifier.weight(1f),
                    onMessageDoubleTap = {
                        Toast.makeText(context, "Minimize is not available for Agora rooms", Toast.LENGTH_SHORT).show()
                    },
                    onBackgroundDoubleTap = {
                        Toast.makeText(context, "Minimize is not available for Agora rooms", Toast.LENGTH_SHORT).show()
                    },
                    replyActionForMessage = { msg ->
                        if (!msg.isStreaming) {
                            { replyToMessage = msg }
                        } else {
                            null
                        }
                    }
                )
            }

            // Agora-specific reply preview bar (rendered above autocomplete/chips to preserve visual order)
            if (replyToMessage != null) {
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
                                "Replying to ${
                                    if (replyToMessage!!.role == "user") {
                                        "You"
                                    } else {
                                        replyToMessage!!.agentName ?: "Assistant"
                                    }
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                buildReplyPreviewText(replyToMessage!!, 80),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { replyToMessage = null }, modifier = Modifier.size(28.dp)) {
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

            // Autocomplete popup — appears when user is typing "@query"
            if (autocompleteSuggestions.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column {
                        autocompleteSuggestions.forEachIndexed { index, name ->
                            TextButton(
                                onClick = { selectSuggestion(name) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    "@$name",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (index < autocompleteSuggestions.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }

            // @Mention quick-insert chips — one per room participant
            if (participantNames.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    participantNames.forEach { name ->
                        val alreadyMentioned = tfv.text.contains("@$name", ignoreCase = true)
                        FilterChip(
                            selected = alreadyMentioned,
                            onClick = { if (!alreadyMentioned) insertMention(name) },
                            label = { Text("@$name", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Unified input bar (attachment strip + input surface with screenshot support)
            val hasAttachment = pendingImageBitmap != null || pendingFileName != null
            ChatInputBar(
                isLoading = isLoading,
                hasAttachment = hasAttachment,
                isPreparingAttachment = isPreparingAttachment,
                preparingAttachmentLabel = attachmentPreparationLabel ?: "Preparing attachment…",
                pendingImages = if (pendingImageBitmap != null) listOf(pendingImageBitmap) else emptyList(),
                pendingFileName = pendingFileName,
                replyToMessage = null, // Rendered manually above to preserve Agora visual order
                onPickCamera = requestCameraPermission,
                onPickGallery = { imagePicker.launch("image/*") },
                onPickFile = { filePicker.launch("*/*") },
                onClearAttachment = { clearAttachment() },
                onStop = { viewModel.stopResponse() },
                onSend = { doSend() },
                sendEnabled = (tfv.text.isNotBlank() || hasAttachment) && !isPreparingAttachment,
                modifier = Modifier.imePadding()
            ) {
                TextField(
                    value = tfv,
                    onValueChange = { newTfv ->
                        tfv = newTfv
                        viewModel.onInputChange(newTfv.text)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (pendingImageBitmap != null) "Ask about this image…"
                            else if (participantNames.size > 1) "Message all · @Name for one…"
                            else "Message…",
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
                    keyboardActions = KeyboardActions(onSend = { doSend() }),
                    maxLines = 5,
                    enabled = !isLoading
                )
            }
        }
    }

    // Room settings bottom sheet
    if (showSettings) {
        val conv = conversation
        if (conv != null) {
            var editName by remember(conv.title) { mutableStateOf(conv.title) }
            var selectedIds by remember(conv.agoraAgentIds) {
                mutableStateOf<Set<String>>(conv.parseAgoraAgentIds().toSet())
            }
            val roomSelectionMessage = when {
                allAvailableAgents.isEmpty() -> "No assistants are configured yet."
                selectedIds.isEmpty() -> "Choose at least one assistant to keep this room active."
                else -> null
            }

            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(stringResource(R.string.room_settings), style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(stringResource(R.string.room_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text(
                        stringResource(R.string.agents_in_room),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (allAvailableAgents.isEmpty()) {
                        Text(
                            "No agents configured. Go to Agents tab to add some.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        allAvailableAgents.forEach { agent ->
                            val checked = agent.id in selectedIds
                            Surface(
                                onClick = {
                                    selectedIds = selectedIds.toMutableSet().apply {
                                        if (checked) remove(agent.id) else add(agent.id)
                                    }
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = if (checked)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            selectedIds = selectedIds.toMutableSet().apply {
                                                if (checked) remove(agent.id) else add(agent.id)
                                            }
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(agent.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${agent.provider.displayName} · ${agent.model}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (roomSelectionMessage != null) {
                        Text(
                            roomSelectionMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedIds.isEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.updateRoom(editName, selectedIds)
                            showSettings = false
                        },
                        enabled = editName.isNotBlank() && selectedIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

private fun encodeImageForApi(context: android.content.Context, uri: Uri): Pair<String?, ImageBitmap?> {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1024)
        val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
        val bmp: Bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts2)
        } ?: return null to null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to bmp.asImageBitmap()
    } catch (_: Exception) { null to null }
}
