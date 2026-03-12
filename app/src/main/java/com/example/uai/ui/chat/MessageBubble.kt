package com.example.uai.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
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
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
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
import kotlin.math.roundToInt

@Composable
fun MessageBubble(
    message: MessageEntity,
    thumbnails: List<ImageBitmap> = emptyList(),
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
    var imageBitmap by remember(message.imageUri) { mutableStateOf<ImageBitmap?>(null) }
    val context = LocalContext.current
    LaunchedEffect(message.imageUri) {
        val uriStr = message.imageUri ?: return@LaunchedEffect
        imageBitmap = withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)?.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
    }

    val clipboardManager = LocalClipboardManager.current

    // Swipe-to-reply state (only active for assistant messages with onReply callback)
    val swipable = !isUser && onReply != null && !message.isStreaming
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    var replyTriggered by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    val maxDragPx = with(density) { 96.dp.toPx() }

    // Icon appearance driven by drag progress (0..1)
    val progress = (dragOffset.value / swipeThresholdPx).coerceIn(0f, 1f)
    val canCopyMessage = !message.isStreaming && message.content.isNotEmpty()
    @OptIn(ExperimentalFoundationApi::class)
    val interactionModifier = if (onDoubleTap != null || canCopyMessage) {
        Modifier.combinedClickable(
            onClick = {},
            onLongClick = if (canCopyMessage) {
                {
                    clipboardManager.setText(AnnotatedString(message.content))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            } else {
                null
            },
            onDoubleClick = onDoubleTap
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser && message.agentName != null) {
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
                                    val next = (dragOffset.value + amount).coerceIn(0f, maxDragPx)
                                    dragOffset.snapTo(next)
                                    if (next >= swipeThresholdPx && !replyTriggered) {
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
                        .align(Alignment.CenterStart)
                        .size(20.dp)
                        .alpha(progress)
                        .scale(0.5f + 0.5f * progress)
                )
            }

            // Bubble slides right with the drag
            @OptIn(ExperimentalFoundationApi::class)
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
                    .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
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
                            TypingIndicator(color = textColor)
                        } else {
                            when {
                                thumbnails.isNotEmpty() -> {
                                    if (thumbnails.size == 1) {
                                        Image(
                                            bitmap = thumbnails[0],
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
                                            thumbnails.forEach { bmp ->
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
                                imageBitmap != null -> Image(
                                    bitmap = imageBitmap!!,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                            if (message.content.isNotEmpty()) {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator(color: Color) {
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
    }
}
