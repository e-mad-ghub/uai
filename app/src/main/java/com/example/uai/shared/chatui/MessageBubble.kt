package com.example.uai.shared.chatui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.gson.JsonParser
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.uai.data.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
fun MessageBubble(
    message: MessageEntity,
    showAgentName: Boolean = true,
    thumbnails: List<ImageBitmap> = emptyList(),
    streamingStatusText: String? = null,
    onDoubleTap: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"

    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    // Load image from URI if present
    val cachedBitmap = remember(message.imageUri) {
        message.imageUri?.let { MessageImageBitmapCache.get(it) }
    }
    var imageBitmap by remember(message.imageUri) { mutableStateOf<ImageBitmap?>(cachedBitmap) }
    val context = LocalContext.current
    LaunchedEffect(message.imageUri) {
        val uriStr = message.imageUri ?: return@LaunchedEffect
        MessageImageBitmapCache.get(uriStr)?.let {
            imageBitmap = it
            return@LaunchedEffect
        }
        imageBitmap = withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
        imageBitmap?.let { MessageImageBitmapCache.put(uriStr, it) }
    }

    // Decode all images from imagesJson (multi-image messages)
    val decodedImages = remember(message.imagesJson) { mutableStateListOf<ImageBitmap>() }
    LaunchedEffect(message.imagesJson) {
        decodedImages.clear()
        val json = message.imagesJson?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val bitmaps = withContext(Dispatchers.IO) {
            try {
                JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                    val base64 = el.asJsonObject.get("base64")?.asString ?: return@mapNotNull null
                    try {
                        val bytes = Base64.decode(base64, Base64.NO_WRAP)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } catch (_: Exception) { null }
                }
            } catch (_: Exception) { emptyList() }
        }
        decodedImages.addAll(bitmaps)
    }

    // Swipe-to-reply state (enabled for any persisted message with a reply callback)
    val swipable = onReply != null && !message.isStreaming
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    var replyTriggered by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    val maxDragPx = with(density) { 96.dp.toPx() }

    // Icon appearance driven by drag progress (0..1)
    val progress = (dragOffset.value.absoluteValue / swipeThresholdPx).coerceIn(0f, 1f)
    val displayContent = remember(message.content, message.attachedFileName, message.attachedFileText) {
        parseAttachedFileDisplay(message)
    }
    val clipboardManager = LocalClipboardManager.current
    val textToCopy = displayContent.visibleText.takeIf { it.isNotBlank() }
    val interactionModifier = Modifier.pointerInput(onDoubleTap, textToCopy) {
        detectTapGestures(
            onDoubleTap = { onDoubleTap?.invoke() },
            onLongPress = { if (textToCopy != null) clipboardManager.setText(AnnotatedString(textToCopy)) }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser && showAgentName && message.agentName != null) {
            Text(
                text = message.agentName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
            )
        }

        Box(
            modifier = Modifier
                .then(
                    if (swipable) Modifier.pointerInput(message.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                replyTriggered = false
                                scope.launch {
                                    dragOffset.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            },
                            onDragCancel = {
                                replyTriggered = false
                                scope.launch { dragOffset.animateTo(0f, spring()) }
                            },
                            onHorizontalDrag = { _, amount ->
                                scope.launch {
                                    val next = if (isUser) {
                                        (dragOffset.value + amount).coerceIn(-maxDragPx, 0f)
                                    } else {
                                        (dragOffset.value + amount).coerceIn(0f, maxDragPx)
                                    }
                                    dragOffset.snapTo(next)
                                    val crossedThreshold = if (isUser) {
                                        next <= -swipeThresholdPx
                                    } else {
                                        next >= swipeThresholdPx
                                    }
                                    if (crossedThreshold && !replyTriggered) {
                                        replyTriggered = true
                                        onReply()
                                        dragOffset.animateTo(
                                            0f,
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                }
                            }
                        )
                    } else Modifier
                )
        ) {
            // Reply icon revealed behind the bubble as it slides
            if (swipable) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                        .size(20.dp)
                        .alpha(progress)
                        .scale(0.5f + 0.5f * progress)
                )
            }

            // Bubble slides right with the drag
            @OptIn(ExperimentalFoundationApi::class)
            Box(modifier = Modifier.offset { IntOffset(dragOffset.value.roundToInt(), 0) }) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp
                ),
                color = bubbleColor,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .then(interactionModifier)
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!isUser && message.responseModelId != null) {
                            Text(
                                text = if (message.responseModelIsFallback) {
                                    "Fallback via ${message.responseModelId}"
                                } else {
                                    "Model: ${message.responseModelId}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (!isUser && message.isStreaming && message.content.isEmpty()) {
                            TypingIndicator(
                                color = textColor,
                                label = streamingStatusText
                            )
                        } else {
                            val displayImages = thumbnails.ifEmpty { decodedImages }
                            when {
                                displayImages.isNotEmpty() -> {
                                    if (displayImages.size == 1) {
                                        Image(
                                            bitmap = displayImages[0],
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.FillWidth
                                        )
                                    } else {
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            displayImages.forEach { bmp ->
                                                Image(
                                                    bitmap = bmp,
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(110.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                        }
                                    }
                                }
                                imageBitmap != null -> imageBitmap?.let { bmp ->
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.FillWidth
                                    )
                                }
                            }
                            if (displayContent.fileNames.isNotEmpty() || displayContent.visibleText.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (displayContent.fileNames.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            displayContent.fileNames.forEach { fileName ->
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = if (isUser) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    } else {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.AttachFile,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp),
                                                            tint = textColor
                                                        )
                                                        Text(
                                                            text = fileName,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = textColor
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (displayContent.visibleText.isNotEmpty()) {
                                        MarkdownMessageText(
                                            text = displayContent.visibleText,
                                            color = textColor,
                                            baseStyle = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // Box (offset)
        }
    }
}

@Composable
private fun TypingIndicator(
    color: Color,
    label: String? = null
) {
    val transition = rememberInfiniteTransition(label = "typing")

    val alpha1 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "dot0"
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(170, StartOffsetType.FastForward)
        ), label = "dot1"
    )
    val alpha3 by transition.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(340, StartOffsetType.FastForward)
        ), label = "dot2"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(22.dp)
    ) {
        listOf(alpha1, alpha2, alpha3).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
        if (!label.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = 0.92f)
            )
        }
    }
}
