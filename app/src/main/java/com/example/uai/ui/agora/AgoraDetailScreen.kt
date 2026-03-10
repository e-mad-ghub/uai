package com.example.uai.ui.agora

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.data.db.MessageEntity
import com.example.uai.service.FloatingBubbleService
import com.example.uai.ui.chat.ChatInputBar
import com.example.uai.ui.chat.MessageBubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // File attachment (text or PDF)
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var pendingFileText by remember { mutableStateOf<String?>(null) }
    var pendingDocumentBase64 by remember { mutableStateOf<String?>(null) }

    // Room settings bottom sheet
    var showSettings by remember { mutableStateOf(false) }

    // Error events
    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { message ->
            val job = launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = "OK",
                    duration = SnackbarDuration.Indefinite
                )
            }
            delay(5_000)
            job.cancel()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFileName = null; pendingFileText = null; pendingDocumentBase64 = null
        pendingImageUri = uri
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            pendingFileName = null; pendingFileText = null; pendingDocumentBase64 = null
            pendingImageUri = null
            scope.launch(Dispatchers.IO) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    pendingImageBase64 = base64
                    pendingImageBitmap = bitmap.asImageBitmap()
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImageUri = null; pendingImageBase64 = null; pendingImageBitmap = null
        scope.launch {
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val name = uri.lastPathSegment ?: "file"
            when {
                mimeType.startsWith("text/") -> {
                    val text = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull()
                    }
                    if (text != null) { pendingFileName = name; pendingFileText = text }
                    else snackbarHostState.showSnackbar("Could not read file.")
                }
                mimeType == "application/pdf" -> {
                    val base64 = withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.openInputStream(uri)?.use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } }.getOrNull()
                    }
                    if (base64 != null) { pendingFileName = name; pendingDocumentBase64 = base64 }
                    else snackbarHostState.showSnackbar("Could not read PDF.")
                }
                else -> snackbarHostState.showSnackbar("Unsupported file type. Supported: images, text files, PDF.")
            }
        }
    }

    // Capture mode: app backgrounds itself, bubble becomes a camera button, tapping it captures
    val agoraId = viewModel.conversationId
    LaunchedEffect(Unit) {
        FloatingBubbleService.screenshotResult.collect { (convId, base64, bitmap) ->
            if (convId == agoraId) {
                pendingImageUri = null; pendingImageBase64 = null; pendingImageBitmap = null
                pendingFileName = null; pendingFileText = null; pendingDocumentBase64 = null
                pendingImageBase64 = base64
                pendingImageBitmap = bitmap
            }
        }
    }

    fun doScreenshot() {
        if (!Settings.canDrawOverlays(context)) {
            scope.launch { snackbarHostState.showSnackbar("Overlay permission required. Enable it in Settings > Apps > UAI.") }
            return
        }
        FloatingBubbleService.enterCaptureMode(context, agoraId, true)
        (context as? Activity)?.moveTaskToBack(true)
    }

    // Encode gallery image when URI changes (camera images encoded directly)
    LaunchedEffect(pendingImageUri) {
        val uri = pendingImageUri ?: return@LaunchedEffect
        val (base64, bmp) = withContext(Dispatchers.IO) { encodeImageForApi(context, uri) }
        pendingImageBase64 = base64
        pendingImageBitmap = bmp
    }

    fun clearAttachment() {
        pendingImageUri = null
        pendingImageBase64 = null
        pendingImageBitmap = null
        pendingFileName = null
        pendingFileText = null
        pendingDocumentBase64 = null
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        FloatingBubbleService.screenshotResult.resetReplayCache()
    }

    fun doSend() {
        val fileContext = pendingFileText?.let { "```\n$it\n```\n\n" } ?: ""
        val replyContext = replyToMessage?.let { "> ${it.content.take(200).replace("\n", " ")}\n\n" } ?: ""
        val fullText = replyContext + fileContext + tfv.text
        viewModel.sendMessage(fullText, pendingImageBase64, pendingImageUri?.toString(), replyToMessage, pendingDocumentBase64)
        replyToMessage = null
        clearAttachment()
    }

    // Auto-scroll
    val listState = rememberLazyListState()
    val isAtBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0) autoScrollEnabled = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(isAtBottom) { if (isAtBottom) autoScrollEnabled = true }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) { autoScrollEnabled = true; listState.animateScrollToItem(messages.size - 1) }
    }
    val lastMessageContent = messages.lastOrNull()?.content
    LaunchedEffect(lastMessageContent) {
        if (messages.lastOrNull()?.isStreaming == true && autoScrollEnabled) listState.scroll { scrollBy(100_000f) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            conversation?.title ?: "Agora Room",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (participantNames.isNotEmpty()) {
                            Text(
                                participantNames.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Room settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (messages.isEmpty() && !isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            "Ask anything",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            if (participantNames.isEmpty())
                                "No agents in this room yet. Tap ⚙ to add agents."
                            else
                                "All agents reply by default.\nType @Name or tap a chip to address one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).nestedScroll(nestedScrollConnection),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onReply = if (msg.role == "assistant" && !msg.isStreaming && msg.agentName != null)
                                { { replyToMessage = msg } } else null
                        )
                    }
                    if (isLoading && messages.lastOrNull()?.isStreaming != true) {
                        item {
                            Row(Modifier.fillMaxWidth()) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
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
                                "Replying to ${replyToMessage!!.agentName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                replyToMessage!!.content.take(80).let {
                                    if (replyToMessage!!.content.length > 80) "$it…" else it
                                },
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
                pendingImages = if (pendingImageBitmap != null) listOf(pendingImageBitmap) else emptyList(),
                pendingFileName = pendingFileName,
                replyToMessage = null, // Rendered manually above to preserve Agora visual order
                onPickCamera = { cameraLauncher.launch(null) },
                onPickGallery = { imagePicker.launch("image/*") },
                onPickFile = { filePicker.launch("*/*") },
                onTakeScreenshot = ::doScreenshot,
                onClearAttachment = { clearAttachment() },
                onStop = { viewModel.stopResponse() },
                onSend = { doSend() },
                sendEnabled = tfv.text.isNotBlank() || hasAttachment,
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
                    Text("Room settings", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Room name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text(
                        "Agents in this room",
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

                    if (selectedIds.isEmpty()) {
                        Text(
                            "No agents selected — messages will show a warning until agents are added.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.updateRoom(editName, selectedIds)
                            showSettings = false
                        },
                        enabled = editName.isNotBlank(),
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
