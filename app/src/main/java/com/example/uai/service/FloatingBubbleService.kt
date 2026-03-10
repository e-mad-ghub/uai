package com.example.uai.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.uai.MainActivity
import com.example.uai.R
import com.example.uai.UaiApplication
import com.example.uai.ai.AiProviderFactory
import com.example.uai.ai.ChatMessage
import com.example.uai.ai.ImageAttachment
import com.example.uai.ai.StreamChunk
import com.example.uai.data.db.ConversationEntity
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AppColorTheme
import com.example.uai.ui.MediaPickerActivity
import com.example.uai.ui.chat.BubbleContent
import com.example.uai.ui.chat.ChatPanel
import com.example.uai.ui.theme.UaiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private val lifecycleOwner = ServiceLifecycleOwner()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared mutable state observed by Compose
    private val chatMessages = mutableStateListOf<MessageEntity>()
    private var inputText by mutableStateOf("")
    private var isLoading by mutableStateOf(false)
    private var activeAgent: AgentConfig? by mutableStateOf(null)
    private var allAgents by mutableStateOf<List<AgentConfig>>(emptyList())
    private var colorTheme by mutableStateOf(AppColorTheme.TERRACOTTA)
    private var isDismissTargetActive by mutableStateOf(false)

    // Attachment state
    // Each Triple: (base64, ImageBitmap?, uriStr?)
    private val pendingImages = mutableStateListOf<Triple<String, ImageBitmap?, String?>>()
    // In-memory thumbnails for sent user messages (messageId → bitmaps); cleared on new conversation
    private val messageThumbnails = mutableStateMapOf<String, List<ImageBitmap>>()
    private var pendingFileName by mutableStateOf<String?>(null)
    private var pendingFileText by mutableStateOf<String?>(null)
    private var pendingDocumentBase64 by mutableStateOf<String?>(null)

    private var bubbleView: ComposeView? = null
    private var chatPanelView: ComposeView? = null
    // FrameLayout wrapper that intercepts BACK key to close the panel
    private var chatPanelContainer: FrameLayout? = null
    private var dismissZoneView: ComposeView? = null
    private lateinit var dismissZoneParams: WindowManager.LayoutParams
    private var isChatPanelVisible = false

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var currentConversationId: String? = null
    private var streamingJob: Job? = null
    private var cachedMediaProjection: MediaProjection? = null

    // Capture mode: triggered from main app, turns bubble into a capture button
    private var isCaptureMode by mutableStateOf(false)
    private var captureForConversationId: String? = null
    private var captureIsAgora: Boolean = false

    override fun onCreate() {
        super.onCreate()
        val container = (application as UaiApplication).container
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()

        startForegroundCompat()
        setupBubble()
        setupChatPanel()
        setupDismissZone()

        container.preferences.colorThemeFlow
            .onEach { colorTheme = it }
            .catch { }
            .launchIn(serviceScope)

        container.agentRepository.agentsFlow
            .onEach { allAgents = it }
            .catch { }
            .launchIn(serviceScope)

        // Agent observer: only restore history on initial load (service first start).
        var previousAgentId: String? = null
        container.agentRepository.activeAgentFlow
            .onEach { agent ->
                val newId = agent?.id
                if (newId != previousAgentId) {
                    val wasInitialLoad = previousAgentId == null
                    previousAgentId = newId
                    streamingJob?.cancel()
                    streamingJob = null
                    isLoading = false
                    if (wasInitialLoad && agent != null) {
                        restoreLatestConversation(agent)
                    }
                }
                activeAgent = agent
            }
            .catch { }
            .launchIn(serviceScope)

        serviceScope.launch {
            val (x, y) = container.preferences.bubblePosFlow.first()
            bubbleParams.x = x
            bubbleParams.y = y
            // Reveal the bubble at the correct position, avoiding an initial jump.
            bubbleParams.alpha = BUBBLE_NORMAL_ALPHA
            bubbleView?.let { windowManager.updateViewLayout(it, bubbleParams) }
        }

        // If the current conversation is deleted externally (e.g. from the main app),
        // clear the in-memory state so the bubble doesn't show a ghost chat.
        container.conversationRepository.getAllConversations()
            .onEach { conversations ->
                val id = currentConversationId
                if (id != null && conversations.none { it.id == id }) {
                    streamingJob?.cancel()
                    streamingJob = null
                    isLoading = false
                    currentConversationId = null
                    chatMessages.clear()
                }
            }
            .catch { }
            .launchIn(serviceScope)
    }

    override fun onDestroy() {
        super.onDestroy()
        cachedMediaProjection?.stop()
        cachedMediaProjection = null
        lifecycleOwner.onDestroy()
        serviceScope.cancel()
        removeSafely(chatPanelContainer)
        removeSafely(dismissZoneView)
        removeSafely(bubbleView)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENTER_CAPTURE_MODE) {
            val convId = intent.getStringExtra(EXTRA_CONV_ID) ?: return START_STICKY
            val isAgora = intent.getBooleanExtra(EXTRA_IS_AGORA, false)
            enterCaptureModeInternal(convId, isAgora)
        }
        return START_STICKY
    }

    private fun enterCaptureModeInternal(convId: String, isAgora: Boolean) {
        captureForConversationId = convId
        captureIsAgora = isAgora
        val projection = cachedMediaProjection
        if (projection != null) {
            isCaptureMode = true
        } else {
            // Request projection permission — MediaPickerActivity shows briefly for consent
            MediaPickerActivity.onProjectionConsent = { resultCode, data ->
                if (resultCode == Activity.RESULT_OK) {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mpm.getMediaProjection(resultCode, data)?.let { newProjection ->
                        newProjection.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() { cachedMediaProjection = null }
                        }, Handler(Looper.getMainLooper()))
                        cachedMediaProjection = newProjection
                        isCaptureMode = true
                    }
                }
            }
            startMediaPickerActivity(MediaPickerActivity.ACTION_SCREENSHOT)
        }
    }

    private fun doCaptureTap() {
        val projection = cachedMediaProjection ?: run { isCaptureMode = false; return }
        val convId = captureForConversationId ?: run { isCaptureMode = false; return }
        val isAgora = captureIsAgora

        // Hide bubble during capture to not appear in the screenshot
        bubbleParams.alpha = 0f
        bubbleView?.let { if (it.isAttachedToWindow) windowManager.updateViewLayout(it, bubbleParams) }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                performCapture(projection) { result ->
                    // Restore bubble
                    bubbleParams.alpha = BUBBLE_NORMAL_ALPHA
                    bubbleView?.let { if (it.isAttachedToWindow) windowManager.updateViewLayout(it, bubbleParams) }

                    // Reset capture mode
                    isCaptureMode = false
                    captureForConversationId = null
                    captureIsAgora = false

                    if (result != null) {
                        val (base64, bitmap) = result
                        screenshotResult.tryEmit(Triple(convId, base64, bitmap))
                    }

                    // Bring main app back to the correct conversation
                    val openIntent = Intent(this, MainActivity::class.java).apply {
                        action = ACTION_SCREENSHOT_CAPTURED
                        putExtra(EXTRA_CONV_ID, convId)
                        putExtra(EXTRA_IS_AGORA, isAgora)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(openIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("UAI_CAP", "capture mode error: ${e.message}")
                isCaptureMode = false
                captureForConversationId = null
                captureIsAgora = false
                bubbleParams.alpha = BUBBLE_NORMAL_ALPHA
                bubbleView?.let { if (it.isAttachedToWindow) windowManager.updateViewLayout(it, bubbleParams) }
            }
        }, 150L)
    }

    // ----- History restore -----

    private fun restoreLatestConversation(agent: AgentConfig) {
        serviceScope.launch {
            val container = (application as UaiApplication).container
            val latest = container.conversationRepository.getAllConversations().first()
                .filter { !it.isAgora && it.agentId == agent.id }
                .maxByOrNull { it.updatedAt } ?: return@launch
            val messages = container.conversationRepository
                .getMessagesList(latest.id)
                .filter { !it.isStreaming }
            if (messages.isNotEmpty() && activeAgent?.id == agent.id) {
                currentConversationId = latest.id
                chatMessages.addAll(messages)
            }
        }
    }

    // ----- Bubble setup -----

    private fun setupBubble() {
        // NOTE: The bubble may disappear on screens where apps use FLAG_SECURE
        // (e.g. banking apps, Samsung secure folder) or on certain Samsung full-screen
        // modes. The OS prevents overlays on those screens — this is not a service crash.
        val sizePx = (64 * resources.displayMetrics.density).toInt()
        bubbleParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
            // Start invisible so the bubble doesn't flash at the default position
            // before we restore the saved position.
            alpha = 0f
        }

        bubbleView = ComposeView(this).apply {
            attachLifecycleOwners(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UaiTheme(colorTheme = colorTheme) {
                    BubbleContent(isLoading = isLoading, isCaptureMode = isCaptureMode)
                }
            }
            setupDragAndTap(this)
        }
        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun setupDragAndTap(view: View) {
        var initialX = 0
        var initialY = 0
        var initialRawX = 0f
        var initialRawY = 0f
        var isDragging = false
        var longPressConsumed = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                longPressConsumed = true
                removeSafely(dismissZoneView)
                isDismissTargetActive = false
                val intent = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        })

        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialRawX = event.rawX
                    initialRawY = event.rawY
                    isDragging = false
                    longPressConsumed = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialRawX
                    val dy = event.rawY - initialRawY
                    if (!isDragging && (abs(dx) > 8 || abs(dy) > 8)) {
                        isDragging = true
                        dismissZoneView?.let {
                            if (!it.isAttachedToWindow) windowManager.addView(it, dismissZoneParams)
                        }
                    }
                    if (isDragging) {
                        val dm = resources.displayMetrics
                        val sizePx = (64 * dm.density).toInt()
                        val realHeight = getRealScreenHeight()
                        val navBarHeight = realHeight - dm.heightPixels
                        val statusBarHeight = getStatusBarHeight()
                        bubbleParams.x = (initialX + dx).toInt()
                            .coerceIn(0, dm.widthPixels - sizePx)
                        bubbleParams.y = (initialY + dy).toInt()
                            .coerceIn(statusBarHeight, dm.heightPixels - sizePx - navBarHeight)
                        windowManager.updateViewLayout(view, bubbleParams)
                        isDismissTargetActive = isOverDismissZone()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasOverDismiss = isDismissTargetActive
                    removeSafely(dismissZoneView)
                    isDismissTargetActive = false
                    if (!isDragging && !longPressConsumed) {
                        if (isCaptureMode) doCaptureTap() else toggleChatPanel()
                    } else if (isDragging) {
                        if (wasOverDismiss) {
                            stopSelf()
                        } else {
                            val dm = resources.displayMetrics
                            val sizePx = (64 * dm.density).toInt()
                            val targetX = if (bubbleParams.x + sizePx / 2 < dm.widthPixels / 2) 0
                                          else dm.widthPixels - sizePx
                            snapToSide(targetX)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeSafely(dismissZoneView)
                    isDismissTargetActive = false
                    longPressConsumed = false
                    if (isDragging) {
                        val dm = resources.displayMetrics
                        val sizePx = (64 * dm.density).toInt()
                        val targetX = if (bubbleParams.x + sizePx / 2 < dm.widthPixels / 2) 0
                                      else dm.widthPixels - sizePx
                        snapToSide(targetX)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToSide(targetX: Int) {
        val startX = bubbleParams.x
        ValueAnimator.ofInt(startX, targetX).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                bubbleParams.x = animator.animatedValue as Int
                bubbleView?.let { windowManager.updateViewLayout(it, bubbleParams) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    saveBubblePosition()
                }
            })
            start()
        }
    }

    private fun isOverDismissZone(): Boolean {
        val dm = resources.displayMetrics
        val density = dm.density
        val sizePx = (64 * density).toInt()
        val realHeight = getRealScreenHeight()
        val navBarHeight = realHeight - dm.heightPixels
        val dismissX = dm.widthPixels / 2
        val dismissY = realHeight - navBarHeight - ((DISMISS_BOTTOM_PAD_DP + DISMISS_RADIUS_DP) * density).toInt()
        val bubbleCenterX = bubbleParams.x + sizePx / 2
        val bubbleCenterY = bubbleParams.y + sizePx / 2
        val dx = (bubbleCenterX - dismissX).toLong()
        val dy = (bubbleCenterY - dismissY).toLong()
        val hitRadiusPx = (DISMISS_HIT_DP * density).toInt()
        return dx * dx + dy * dy < (hitRadiusPx * hitRadiusPx).toLong()
    }

    // ----- Dismiss zone overlay -----

    private fun setupDismissZone() {
        dismissZoneParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        dismissZoneView = ComposeView(this).apply {
            attachLifecycleOwners(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UaiTheme(colorTheme = colorTheme) {
                    val circleSize by animateDpAsState(
                        targetValue = if (isDismissTargetActive) 72.dp else 60.dp,
                        animationSpec = tween(150),
                        label = "dismissSize"
                    )
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = DISMISS_BOTTOM_PAD_DP.dp)
                                .size(circleSize)
                                .clip(CircleShape)
                                .background(
                                    if (isDismissTargetActive) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.errorContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss bubble",
                                tint = if (isDismissTargetActive) Color.White
                                       else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
            }
        }
        // Not added to WindowManager here — shown only while dragging
    }

    // ----- Chat panel setup -----

    private fun setupChatPanel() {
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            dimAmount = 0.4f
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        chatPanelView = ComposeView(this).apply {
            attachLifecycleOwners(this)
            // Keep the overlay composition alive across temporary detach/attach cycles
            // such as screenshot capture, so scroll state and other UI behavior persist.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UaiTheme(colorTheme = colorTheme) {
                    ChatPanel(
                        messages = chatMessages,
                        inputText = inputText,
                        isLoading = isLoading,
                        agentName = activeAgent?.name ?: "AI Agent",
                        agents = allAgents,
                        pendingImages = pendingImages.toList(),
                        pendingFileName = pendingFileName,
                        hasAttachment = pendingImages.isNotEmpty() || pendingFileName != null,
                        messageThumbnails = messageThumbnails,
                        onInputChange = { inputText = it },
                        onSend = ::sendMessage,
                        onStop = ::stopResponse,
                        onClose = ::dismissChatPanelAnimated,
                        onOpenInApp = ::openInApp,
                        onAgentSelect = { agent ->
                            serviceScope.launch {
                                (application as UaiApplication).container
                                    .agentRepository.setActiveAgent(agent.id)
                            }
                        },
                        onNewConversation = ::startNewConversation,
                        onPickGallery = ::launchGalleryPicker,
                        onPickCamera = ::launchCameraCapture,
                        onPickFile = ::launchFilePicker,
                        onTakeScreenshot = ::launchScreenshotCapture,
                        onClearAttachment = ::clearAttachment
                    )
                }
            }
        }

        // Wrap in a full-screen FrameLayout. Intercepts BACK to close panel and
        // touches above the panel to dismiss (tap-outside-to-close).
        chatPanelContainer = object : FrameLayout(this) {
            private var touchDownRawY = 0f
            private var touchDownRawX = 0f
            private var isDragIntercepted = false

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissChatPanelAnimated()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isDragIntercepted = false
                        touchDownRawY = ev.rawY
                        touchDownRawX = ev.rawX
                        chatPanelView?.let { panel ->
                            val rect = android.graphics.Rect()
                            panel.getGlobalVisibleRect(rect)
                            if (ev.rawY.toInt() < rect.top) {
                                dismissChatPanelAnimated()
                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isDragIntercepted) {
                            val dy = ev.rawY - touchDownRawY
                            val dx = ev.rawX - touchDownRawX
                            val slop = 12 * resources.displayMetrics.density
                            if (dy > slop && dy > kotlin.math.abs(dx)) {
                                chatPanelView?.let { panel ->
                                    val rect = android.graphics.Rect()
                                    panel.getGlobalVisibleRect(rect)
                                    val headerH = 56 * resources.displayMetrics.density
                                    if (touchDownRawY <= rect.top + headerH) {
                                        isDragIntercepted = true
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (!isDragIntercepted) return false
                val panel = chatPanelView ?: return false
                when (ev.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - touchDownRawY
                        panel.translationY = maxOf(0f, dy)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = ev.rawY - touchDownRawY
                        isDragIntercepted = false
                        if (dy >= panel.height * 0.25f) {
                            dismissChatPanelAnimated()
                        } else {
                            panel.animate()
                                .translationY(0f)
                                .setDuration(200)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        }
                    }
                }
                return true
            }
        }.also { container ->
            attachLifecycleOwners(container)
            container.addView(
                chatPanelView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
        }
    }

    private fun toggleChatPanel() {
        if (isChatPanelVisible) dismissChatPanelAnimated() else showChatPanel()
    }

    private fun showChatPanel() {
        chatPanelContainer?.let {
            if (!it.isAttachedToWindow) {
                // Set translationY BEFORE adding to window so the first draw is offscreen (no flash)
                val screenH = resources.displayMetrics.heightPixels.toFloat()
                chatPanelView?.translationY = screenH
                windowManager.addView(it, panelParams)
                isChatPanelVisible = true
                chatPanelView?.animate()
                    ?.translationY(0f)
                    ?.setDuration(280)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                    ?.start()
            }
        }
    }

    private fun dismissChatPanelAnimated() {
        val panel = chatPanelView ?: run { hideChatPanel(); return }
        val h = panel.height.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels.toFloat() / 3f
        panel.animate()
            .translationY(h)
            .setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { hideChatPanel() }
            .start()
    }

    private fun hideChatPanel() {
        removeSafely(chatPanelContainer)
        isChatPanelVisible = false
    }

    // ----- Attachment handling -----

    private fun clearAttachment() {
        pendingImages.clear()
        pendingFileName = null
        pendingFileText = null
        pendingDocumentBase64 = null
    }

    private fun launchGalleryPicker() {
        MediaPickerActivity.onImageResult = { uri ->
            serviceScope.launch {
                if (uri != null) {
                    val (base64, bitmap) = encodeImageFromUri(uri)
                    if (base64 != null) {
                        pendingImages.add(Triple(base64, bitmap, uri.toString()))
                    }
                }
                showChatPanel()
            }
        }
        hideChatPanel()
        startMediaPickerActivity(MediaPickerActivity.ACTION_GALLERY)
    }

    private fun launchCameraCapture() {
        MediaPickerActivity.onBitmapResult = { bitmap ->
            serviceScope.launch(Dispatchers.IO) {
                if (bitmap != null) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    val imgBitmap = bitmap.asImageBitmap()
                    withContext(Dispatchers.Main) {
                        pendingImages.add(Triple(base64, imgBitmap, null))
                    }
                }
                withContext(Dispatchers.Main) { showChatPanel() }
            }
        }
        hideChatPanel()
        startMediaPickerActivity(MediaPickerActivity.ACTION_CAMERA)
    }

    private fun launchFilePicker() {
        MediaPickerActivity.onFileResult = { uri ->
            if (uri != null) {
                serviceScope.launch {
                    // Process then always restore the panel
                    val mimeType = contentResolver.getType(uri) ?: ""
                    val name = uri.lastPathSegment ?: "file"
                    when {
                        mimeType.startsWith("image/") -> {
                            val (base64, bitmap) = encodeImageFromUri(uri)
                            if (base64 != null) {
                                // File-picked image replaces all existing attachments
                                pendingImages.clear()
                                pendingFileName = null
                                pendingFileText = null
                                pendingDocumentBase64 = null
                                pendingImages.add(Triple(base64, bitmap, uri.toString()))
                            }
                        }
                        mimeType.startsWith("text/") -> {
                            val text = withContext(Dispatchers.IO) {
                                runCatching {
                                    contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                                }.getOrNull()
                            }
                            if (text != null) {
                                pendingImages.clear()
                                pendingFileName = name
                                pendingFileText = text
                                pendingDocumentBase64 = null
                            }
                        }
                        mimeType == "application/pdf" -> {
                            val base64 = withContext(Dispatchers.IO) {
                                runCatching {
                                    contentResolver.openInputStream(uri)?.use {
                                        Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
                                    }
                                }.getOrNull()
                            }
                            if (base64 != null) {
                                pendingImages.clear()
                                pendingFileName = name
                                pendingFileText = null
                                pendingDocumentBase64 = base64
                            }
                        }
                        else -> { /* unsupported — silently ignore */ }
                    }
                    showChatPanel()
                }
            } else {
                showChatPanel() // cancelled
            }
        }
        hideChatPanel()
        startMediaPickerActivity(MediaPickerActivity.ACTION_FILE)
    }

    private fun launchScreenshotCapture() {
        fun suppressChatPanelForCapture(): (() -> Unit) {
            val container = chatPanelContainer
            val panel = chatPanelView
            if (container == null || panel == null || !container.isAttachedToWindow) {
                return {}
            }

            val previousDimAmount = panelParams.dimAmount
            val previousVisibility = container.visibility
            val previousAlpha = panel.alpha

            panel.animate().cancel()
            panel.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(panel.windowToken, 0)

            panelParams.dimAmount = 0f
            runCatching { windowManager.updateViewLayout(container, panelParams) }
            container.visibility = View.INVISIBLE
            panel.alpha = 0f

            return {
                if (container.isAttachedToWindow) {
                    container.visibility = previousVisibility
                    panel.alpha = previousAlpha
                    panelParams.dimAmount = previousDimAmount
                    runCatching { windowManager.updateViewLayout(container, panelParams) }
                }
            }
        }

        fun doCapture(projection: MediaProjection) {
            // Wait for the tap ripple animation to finish (~150ms) before detaching the panel.
            // If we tear down the panel mid-ripple, Samsung/Compose can leave the ripple animator
            // or panel state in a bad state. Keep the panel attached, but hidden, during capture.
            Handler(Looper.getMainLooper()).postDelayed({
                val restorePanel = suppressChatPanelForCapture()
                Handler(Looper.getMainLooper()).postDelayed({
                    bubbleParams.alpha = 0f
                    bubbleView?.let { if (it.isAttachedToWindow) windowManager.updateViewLayout(it, bubbleParams) }
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            performCapture(projection) { result ->
                                bubbleParams.alpha = BUBBLE_NORMAL_ALPHA
                                bubbleView?.let { if (it.isAttachedToWindow) windowManager.updateViewLayout(it, bubbleParams) }
                                if (result != null) {
                                    val (base64, bitmap) = result
                                    pendingImages.add(Triple(base64, bitmap, null))
                                }
                                restorePanel()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("UAI_CAP", "capture error: ${e.javaClass.simpleName}: ${e.message}")
                            cachedMediaProjection = null
                            bubbleParams.alpha = BUBBLE_NORMAL_ALPHA
                            bubbleView?.let { if (it.isAttachedToWindow) windowManager.updateViewLayout(it, bubbleParams) }
                            restorePanel()
                        }
                    }, 150L)
                }, 120L)
            }, 200L)
        }

        val projection = cachedMediaProjection
        if (projection != null) {
            doCapture(projection)
        } else {
            MediaPickerActivity.onProjectionConsent = { resultCode, data ->
                if (resultCode == Activity.RESULT_OK) {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mpm.getMediaProjection(resultCode, data)?.let { newProjection ->
                        newProjection.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() { cachedMediaProjection = null }
                        }, Handler(Looper.getMainLooper()))
                        cachedMediaProjection = newProjection
                        Handler(Looper.getMainLooper()).post { doCapture(newProjection) }
                    }
                }
            }
            startMediaPickerActivity(MediaPickerActivity.ACTION_SCREENSHOT)
        }
    }

    /** Captures one frame and returns base64+ImageBitmap encoded in the background thread. */
    private fun performCapture(projection: MediaProjection, onComplete: (Pair<String, ImageBitmap>?) -> Unit) {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi
        val mainHandler = Handler(Looper.getMainLooper())

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        // finished is only ever written on the main thread (timeout runnable + listener guard),
        // so no AtomicBoolean is needed. It prevents both double-finish and multiple threads.
        var finished = false

        // Always called on the main thread
        fun finish(result: Pair<String, ImageBitmap>?) {
            android.util.Log.d("UAI_CAP", "finish called: result=${if (result != null) "OK" else "null"}")
            if (finished) return
            finished = true
            virtualDisplay?.release()
            runCatching { imageReader.close() }
            onComplete(result)
        }

        val timeoutRunnable = Runnable { finish(null) }
        mainHandler.postDelayed(timeoutRunnable, 5000L)

        // The listener runs on mainHandler (main thread), so imageAcquired needs no synchronization.
        // It prevents spawning multiple threads when Samsung fires the listener several times.
        var imageAcquired = false
        imageReader.setOnImageAvailableListener({ reader ->
            if (imageAcquired) return@setOnImageAvailableListener
            // Samsung sometimes fires the listener before the buffer is ready — skip null acquisitions.
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            image ?: return@setOnImageAvailableListener
            imageAcquired = true
            mainHandler.removeCallbacks(timeoutRunnable)
            Thread {
                var result: Pair<String, ImageBitmap>? = null
                try {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * width
                    val raw = Bitmap.createBitmap(
                        width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    raw.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(raw, 0, 0, width, height)
                    raw.recycle()

                    // Scale down to max 1024px on the long edge (same as gallery images)
                    val scale = maxOf(1, maxOf(width, height) / 1024)
                    val scaled = if (scale > 1) {
                        Bitmap.createScaledBitmap(cropped, width / scale, height / scale, true)
                            .also { if (it !== cropped) cropped.recycle() }
                    } else cropped

                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    result = base64 to scaled.asImageBitmap()
                } catch (e: Exception) {
                    android.util.Log.e("UAI_CAP", "Bitmap encode failed: ${e.javaClass.simpleName}: ${e.message}", e)
                } finally {
                    image.close()
                    // finish() touches finished (main-thread state) — dispatch back to main
                    val r = result
                    mainHandler.post { finish(r) }
                }
            }.start()
        }, mainHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )
    }

    private fun startMediaPickerActivity(action: String) {
        val intent = Intent(this, MediaPickerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(MediaPickerActivity.EXTRA_ACTION, action)
        }
        startActivity(intent)
    }

    private suspend fun encodeImageFromUri(uri: Uri): Pair<String?, ImageBitmap?> {
        return withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1024)
                val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts2)
                } ?: return@withContext null to null
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                base64 to bmp.asImageBitmap()
            } catch (_: Exception) { null to null }
        }
    }

    // ----- Message sending -----

    private fun startNewConversation() {
        streamingJob?.cancel()
        streamingJob = null
        isLoading = false
        currentConversationId = null
        chatMessages.clear()
        messageThumbnails.clear()
        clearAttachment()
    }

    private fun openInApp() {
        val convId = currentConversationId ?: return
        dismissChatPanelAnimated()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.uai.OPEN_CONVERSATION"
            putExtra("conversationId", convId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun stopResponse() {
        streamingJob?.cancel()
        streamingJob = null
        isLoading = false
    }

    private fun sendMessage(text: String) {
        val agent = activeAgent
        val imageList = pendingImages.toList()
        val docBase64 = pendingDocumentBase64
        val fileCtx = pendingFileText?.let { "```\n$it\n```\n\n" } ?: ""
        val fullText = fileCtx + text

        if ((fullText.isBlank() && imageList.isEmpty() && docBase64 == null) || isLoading || agent == null) return

        // Clear attachment before starting the stream (don't wait for it)
        clearAttachment()

        val container = (application as UaiApplication).container

        streamingJob = serviceScope.launch {
            isLoading = true
            inputText = ""

            var assistantId: String? = null
            var accumulated = ""

            try {
                if (currentConversationId == null) {
                    val title = fullText.trim().ifBlank {
                        if (docBase64 != null) "Document" else if (imageList.isNotEmpty()) "Image" else "Chat"
                    }.take(60)
                    val conv = ConversationEntity(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        agentId = agent.id,
                        agentName = agent.name,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    container.conversationRepository.upsertConversation(conv)
                    currentConversationId = conv.id
                }
                val convId = currentConversationId!!

                val userMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = "user",
                    content = fullText,
                    createdAt = System.currentTimeMillis(),
                    imageUri = imageList.firstOrNull()?.third
                )
                container.conversationRepository.insertMessage(userMsg)
                chatMessages.add(userMsg)
                // Store in-memory thumbnails so the message bubble can display them
                val thumbs = imageList.mapNotNull { it.second }
                if (thumbs.isNotEmpty()) messageThumbnails[userMsg.id] = thumbs

                assistantId = UUID.randomUUID().toString()
                val assistantMsg = MessageEntity(
                    id = assistantId,
                    conversationId = convId,
                    role = "assistant",
                    content = "",
                    createdAt = System.currentTimeMillis(),
                    isStreaming = true,
                    agentName = agent.name
                )
                container.conversationRepository.insertMessage(assistantMsg)
                chatMessages.add(assistantMsg)

                // If agent doesn't support vision, insert a capability notice instead of calling API
                if (imageList.isNotEmpty() && !agent.supportsVision) {
                    val notice = "I don't support image analysis with \"${agent.model}\". " +
                            "Please switch to a vision-capable model in agent settings."
                    accumulated = notice  // must be non-blank so finally doesn't delete the message
                    val idx = chatMessages.indexOfFirst { it.id == assistantId }
                    if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(content = notice, isStreaming = false)
                    container.conversationRepository.updateMessageContent(assistantId, notice, false)
                    container.conversationRepository.touchConversation(convId)
                    return@launch
                }

                // Build history, attaching images/document to the last user message
                val allHistory = chatMessages.filter { !it.isStreaming }
                val history = allHistory.mapIndexed { idx, msg ->
                    if (idx == allHistory.lastIndex && msg.role == "user") {
                        when {
                            imageList.isNotEmpty() -> ChatMessage(
                                msg.role, msg.content,
                                images = imageList.map { ImageAttachment(it.first) }
                            )
                            docBase64 != null      -> ChatMessage(msg.role, msg.content, documentBase64 = docBase64)
                            else                   -> ChatMessage(msg.role, msg.content)
                        }
                    } else {
                        ChatMessage(msg.role, msg.content)
                    }
                }

                val provider = AiProviderFactory.create(agent, container.okHttpClient)

                provider.streamResponse(history, agent)
                    .catch { e -> emit(StreamChunk.Error(e)) }
                    .collect { chunk ->
                        val id = assistantId ?: return@collect
                        val idx = chatMessages.indexOfFirst { it.id == id }
                        when (chunk) {
                            is StreamChunk.Token -> {
                                accumulated += chunk.text
                                if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(content = accumulated)
                                container.conversationRepository.updateMessageContent(id, accumulated, true)
                            }
                            is StreamChunk.Done -> {
                                if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(isStreaming = false)
                                container.conversationRepository.updateMessageContent(id, accumulated, false)
                                container.conversationRepository.touchConversation(convId)
                            }
                            is StreamChunk.Error -> {
                                val errContent = if (accumulated.isBlank()) "[Error: ${chunk.cause.message}]"
                                                 else "$accumulated\n[Error: ${chunk.cause.message}]"
                                accumulated = errContent  // must be non-blank so finally doesn't delete the message
                                if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(content = errContent, isStreaming = false)
                                container.conversationRepository.updateMessageContent(id, errContent, false)
                            }
                        }
                    }
            } finally {
                withContext(NonCancellable) {
                    val id = assistantId
                    if (id != null) {
                        val idx = chatMessages.indexOfFirst { it.id == id }
                        if (accumulated.isBlank()) {
                            if (idx != -1) chatMessages.removeAt(idx)
                            container.conversationRepository.deleteMessage(id)
                        } else {
                            if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(isStreaming = false)
                            container.conversationRepository.updateMessageContent(id, accumulated, false)
                        }
                    }
                    isLoading = false
                }
            }
        }
    }

    // ----- Helpers -----

    private fun getRealScreenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val pt = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(pt)
            pt.y
        }

    private fun getStatusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun attachLifecycleOwners(view: View) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun removeSafely(view: View?) {
        if (view != null && view.isAttachedToWindow) windowManager.removeView(view)
    }

    private fun saveBubblePosition() {
        serviceScope.launch {
            (application as UaiApplication).container.preferences
                .saveBubblePosition(bubbleParams.x, bubbleParams.y)
        }
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun startForegroundCompat() {
        val notifIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notifIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, UaiApplication.BUBBLE_CHANNEL_ID)
            .setContentTitle("UAI Chat")
            .setContentText("AI chat bubble is active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val DISMISS_BOTTOM_PAD_DP = 48
        private const val DISMISS_RADIUS_DP = 30
        private const val DISMISS_HIT_DP = 60
        private const val BUBBLE_NORMAL_ALPHA = 0.82f

        const val ACTION_ENTER_CAPTURE_MODE = "com.example.uai.ENTER_CAPTURE_MODE"
        const val ACTION_SCREENSHOT_CAPTURED = "com.example.uai.SCREENSHOT_CAPTURED"
        const val EXTRA_CONV_ID = "conversationId"
        const val EXTRA_IS_AGORA = "isAgora"

        /** Replay-1 flow so collectors that start after emit() still receive the screenshot. */
        val screenshotResult = MutableSharedFlow<Triple<String, String, androidx.compose.ui.graphics.ImageBitmap>>(replay = 1)

        fun startService(context: android.content.Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stopService(context: android.content.Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }

        fun enterCaptureMode(context: android.content.Context, conversationId: String, isAgora: Boolean) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_ENTER_CAPTURE_MODE
                putExtra(EXTRA_CONV_ID, conversationId)
                putExtra(EXTRA_IS_AGORA, isAgora)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }
    }
}
