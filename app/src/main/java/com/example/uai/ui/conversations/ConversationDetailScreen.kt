package com.example.uai.ui.conversations

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.model.AiProviderType
import com.example.uai.ui.chat.MessageBubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var agentMenuExpanded by remember { mutableStateOf(false) }
    var attachMenuExpanded by remember { mutableStateOf(false) }
    var replyToMessage by remember { mutableStateOf<MessageEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { message ->
            val job = launch {
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = "Switch Model",
                    duration = SnackbarDuration.Indefinite
                ).let { result ->
                    if (result == SnackbarResult.ActionPerformed) agentMenuExpanded = true
                }
            }
            kotlinx.coroutines.delay(5_000)
            job.cancel()
        }
    }

    // Image attachment
    var pendingImageUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImageBase64 by remember { mutableStateOf<String?>(null) }
    var pendingImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // File attachment (text or PDF)
    var pendingFileName by remember { mutableStateOf<String?>(null) }
    var pendingFileText by remember { mutableStateOf<String?>(null) }
    var pendingDocumentBase64 by remember { mutableStateOf<String?>(null) }

    fun clearAttachments() {
        pendingImageUri = null
        pendingFileName = null
        pendingFileText = null
        pendingDocumentBase64 = null
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
                    if (activeAgent?.provider == AiProviderType.ANTHROPIC) {
                        val base64 = withContext(Dispatchers.IO) {
                            runCatching { context.contentResolver.openInputStream(uri)?.use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } }.getOrNull()
                        }
                        if (base64 != null) { pendingFileName = name; pendingDocumentBase64 = base64 }
                        else snackbarHostState.showSnackbar("Could not read PDF.")
                    } else {
                        snackbarHostState.showSnackbar("PDF upload is not supported by the current agent's model.")
                    }
                }
                else -> snackbarHostState.showSnackbar("Unsupported file type. Supported: images, text files, and PDF.")
            }
        }
    }

    // Encode gallery image when URI changes (camera images are encoded directly in the launcher)
    LaunchedEffect(pendingImageUri) {
        val uri = pendingImageUri ?: return@LaunchedEffect
        val (base64, bmp) = withContext(Dispatchers.IO) { encodeImageForApi(context, uri) }
        pendingImageBase64 = base64
        pendingImageBitmap = bmp
    }

    val hasAttachment = pendingImageUri != null || pendingFileName != null

    fun doSend() {
        val image = pendingImageBase64
        val doc = pendingDocumentBase64
        val fileContext = pendingFileText?.let { "```\n$it\n```\n\n" } ?: ""
        val replyContext = replyToMessage?.let {
            "> ${it.content.take(200).replace("\n", " ")}\n\n"
        } ?: ""
        val fullText = replyContext + fileContext + inputText

        viewModel.sendMessage(fullText, image, pendingImageUri?.toString(), doc)
        clearAttachments()
        replyToMessage = null
    }

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
                title = { Text(conversation?.title ?: "New Chat") },
                navigationIcon = { IconButton(onClick = openDrawer) { Icon(Icons.Default.Menu, "Menu") } },
                actions = {
                    if (activeAgent != null) {
                        Box {
                            TextButton(onClick = { agentMenuExpanded = true }) {
                                Text(activeAgent!!.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Icon(Icons.Default.ArrowDropDown, "Switch agent", tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(expanded = agentMenuExpanded, onDismissRequest = { agentMenuExpanded = false }) {
                                agents.forEach { agent ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(agent.name)
                                                    if (agent.supportsVision) Icon(Icons.Default.Image, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
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

            if (messages.isEmpty() && !isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(32.dp)) {
                        Text("What can I help you with?", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                        Text("Type a message below to get started.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        if (activeAgent != null) {
                            Spacer(Modifier.height(4.dp))
                            AssistChip(onClick = { agentMenuExpanded = true }, label = {
                                Text("${activeAgent!!.name} · ${activeAgent!!.provider.displayName}", style = MaterialTheme.typography.labelSmall)
                            })
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
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onReply = if (msg.role != "user" && !msg.isStreaming) ({ replyToMessage = msg }) else null
                        )
                    }
                    if (isLoading && messages.lastOrNull()?.isStreaming != true) {
                        item { Row(Modifier.fillMaxWidth()) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) } }
                    }
                }
            }

            if (activeAgent == null) {
                Text("No active agent. Go to Agents to set one up.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            // Reply preview bar
            replyToMessage?.let { reply ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                activeAgent?.name ?: "Assistant",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                reply.content.take(80) + if (reply.content.length > 80) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1
                            )
                        }
                    }
                    IconButton(onClick = { replyToMessage = null }) {
                        Icon(Icons.Default.Close, "Cancel reply", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Attachment preview strip
            if (hasAttachment) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (pendingImageBitmap != null) {
                        Image(
                            bitmap = pendingImageBitmap!!,
                            contentDescription = "Selected image",
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else if (pendingFileName != null) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.height(48.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Icon(
                                    if (pendingDocumentBase64 != null) Icons.Default.Description else Icons.Default.AttachFile,
                                    null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(pendingFileName!!, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                            }
                        }
                    }
                    IconButton(onClick = { clearAttachments() }) {
                        Icon(Icons.Default.Close, "Remove attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Input row
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).imePadding(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (activeAgent != null) {
                        Box {
                            IconButton(onClick = { attachMenuExpanded = true }, enabled = !isLoading) {
                                Icon(
                                    Icons.Default.Add, "Attach",
                                    tint = if (hasAttachment) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(expanded = attachMenuExpanded, onDismissRequest = { attachMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Camera") },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                    onClick = { cameraLauncher.launch(null); attachMenuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Photo") },
                                    leadingIcon = { Icon(Icons.Default.Image, null) },
                                    onClick = { imagePicker.launch("image/*"); attachMenuExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Document (text / PDF)") },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                                    onClick = { filePicker.launch("*/*"); attachMenuExpanded = false }
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }

                    TextField(
                        value = inputText,
                        onValueChange = viewModel::onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when {
                                    pendingImageUri != null -> "Ask about this image…"
                                    pendingFileName != null -> "Ask about this file…"
                                    else -> "Message…"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { doSend() }),
                        maxLines = 5,
                        enabled = !isLoading
                    )

                    if (isLoading) {
                        FilledIconButton(onClick = { viewModel.stopResponse() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Default.StopCircle, "Stop")
                        }
                    } else {
                        FilledIconButton(
                            onClick = { doSend() },
                            enabled = (inputText.isNotBlank() || hasAttachment) && activeAgent != null
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
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
        val bmp: Bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) } ?: return null to null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to bmp.asImageBitmap()
    } catch (_: Exception) { null to null }
}
