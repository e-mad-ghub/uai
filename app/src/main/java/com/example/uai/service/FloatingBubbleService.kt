package com.example.uai.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.IBinder
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
import android.widget.Toast
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.uai.MainActivity
import com.example.uai.R
import com.example.uai.UaiApplication
import com.example.uai.ai.AssistantStreamingSession
import com.example.uai.ai.FileAttachmentContext
import com.example.uai.ai.ImageAttachment
import com.example.uai.ai.StreamChunk
import com.example.uai.ai.ThrottledStreamingMessageWriter
import com.example.uai.ai.compressHistory
import com.example.uai.ai.sanitizeGroundedAssistantResponse
import com.example.uai.data.db.ConversationEntity
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.db.toChatMessage
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.model.hasInternetAccess
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AppColorTheme
import com.example.uai.ui.MediaPickerActivity
import com.example.uai.ui.OverlayScreenCaptureActivity
import com.example.uai.ui.OverlayScreenCaptureOutcome
import com.example.uai.ui.chat.persistImageAttachment
import com.example.uai.ui.chat.FileAttachmentImportResult
import com.example.uai.ui.chat.BubbleContent
import com.example.uai.ui.chat.ChatPanel
import com.example.uai.ui.chat.importFileAttachment
import com.example.uai.ui.theme.UaiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private enum class OverlaySurfaceState {
        BubbleVisible,
        PanelVisible,
        ExternalFlow,
        AppForegroundSuppressed
    }

    private enum class BubbleLayoutMode {
        Portrait,
        Wide
    }

    private data class PendingAssistantRepairToast(
        val conversationId: String,
        val message: String
    )

    private lateinit var windowManager: WindowManager
    private val lifecycleOwner = ServiceLifecycleOwner()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared mutable state observed by Compose
    private val chatMessages = mutableStateListOf<MessageEntity>()
    private var inputText by mutableStateOf("")
    private var isLoading by mutableStateOf(false)
    private var activeAgent: AgentConfig? by mutableStateOf(null)
    private var allAgents by mutableStateOf<List<AgentConfig>>(emptyList())
    private var availableConversations by mutableStateOf<List<ConversationEntity>>(emptyList())
    private var colorTheme by mutableStateOf(AppColorTheme.DEFAULT)
    private var isDismissTargetActive by mutableStateOf(false)
    private var isAppUiVisible = false
    private var miniChatMinimizeTipDismissed by mutableStateOf(false)
    private var miniChatScreenshotHintMessage by mutableStateOf<String?>(null)
    private var onlineSearchStatusMessage by mutableStateOf<String?>(null)
    private var currentSession by mutableStateOf<AssistantStreamingSession?>(null)

    // Attachment state
    // Each Triple: (base64, ImageBitmap?, uriStr?)
    private val pendingImages = mutableStateListOf<Triple<String, ImageBitmap?, String?>>()
    // In-memory thumbnails for sent user messages (messageId → bitmaps); cleared on new conversation
    private val messageThumbnails = mutableStateMapOf<String, List<ImageBitmap>>()
    private var pendingFileName by mutableStateOf<String?>(null)
    private var pendingFileText by mutableStateOf<String?>(null)
    private var isOverlayScreenshotCaptureInProgress = false

    private var bubbleView: ComposeView? = null
    private var chatPanelView: ComposeView? = null
    // FrameLayout wrapper that intercepts BACK key to close the panel
    private var chatPanelContainer: FrameLayout? = null
    private var dismissZoneView: ComposeView? = null
    private lateinit var dismissZoneParams: WindowManager.LayoutParams
    private var isChatPanelVisible = false

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var allConversations: List<ConversationEntity> = emptyList()
    private var hasConversationSnapshot = false
    private var currentConversationId: String? = null
    private var currentConversationMessagesJob: Job? = null
    private var prefersDraftConversation = false
    private var draftAgentId: String? = null
    private var pendingAssistantRepairToast: PendingAssistantRepairToast? = null
    private var pendingPanelShowAfterAppHidden = false
    private var screenshotRestoreJob: Job? = null
    private var screenshotHintJob: Job? = null
    private var streamingJob: Job? = null
    private var bubbleIdleJob: Job? = null
    private var isChatPanelAnimating = false
    private var overlaySurfaceState = OverlaySurfaceState.BubbleVisible
    private var currentBubbleLayoutMode = BubbleLayoutMode.Portrait
    private var pendingPanelRestoreAfterExternalFlow = false
    private var panelTransitionGeneration = 0L
    private var externalFlowGeneration = 0L
    private var repairInFlightKey: String? = null
    private var lastAssistantRepairNotificationKey: String? = null
    private var bubbleAlphaAnimator: ValueAnimator? = null
    private val systemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
            if (overlaySurfaceState == OverlaySurfaceState.ExternalFlow || isOverlayScreenshotCaptureInProgress) {
                pendingPanelRestoreAfterExternalFlow = false
                return
            }
            serviceScope.launch {
                minimizeChatPanelToBubble(immediate = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as UaiApplication
        val container = app.container
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        currentBubbleLayoutMode = detectBubbleLayoutMode()
        isAppUiVisible = app.isAppUiVisible.value

        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()

        startForegroundCompat()
        setupBubble()
        setupChatPanel()
        setupDismissZone()
        registerSystemDialogReceiver()

        container.preferences.colorThemeFlow
            .onEach { colorTheme = it }
            .catch { }
            .launchIn(serviceScope)

        container.preferences.miniChatMinimizeTipDismissedFlow
            .onEach { miniChatMinimizeTipDismissed = it }
            .catch { }
            .launchIn(serviceScope)

        app.isAppUiVisible
            .onEach { visible ->
                isAppUiVisible = visible
                if (visible) suppressOverlaysWhileAppVisible()
                else restoreOverlayAfterAppHidden()
            }
            .catch { }
            .launchIn(serviceScope)

        container.agentRepository.agentsFlow
            .onEach { agents ->
                allAgents = agents
                if (hasConversationSnapshot) {
                    synchronizeConversationSelection()
                }
            }
            .catch { }
            .launchIn(serviceScope)

        container.agentRepository.activeAgentFlow
            .onEach { agent ->
                activeAgent = agent
                if (hasConversationSnapshot) {
                    synchronizeConversationSelection()
                }
            }
            .catch { }
            .launchIn(serviceScope)

        serviceScope.launch {
            // Reveal the bubble at the correct position, avoiding an initial jump.
            bubbleParams.alpha = if (isAppUiVisible) 0f else BUBBLE_NORMAL_ALPHA
            restoreBubblePositionForCurrentLayout(seedDefaultIfMissing = true)
            if (!isAppUiVisible) {
                scheduleBubbleIdleFade()
            }
        }

        container.conversationRepository.getAllConversations()
            .onEach { conversations ->
                allConversations = conversations
                hasConversationSnapshot = true
                synchronizeConversationSelection()
            }
            .catch { }
            .launchIn(serviceScope)
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaPickerActivity.clearCallbacks()
        OverlayScreenCaptureActivity.clearPendingRequest()
        unregisterSystemDialogReceiver()
        lifecycleOwner.onDestroy()
        serviceScope.cancel()
        screenshotRestoreJob?.cancel()
        screenshotHintJob?.cancel()
        currentConversationMessagesJob?.cancel()
        removeSafely(chatPanelContainer, immediate = true)
        removeSafely(dismissZoneView, immediate = true)
        removeSafely(bubbleView, immediate = true)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        serviceScope.launch {
            val previousMode = currentBubbleLayoutMode
            val newMode = detectBubbleLayoutMode()
            if (previousMode != newMode) {
                saveBubblePositionForMode(previousMode)
                currentBubbleLayoutMode = newMode
                restoreBubblePositionForCurrentLayout(seedDefaultIfMissing = true)
            } else {
                clampBubblePositionToDisplay(saveIfChanged = true)
            }
            restoreChatPanelWindowState()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleStartIntent(intent)
        return START_STICKY
    }

    private fun handleStartIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_CHAT_PANEL -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
                prefersDraftConversation = false
                switchConversation(conversationId, force = true)
                pendingPanelShowAfterAppHidden = true
                if (!isAppUiVisible) {
                    showChatPanel()
                }
            }
            ACTION_OPEN_DRAFT_CHAT_PANEL -> {
                val assistantId = intent.getStringExtra(EXTRA_ASSISTANT_ID)
                prefersDraftConversation = true
                draftAgentId = assistantId ?: draftAgentId
                switchConversation(null, force = true)
                pendingPanelShowAfterAppHidden = true
                if (!isAppUiVisible) {
                    showChatPanel()
                }
            }
            ACTION_SUPPRESS_FOR_FOREGROUND_APP -> {
                pendingPanelRestoreAfterExternalFlow = false
                pendingPanelShowAfterAppHidden = false
                forceHideOverlayWindows("foreground-app-command")
                overlaySurfaceState = OverlaySurfaceState.AppForegroundSuppressed
            }
        }
    }

    // ----- Conversation sync -----

    private fun currentConversationEntity(): ConversationEntity? {
        val conversationId = currentConversationId ?: return null
        return allConversations.firstOrNull { !it.isAgora && it.id == conversationId }
    }

    private fun conversationsForOverlay(): List<ConversationEntity> {
        return allConversations
            .filter { !it.isAgora }
            .sortedWith(compareByDescending<ConversationEntity> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    private fun resolvedDefaultAgent(): AgentConfig? {
        val currentDefault = activeAgent ?: return null
        return currentDefault.takeIf { candidate ->
            allAgents.any { it.id == candidate.id }
        }
    }

    private fun fallbackAgentForCurrentContext(): AgentConfig? =
        resolvedDefaultAgent() ?: allAgents.firstOrNull()

    private fun selectedAgentForCurrentContext(): AgentConfig? {
        currentConversationEntity()?.let { conversation ->
            return allAgents.firstOrNull { it.id == conversation.agentId }
                ?: fallbackAgentForCurrentContext()
        }

        val draftAgentId = draftAgentId
        if (draftAgentId != null) {
            return allAgents.firstOrNull { it.id == draftAgentId }
                ?: fallbackAgentForCurrentContext()
        }

        return fallbackAgentForCurrentContext()
    }

    private fun queueOrShowAssistantRepairToast(conversationId: String, message: String) {
        val pendingToast = PendingAssistantRepairToast(conversationId, message)
        if (isChatPanelToastVisible() && currentConversationId == conversationId) {
            pendingAssistantRepairToast = null
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        } else {
            pendingAssistantRepairToast = pendingToast
        }
    }

    private fun flushPendingAssistantRepairToast() {
        if (!isChatPanelToastVisible()) return
        val pendingToast = pendingAssistantRepairToast ?: return
        if (pendingToast.conversationId != currentConversationId) return
        pendingAssistantRepairToast = null
        Toast.makeText(applicationContext, pendingToast.message, Toast.LENGTH_LONG).show()
    }

    private fun isChatPanelToastVisible(): Boolean {
        return overlaySurfaceState == OverlaySurfaceState.PanelVisible ||
                (isChatPanelVisible && chatPanelContainer?.isAttachedToWindow == true)
    }

    private fun showMiniChatScreenshotHint(message: String) {
        miniChatScreenshotHintMessage = message
        screenshotHintJob?.cancel()
        screenshotHintJob = serviceScope.launch {
            delay(3_500L)
            if (miniChatScreenshotHintMessage == message) {
                miniChatScreenshotHintMessage = null
            }
        }
    }

    private fun repairCurrentConversationAssignmentIfNeeded(conversation: ConversationEntity) {
        val assignedAgent = allAgents.firstOrNull { it.id == conversation.agentId }
        when {
            assignedAgent != null -> {
                val syncKey = "sync:${conversation.id}:${assignedAgent.id}:${assignedAgent.name}"
                if (conversation.agentName == assignedAgent.name || repairInFlightKey == syncKey) return
                repairInFlightKey = syncKey
                val container = (application as UaiApplication).container
                serviceScope.launch {
                    try {
                        container.conversationRepository.upsertConversation(
                            conversation.copy(agentName = assignedAgent.name)
                        )
                    } finally {
                        if (repairInFlightKey == syncKey) {
                            repairInFlightKey = null
                        }
                    }
                }
            }

            else -> {
                val fallbackAgent = fallbackAgentForCurrentContext() ?: return
                val repairKey = "repair:${conversation.id}:${conversation.agentId}:${fallbackAgent.id}"
                if (repairInFlightKey == repairKey) return
                repairInFlightKey = repairKey
                val container = (application as UaiApplication).container
                serviceScope.launch {
                    try {
                        if (resolvedDefaultAgent() == null) {
                            container.agentRepository.setActiveAgent(fallbackAgent.id)
                        }
                        container.conversationRepository.upsertConversation(
                            conversation.copy(
                                agentId = fallbackAgent.id,
                                agentName = fallbackAgent.name
                            )
                        )
                        if (lastAssistantRepairNotificationKey != repairKey) {
                            lastAssistantRepairNotificationKey = repairKey
                            queueOrShowAssistantRepairToast(
                                conversationId = conversation.id,
                                message = "This chat's previous assistant is no longer available. Switched to ${fallbackAgent.name}."
                            )
                        }
                    } finally {
                        if (repairInFlightKey == repairKey) {
                            repairInFlightKey = null
                        }
                    }
                }
            }
        }
    }

    private fun synchronizeConversationSelection() {
        availableConversations = conversationsForOverlay()
        val currentConversation = currentConversationEntity()
        val fallbackAgent = fallbackAgentForCurrentContext()

        if (draftAgentId != null && allAgents.none { it.id == draftAgentId }) {
            draftAgentId = fallbackAgent?.id
            if (resolvedDefaultAgent() == null && fallbackAgent != null) {
                serviceScope.launch {
                    (application as UaiApplication).container.agentRepository.setActiveAgent(fallbackAgent.id)
                }
            }
        }

        when {
            currentConversation != null -> {
                if (currentConversationMessagesJob == null) {
                    switchConversation(currentConversation.id, force = true)
                } else {
                    repairCurrentConversationAssignmentIfNeeded(currentConversation)
                }
            }
            prefersDraftConversation -> {
                switchConversation(null, force = currentConversationId != null || chatMessages.isNotEmpty())
            }
            else -> {
                val fallbackConversation = availableConversations.firstOrNull()
                prefersDraftConversation = fallbackConversation == null && hasConversationSnapshot
                switchConversation(fallbackConversation?.id, force = true)
            }
        }
    }

    private fun switchConversation(conversationId: String?, force: Boolean = false) {
        if (!force && currentConversationId == conversationId) return

        currentConversationMessagesJob?.cancel()
        currentConversationMessagesJob = null
        currentConversationId = conversationId
        inputText = ""
        clearAttachment()
        messageThumbnails.clear()

        if (conversationId == null) {
            chatMessages.clear()
            return
        }

        currentConversationEntity()?.let(::repairCurrentConversationAssignmentIfNeeded)
        flushPendingAssistantRepairToast()

        val container = (application as UaiApplication).container
        currentConversationMessagesJob = container.conversationRepository
            .getMessages(conversationId)
            .onEach { messages ->
                chatMessages.clear()
                chatMessages.addAll(messages)
            }
            .catch {
                currentConversationId = null
                chatMessages.clear()
            }
            .launchIn(serviceScope)
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
                    BubbleContent(isLoading = isLoading)
                }
            }
            setupDragAndTap(this)
        }
        windowManager.addView(bubbleView, bubbleParams)
        clampBubblePositionToDisplay(saveIfChanged = false)
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
                removeSafely(dismissZoneView, immediate = true)
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
                    activateBubbleOpacity()
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
                    removeSafely(dismissZoneView, immediate = true)
                    isDismissTargetActive = false
                    if (!isDragging && !longPressConsumed) {
                        toggleChatPanel()
                    } else if (isDragging) {
                        if (wasOverDismiss) {
                            disableBubbleFromDismissZone()
                        } else {
                            val dm = resources.displayMetrics
                            val sizePx = (64 * dm.density).toInt()
                            val targetX = if (bubbleParams.x + sizePx / 2 < dm.widthPixels / 2) 0
                                          else dm.widthPixels - sizePx
                            snapToSide(targetX)
                        }
                    } else {
                        scheduleBubbleIdleFade()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeSafely(dismissZoneView, immediate = true)
                    isDismissTargetActive = false
                    longPressConsumed = false
                    if (isDragging) {
                        val dm = resources.displayMetrics
                        val sizePx = (64 * dm.density).toInt()
                        val targetX = if (bubbleParams.x + sizePx / 2 < dm.widthPixels / 2) 0
                                      else dm.widthPixels - sizePx
                        snapToSide(targetX)
                    } else {
                        scheduleBubbleIdleFade()
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
                    scheduleBubbleIdleFade()
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
            alpha = 0.79f
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
        val container = (application as UaiApplication).container
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
                    val sessionState by produceState<AssistantStreamingSession.State?>(null, currentSession) {
                        val session = currentSession
                        if (session == null) { value = null; return@produceState }
                        session.state.collect { value = it }
                    }
                    val displayMessages by remember {
                        derivedStateOf {
                            val state = sessionState
                            if (state == null) chatMessages.toList()
                            else chatMessages.mapNotNull { msg ->
                                when {
                                    msg.id != state.messageId -> msg
                                    state.hidden -> null
                                    else -> msg.copy(content = state.content, isStreaming = state.isStreaming)
                                }
                            }
                        }
                    }
                    ChatPanel(
                        messages = displayMessages,
                        inputText = inputText,
                        isLoading = isLoading,
                        agentName = selectedAgentForCurrentContext()?.name ?: "Select assistant",
                        selectedAgentId = selectedAgentForCurrentContext()?.id,
                        hasSelectedAgent = selectedAgentForCurrentContext() != null,
                        agents = allAgents,
                        conversations = availableConversations,
                        currentConversationId = currentConversationId,
                        pendingImages = pendingImages.toList(),
                        pendingFileName = pendingFileName,
                        hasAttachment = pendingImages.isNotEmpty() || pendingFileName != null,
                        messageThumbnails = messageThumbnails,
                        onInputChange = { inputText = it },
                        onSend = ::sendMessage,
                        onStop = ::stopResponse,
                        onMinimize = ::dismissChatPanelAnimated,
                        onOpenInApp = ::openInApp,
                        onAgentSelect = { agent ->
                            serviceScope.launch {
                                val conversation = currentConversationEntity()
                                if (conversation != null) {
                                    container.conversationRepository.upsertConversation(
                                        conversation.copy(
                                            agentId = agent.id,
                                            agentName = agent.name
                                        )
                                    )
                                } else {
                                    draftAgentId = agent.id
                                }
                            }
                        },
                        onConversationSelect = { conversationId ->
                            prefersDraftConversation = conversationId == null
                            switchConversation(
                                conversationId,
                                force = conversationId != currentConversationId
                            )
                        },
                        onNewConversation = ::startNewConversation,
                        onPickGallery = ::launchGalleryPicker,
                        onPickCamera = ::launchCameraCapture,
                        onPickFile = ::launchFilePicker,
                        onTakeScreenshot = ::launchScreenshotCapture,
                        onClearAttachment = ::clearAttachment,
                        onRemoveImage = { idx ->
                            if (idx in pendingImages.indices) pendingImages.removeAt(idx)
                        },
                        showMiniChatMinimizeTip = !miniChatMinimizeTipDismissed,
                        onDismissMiniChatMinimizeTip = {
                            serviceScope.launch {
                                container.preferences.setMiniChatMinimizeTipDismissed(true)
                            }
                        },
                        screenshotHintMessage = miniChatScreenshotHintMessage,
                        loadingStatusText = onlineSearchStatusMessage
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
                            if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
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
        if (isOverlayScreenshotCaptureInProgress || isChatPanelAnimating || isAppUiVisible) return
        when {
            overlaySurfaceState == OverlaySurfaceState.ExternalFlow -> return
            overlaySurfaceState == OverlaySurfaceState.PanelVisible ||
                    chatPanelContainer?.isAttachedToWindow == true -> dismissChatPanelAnimated()
            else -> showChatPanel()
        }
    }

    private fun restoreChatPanelWindowState() {
        panelParams.flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND
        panelParams.dimAmount = 0.4f
        panelParams.alpha = 1f
        chatPanelContainer?.alpha = 1f
        chatPanelView?.let { panel ->
            panel.animate().cancel()
            panel.alpha = 1f
        }
        chatPanelContainer?.let { container ->
            if (container.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(container, panelParams) }
            }
        }
    }

    private fun showChatPanel() {
        if (isOverlayScreenshotCaptureInProgress || overlaySurfaceState == OverlaySurfaceState.ExternalFlow || isAppUiVisible) return
        pendingPanelShowAfterAppHidden = false
        if (chatPanelView == null || chatPanelContainer == null) {
            setupChatPanel()
        }

        restoreChatPanelWindowState()
        hideBubbleWindow(immediate = true)
        chatPanelContainer?.let { container ->
            isChatPanelVisible = true
            isChatPanelAnimating = false
            if (!container.isAttachedToWindow) {
                // Set translationY BEFORE adding to window so the first draw is offscreen (no flash)
                val screenH = resources.displayMetrics.heightPixels.toFloat()
                chatPanelView?.translationY = screenH
                windowManager.addView(container, panelParams)
                isChatPanelAnimating = true
                val transitionGeneration = nextPanelTransitionGeneration()
                chatPanelView?.animate()
                    ?.translationY(0f)
                    ?.setDuration(280)
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                    ?.withEndAction {
                        if (panelTransitionGeneration != transitionGeneration) return@withEndAction
                        isChatPanelAnimating = false
                        overlaySurfaceState = OverlaySurfaceState.PanelVisible
                        flushPendingAssistantRepairToast()
                    }
                    ?.start()
            } else {
                nextPanelTransitionGeneration()
                chatPanelView?.animate()?.cancel()
                chatPanelView?.translationY = 0f
                overlaySurfaceState = OverlaySurfaceState.PanelVisible
                flushPendingAssistantRepairToast()
            }
        }
    }

    private fun dismissChatPanelAnimated() {
        if (overlaySurfaceState == OverlaySurfaceState.ExternalFlow) return
        if (isChatPanelAnimating && !isChatPanelVisible) return
        val panel = chatPanelView ?: run { hideChatPanel(immediate = true); return }
        if (chatPanelContainer?.isAttachedToWindow != true) {
            hideChatPanel(immediate = true)
            return
        }
        val h = panel.height.toFloat().takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels.toFloat() / 3f
        isChatPanelVisible = false
        isChatPanelAnimating = true
        val transitionGeneration = nextPanelTransitionGeneration()
        panel.animate().cancel()
        panel.animate()
            .translationY(h)
            .setDuration(220)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (panelTransitionGeneration != transitionGeneration) return@withEndAction
                hideChatPanel(immediate = true)
            }
            .start()
    }

    private fun hideChatPanel(
        immediate: Boolean = false,
        restoreBubble: Boolean = true
    ) {
        nextPanelTransitionGeneration()
        clearChatPanelInteractionState()
        removeSafely(chatPanelContainer, immediate = immediate)
        chatPanelView?.disposeComposition()
        chatPanelContainer = null
        chatPanelView = null
        isChatPanelVisible = false
        isChatPanelAnimating = false
        overlaySurfaceState = if (restoreBubble) {
            OverlaySurfaceState.BubbleVisible
        } else {
            OverlaySurfaceState.ExternalFlow
        }
        if (restoreBubble) {
            ensureBubbleVisible()
        }
    }

    private fun minimizeChatPanelToBubble(immediate: Boolean = true) {
        removeSafely(dismissZoneView, immediate = true)
        isDismissTargetActive = false
        hideChatPanel(immediate = immediate, restoreBubble = true)
    }

    private fun ensureBubbleVisible() {
        if (!::bubbleParams.isInitialized) return
        if (isAppUiVisible) {
            overlaySurfaceState = OverlaySurfaceState.AppForegroundSuppressed
            return
        }
        if (bubbleView == null) {
            setupBubble()
        }
        clampBubblePositionToDisplay(saveIfChanged = true)
        bubbleParams.flags = BUBBLE_WINDOW_FLAGS
        bubbleParams.alpha = BUBBLE_NORMAL_ALPHA
        bubbleView?.let { bubble ->
            if (bubble.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(bubble, bubbleParams) }
            } else {
                runCatching { windowManager.addView(bubble, bubbleParams) }
            }
        }
        overlaySurfaceState = OverlaySurfaceState.BubbleVisible
        scheduleBubbleIdleFade()
    }

    private fun hideBubbleWindow(immediate: Boolean = true) {
        bubbleIdleJob?.cancel()
        bubbleIdleJob = null
        bubbleAlphaAnimator?.cancel()
        bubbleAlphaAnimator = null
        removeSafely(bubbleView, immediate = immediate)
    }

    private fun clearChatPanelInteractionState() {
        chatPanelView?.animate()?.cancel()
        chatPanelView?.clearFocus()
        chatPanelContainer?.clearFocus()
        chatPanelView?.translationY = 0f
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(chatPanelView?.windowToken ?: chatPanelContainer?.windowToken, 0)
    }

    private fun suspendOverlaysForExternalFlow(reopenPanelOnReturn: Boolean): Long {
        externalFlowGeneration += 1
        val flowGeneration = externalFlowGeneration
        pendingPanelRestoreAfterExternalFlow =
            reopenPanelOnReturn && (
                    overlaySurfaceState == OverlaySurfaceState.PanelVisible ||
                            isChatPanelVisible ||
                            chatPanelContainer?.isAttachedToWindow == true
                    )
        removeSafely(dismissZoneView, immediate = true)
        isDismissTargetActive = false
        hideChatPanel(immediate = true, restoreBubble = false)
        hideBubbleWindow(immediate = true)
        overlaySurfaceState = OverlaySurfaceState.ExternalFlow
        return flowGeneration
    }

    private fun restoreOverlaysAfterExternalFlow(
        flowGeneration: Long,
        forcePanelVisible: Boolean? = null
    ) {
        if (flowGeneration != externalFlowGeneration) return
        screenshotRestoreJob?.cancel()
        screenshotRestoreJob = null
        isOverlayScreenshotCaptureInProgress = false

        val reopenPanel = forcePanelVisible ?: pendingPanelRestoreAfterExternalFlow
        pendingPanelRestoreAfterExternalFlow = false
        overlaySurfaceState = OverlaySurfaceState.BubbleVisible

        if (dismissZoneView == null) {
            setupDismissZone()
        }
        if (chatPanelView == null || chatPanelContainer == null) {
            setupChatPanel()
        }
        if (!reopenPanel && bubbleView == null) {
            setupBubble()
        }

        android.util.Log.d(
            "UAI_CAP",
            "restoring overlays after external flow: flow=$flowGeneration reopenPanel=$reopenPanel"
        )

        if (isAppUiVisible) {
            suppressOverlaysWhileAppVisible()
            return
        }

        if (reopenPanel) {
            showChatPanel()
        } else {
            ensureBubbleVisible()
        }
    }

    private fun suppressOverlaysWhileAppVisible() {
        if (overlaySurfaceState == OverlaySurfaceState.ExternalFlow || isOverlayScreenshotCaptureInProgress) {
            return
        }
        forceHideOverlayWindows("app-visible")
        overlaySurfaceState = OverlaySurfaceState.AppForegroundSuppressed
    }

    private fun restoreOverlayAfterAppHidden() {
        if (overlaySurfaceState != OverlaySurfaceState.AppForegroundSuppressed || isOverlayScreenshotCaptureInProgress) {
            return
        }
        if (pendingPanelShowAfterAppHidden) {
            showChatPanel()
            return
        }
        if (bubbleView == null) {
            setupBubble()
        }
        ensureBubbleVisible()
    }

    private fun registerSystemDialogReceiver() {
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemDialogsReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(systemDialogsReceiver, filter)
        }
    }

    private fun unregisterSystemDialogReceiver() {
        runCatching { unregisterReceiver(systemDialogsReceiver) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (overlaySurfaceState == OverlaySurfaceState.ExternalFlow || isOverlayScreenshotCaptureInProgress) {
            pendingPanelRestoreAfterExternalFlow = false
        } else {
            minimizeChatPanelToBubble(immediate = true)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun disableBubbleFromDismissZone() {
        MediaPickerActivity.clearCallbacks()
        hideChatPanel(immediate = true)
        removeSafely(dismissZoneView, immediate = true)
        isDismissTargetActive = false

        serviceScope.launch {
            (application as UaiApplication).container.agentRepository.setBubbleEnabled(false)
            stopSelf()
        }
    }

    // ----- Attachment handling -----

    private fun clearAttachment() {
        pendingImages.clear()
        pendingFileName = null
        pendingFileText = null
    }

    private fun launchGalleryPicker() {
        MediaPickerActivity.clearCallbacks()
        val flowGeneration = suspendOverlaysForExternalFlow(reopenPanelOnReturn = true)
        MediaPickerActivity.onImageResult = { uri ->
            serviceScope.launch {
                if (uri != null) {
                    val (base64, bitmap) = encodeImageFromUri(uri)
                    if (base64 != null) {
                        val persistedUri = withContext(Dispatchers.IO) {
                            persistImageAttachment(applicationContext, base64)
                        } ?: uri.toString()
                        pendingImages.add(Triple(base64, bitmap, persistedUri))
                    }
                }
                restoreOverlaysAfterExternalFlow(
                    flowGeneration = flowGeneration,
                    forcePanelVisible = true
                )
            }
        }
        startMediaPickerActivity(MediaPickerActivity.ACTION_GALLERY)
    }

    private fun launchCameraCapture() {
        MediaPickerActivity.clearCallbacks()
        val flowGeneration = suspendOverlaysForExternalFlow(reopenPanelOnReturn = true)
        MediaPickerActivity.onBitmapResult = { bitmap ->
            serviceScope.launch(Dispatchers.IO) {
                if (bitmap != null) {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    val imgBitmap = bitmap.asImageBitmap()
                    val persistedUri = persistImageAttachment(applicationContext, base64)
                    withContext(Dispatchers.Main) {
                        pendingImages.add(Triple(base64, imgBitmap, persistedUri))
                    }
                }
                withContext(Dispatchers.Main) {
                    restoreOverlaysAfterExternalFlow(
                        flowGeneration = flowGeneration,
                        forcePanelVisible = true
                    )
                }
            }
        }
        startMediaPickerActivity(MediaPickerActivity.ACTION_CAMERA)
    }

    private fun launchFilePicker() {
        MediaPickerActivity.clearCallbacks()
        val flowGeneration = suspendOverlaysForExternalFlow(reopenPanelOnReturn = true)
        MediaPickerActivity.onFileResult = { uri ->
            if (uri != null) {
                serviceScope.launch {
                    // Process then always restore the panel
                    val mimeType = contentResolver.getType(uri) ?: ""
                    when {
                        mimeType.startsWith("image/") -> {
                            val (base64, bitmap) = encodeImageFromUri(uri)
                            if (base64 != null) {
                                // File-picked image replaces all existing attachments
                                pendingImages.clear()
                                pendingFileName = null
                                pendingFileText = null
                                val persistedUri = withContext(Dispatchers.IO) {
                                    persistImageAttachment(applicationContext, base64)
                                } ?: uri.toString()
                                pendingImages.add(Triple(base64, bitmap, persistedUri))
                            }
                        }
                        else -> {
                            when (val result = importFileAttachment(applicationContext, uri)) {
                                is FileAttachmentImportResult.Success -> {
                                    pendingImages.clear()
                                    pendingFileName = result.attachment.displayName
                                    pendingFileText = result.attachment.extractedText
                                }
                                is FileAttachmentImportResult.Unsupported -> {
                                    Toast.makeText(
                                        applicationContext,
                                        result.message,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                is FileAttachmentImportResult.Failure -> {
                                    Toast.makeText(
                                        applicationContext,
                                        result.message,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    restoreOverlaysAfterExternalFlow(
                        flowGeneration = flowGeneration,
                        forcePanelVisible = true
                    )
                }
            } else {
                restoreOverlaysAfterExternalFlow(
                    flowGeneration = flowGeneration,
                    forcePanelVisible = true
                )
            }
        }
        startMediaPickerActivity(MediaPickerActivity.ACTION_FILE)
    }

    private fun launchScreenshotCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchAccessibilityScreenshotCapture()
            return
        }
        launchMediaProjectionScreenshotCapture()
    }

    private fun launchAccessibilityScreenshotCapture() {
        if (isOverlayScreenshotCaptureInProgress || overlaySurfaceState == OverlaySurfaceState.ExternalFlow) return
        if (!MiniChatScreenshotAccessibilityService.isAvailable()) {
            val accessibilityHelperEnabled =
                MiniChatScreenshotAccessibilityService.isEnabled(this)
            showMiniChatScreenshotHint(
                getString(
                    if (accessibilityHelperEnabled) {
                        R.string.mini_chat_screenshot_accessibility_wait
                    } else {
                        R.string.mini_chat_screenshot_accessibility_hint
                    }
                )
            )
            return
        }

        isOverlayScreenshotCaptureInProgress = true
        android.util.Log.d("UAI_CAP", "accessibility screenshot requested")
        serviceScope.launch {
            // Let the screenshot button tap fully unwind before removing the panel window.
            delay(140L)
            if (!isOverlayScreenshotCaptureInProgress) return@launch

            val flowGeneration = suspendOverlaysForExternalFlow(reopenPanelOnReturn = true)
            android.util.Log.d("UAI_CAP", "overlay suspended for accessibility screenshot: flow=$flowGeneration")

            screenshotRestoreJob?.cancel()
            screenshotRestoreJob = serviceScope.launch {
                delay(6_000L)
                if (isOverlayScreenshotCaptureInProgress && flowGeneration == externalFlowGeneration) {
                    android.util.Log.w("UAI_CAP", "accessibility capture restore timeout; rebuilding overlay")
                    restoreOverlaysAfterExternalFlow(
                        flowGeneration = flowGeneration,
                        forcePanelVisible = true
                    )
                }
            }

            delay(180L)
            val started = MiniChatScreenshotAccessibilityService.requestScreenshot { outcome ->
                serviceScope.launch {
                    when (outcome) {
                        is AccessibilityScreenCaptureOutcome.Success -> {
                            android.util.Log.d("UAI_CAP", "accessibility screenshot success")
                            val persistedUri = withContext(Dispatchers.IO) {
                                persistImageAttachment(applicationContext, outcome.base64)
                            }
                            pendingImages.add(Triple(outcome.base64, outcome.bitmap, persistedUri))
                        }
                        is AccessibilityScreenCaptureOutcome.Error -> {
                            android.util.Log.e("UAI_CAP", "accessibility capture error: ${outcome.message}")
                            Toast.makeText(
                                applicationContext,
                                getString(R.string.mini_chat_screenshot_capture_error, outcome.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    restoreOverlaysAfterExternalFlow(
                        flowGeneration = flowGeneration,
                        forcePanelVisible = true
                    )
                }
            }

            if (!started) {
                val accessibilityHelperEnabled =
                    MiniChatScreenshotAccessibilityService.isEnabled(applicationContext)
                android.util.Log.e("UAI_CAP", "accessibility screenshot service unavailable at capture time")
                restoreOverlaysAfterExternalFlow(
                    flowGeneration = flowGeneration,
                    forcePanelVisible = true
                )
                showMiniChatScreenshotHint(
                    getString(
                        if (accessibilityHelperEnabled) {
                            R.string.mini_chat_screenshot_accessibility_wait
                        } else {
                            R.string.mini_chat_screenshot_accessibility_hint
                        }
                    )
                )
            }
        }
    }

    private fun launchMediaProjectionScreenshotCapture() {
        if (isOverlayScreenshotCaptureInProgress || overlaySurfaceState == OverlaySurfaceState.ExternalFlow) return
        isOverlayScreenshotCaptureInProgress = true

        val flowGeneration = suspendOverlaysForExternalFlow(reopenPanelOnReturn = true)

        screenshotRestoreJob?.cancel()
        screenshotRestoreJob = serviceScope.launch {
            delay(12_000L)
            if (isOverlayScreenshotCaptureInProgress && flowGeneration == externalFlowGeneration) {
                android.util.Log.w("UAI_CAP", "overlay capture restore timeout; rebuilding overlay")
                restoreOverlaysAfterExternalFlow(
                    flowGeneration = flowGeneration,
                    forcePanelVisible = true
                )
            }
        }

        val launchResult = runCatching {
            OverlayScreenCaptureActivity.start(applicationContext) { outcome ->
                serviceScope.launch {
                    if (outcome is OverlayScreenCaptureOutcome.Success) {
                        val persistedUri = withContext(Dispatchers.IO) {
                            persistImageAttachment(applicationContext, outcome.base64)
                        }
                        pendingImages.add(Triple(outcome.base64, outcome.bitmap, persistedUri))
                    } else if (outcome is OverlayScreenCaptureOutcome.Error) {
                        android.util.Log.e("UAI_CAP", "overlay capture error: ${outcome.message}")
                    }
                    restoreOverlaysAfterExternalFlow(
                        flowGeneration = flowGeneration,
                        forcePanelVisible = true
                    )
                }
            }
        }
        if (launchResult.isFailure) {
            serviceScope.launch {
                android.util.Log.e(
                    "UAI_CAP",
                    "failed to launch overlay capture activity",
                    launchResult.exceptionOrNull()
                )
                restoreOverlaysAfterExternalFlow(
                    flowGeneration = flowGeneration,
                    forcePanelVisible = true
                )
            }
        }
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
        currentConversationMessagesJob?.cancel()
        currentConversationMessagesJob = null
        inputText = ""
        isLoading = false
        currentConversationId = null
        prefersDraftConversation = true
        draftAgentId = null
        isOverlayScreenshotCaptureInProgress = false
        pendingPanelRestoreAfterExternalFlow = false
        removeSafely(dismissZoneView, immediate = true)
        isDismissTargetActive = false
        restoreChatPanelWindowState()
        chatMessages.clear()
        messageThumbnails.clear()
        clearAttachment()
    }

    private fun openInApp() {
        val convId = currentConversationId ?: return
        pendingPanelRestoreAfterExternalFlow = false
        pendingPanelShowAfterAppHidden = false
        forceHideOverlayWindows("open-in-app-prelaunch")
        overlaySurfaceState = OverlaySurfaceState.AppForegroundSuppressed
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.example.uai.OPEN_CONVERSATION"
            putExtra("conversationId", convId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        serviceScope.launch {
            delay(250L)
            forceHideOverlayWindows("open-in-app-postlaunch")
            overlaySurfaceState = OverlaySurfaceState.AppForegroundSuppressed
        }
    }

    private fun stopResponse() {
        streamingJob?.cancel()
        streamingJob = null
        isLoading = false
        onlineSearchStatusMessage = null
        currentSession?.markStopped()
    }

    private fun sendMessage(text: String) {
        val agent = selectedAgentForCurrentContext()
        val imageList = pendingImages.toList()
        val attachedFile = pendingFileText?.let {
            FileAttachmentContext(
                displayName = pendingFileName ?: "file",
                extractedText = it
            )
        }
        val fullText = text
        val titleHint = text.trim().ifBlank { pendingFileName.orEmpty() }

        if ((fullText.isBlank() && imageList.isEmpty() && attachedFile == null) || isLoading || agent == null) return

        // Clear attachment before starting the stream (don't wait for it)
        clearAttachment()

        val container = (application as UaiApplication).container

        streamingJob = serviceScope.launch {
            isLoading = true
            inputText = ""

            var convId: String? = null
            var assistantId: String? = null
            var accumulated = ""
            var streamingWriter: ThrottledStreamingMessageWriter? = null
            var session: AssistantStreamingSession? = null

            try {
                if (currentConversationId == null) {
                    val title = titleHint
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.take(60)
                        ?: fullText.trim().ifBlank {
                            when {
                                attachedFile != null -> attachedFile.displayName
                                imageList.isNotEmpty() -> "Image"
                                else -> "Chat"
                            }
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
                    prefersDraftConversation = false
                    switchConversation(conv.id, force = true)
                }
                convId = currentConversationId ?: return@launch
                val activeConversationId = convId
                val persistedImageUri = imageList.firstOrNull()?.third
                    ?: imageList.firstOrNull()?.first?.let {
                        withContext(Dispatchers.IO) { persistImageAttachment(applicationContext, it) }
                    }

                val userMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = activeConversationId,
                    role = "user",
                    content = fullText,
                    createdAt = System.currentTimeMillis(),
                    imageUri = persistedImageUri,
                    attachedFileName = attachedFile?.displayName,
                    attachedFileText = attachedFile?.extractedText
                )
                container.conversationRepository.insertMessage(userMsg)
                chatMessages.add(userMsg)
                // Store in-memory thumbnails so the message bubble can display them
                val thumbs = imageList.mapNotNull { it.second }
                if (thumbs.isNotEmpty()) messageThumbnails[userMsg.id] = thumbs

                val messageId = UUID.randomUUID().toString()
                assistantId = messageId
                val assistantMsg = MessageEntity(
                    id = messageId,
                    conversationId = activeConversationId,
                    role = "assistant",
                    content = "",
                    createdAt = System.currentTimeMillis(),
                    isStreaming = true,
                    agentId = agent.id,
                    agentName = agent.name
                )
                container.conversationRepository.insertMessage(assistantMsg)
                chatMessages.add(assistantMsg)
                session = AssistantStreamingSession(messageId)
                session!!.start(serviceScope)
                currentSession = session

                // If agent doesn't support vision, insert a capability notice instead of calling API
                if (imageList.isNotEmpty() && !agent.canHandleImageRequests()) {
                    val notice = "I don't support image analysis with \"${agent.model}\". " +
                            "Please switch to a vision-capable model in agent settings."
                    accumulated = notice  // must be non-blank so finally doesn't delete the message
                    val idx = chatMessages.indexOfFirst { it.id == messageId }
                    if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(content = notice, isStreaming = false)
                    container.conversationRepository.updateMessageContent(messageId, notice, false)
                    container.conversationRepository.touchConversation(activeConversationId)
                    return@launch
                }

                // Build history, attaching images to the last user message.
                val allHistory = chatMessages.filter { !it.isStreaming }
                val history = compressHistory(allHistory.mapIndexed { idx, msg ->
                    if (idx == allHistory.lastIndex && msg.role == "user") {
                        when {
                            imageList.isNotEmpty() -> msg.toChatMessage(
                                images = imageList.map { ImageAttachment(it.first) }
                            )
                            else -> msg.toChatMessage()
                        }
                    } else {
                        msg.toChatMessage()
                    }
                })
                val effectiveHistory = if (agent.hasInternetAccess) {
                    container.webGateway.prepareTurn(
                        conversationKey = activeConversationId,
                        messages = history,
                        planningConfig = agent
                    ) { status ->
                        onlineSearchStatusMessage = status
                    }.messages
                } else {
                    history
                }

                streamingWriter = ThrottledStreamingMessageWriter { content, isStreaming ->
                    container.conversationRepository.updateMessageContent(
                        messageId,
                        content,
                        isStreaming
                    )
                }

                val agent = container.resolveAgentConfig(agent)
                val responseStream = if (agent.hasInternetAccess) {
                    container.assistantRuntime.streamResponse(
                        conversationKey = activeConversationId,
                        messages = effectiveHistory,
                        config = agent,
                        onStatusChanged = { status -> onlineSearchStatusMessage = status }
                    )
                } else {
                    container.providerFactory(agent).streamResponse(effectiveHistory, agent)
                }
                responseStream
                    .catch { e -> if (currentCoroutineContext().isActive) emit(StreamChunk.Error(e)) }
                    .collect { chunk ->
                        val id = assistantId ?: return@collect
                        val idx = chatMessages.indexOfFirst { it.id == id }
                        when (chunk) {
                            is StreamChunk.Token -> {
                                accumulated += chunk.text
                                val sanitized = if (agent.hasInternetAccess) sanitizeGroundedAssistantResponse(accumulated) else accumulated
                                session?.onToken(sanitized)
                                streamingWriter?.emitStreaming(sanitized)
                            }
                            is StreamChunk.ModelSelection -> {
                                if (idx != -1) {
                                    chatMessages[idx] = chatMessages[idx].copy(
                                        responseModelId = chunk.modelId,
                                        responseModelIsFallback = chunk.viaFallback
                                    )
                                }
                                container.conversationRepository.updateMessageResponseModel(
                                    id,
                                    chunk.modelId,
                                    chunk.viaFallback
                                )
                            }
                            is StreamChunk.Done -> Unit
                            is StreamChunk.Error -> {
                                // Keep accumulated non-blank so finally() doesn't delete the message bubble.
                                // Do not surface raw HTTP errors into the message text.
                                if (accumulated.isBlank()) accumulated = " "
                            }
                        }
                    }
            } finally {
                withContext(NonCancellable) {
                    val id = assistantId
                    if (id != null) {
                        val idx = chatMessages.indexOfFirst { it.id == id }
                        if (accumulated.isBlank()) {
                            session?.markDeleted()
                            if (idx != -1) chatMessages.removeAt(idx)
                            container.conversationRepository.deleteMessage(id)
                        } else {
                            val sanitized = if (agent.hasInternetAccess) sanitizeGroundedAssistantResponse(accumulated) else accumulated
                            streamingWriter?.emitFinal(sanitized)
                            session?.finalize(sanitized)
                            if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(content = sanitized, isStreaming = false)
                        }
                        convId?.let { container.conversationRepository.touchConversation(it) }
                    }
                    currentSession = null
                    onlineSearchStatusMessage = null
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

    private fun detectBubbleLayoutMode(): BubbleLayoutMode {
        val width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            resources.displayMetrics.widthPixels
        }
        val height = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            getRealScreenHeight()
        }
        return if (width > height) BubbleLayoutMode.Wide else BubbleLayoutMode.Portrait
    }

    private fun currentBubbleBounds(): OverlayBubbleBounds {
        val dm = resources.displayMetrics
        val bubbleSize = (64 * dm.density).toInt()
        val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            dm.widthPixels
        }
        return calculateOverlayBubbleBounds(
            screenWidth = screenWidth,
            screenHeight = dm.heightPixels,
            realHeight = getRealScreenHeight(),
            statusBarHeight = getStatusBarHeight(),
            bubbleSize = bubbleSize
        )
    }

    private fun defaultBubblePositionForCurrentLayout(): Pair<Int, Int> {
        return defaultOverlayBubblePosition(currentBubbleBounds())
    }

    private suspend fun restoreBubblePositionForCurrentLayout(seedDefaultIfMissing: Boolean) {
        val prefs = (application as UaiApplication).container.preferences
        val saved = prefs.getBubblePosition(
            isWideMode = currentBubbleLayoutMode == BubbleLayoutMode.Wide
        )
        val target = saved ?: defaultBubblePositionForCurrentLayout()
        bubbleParams.x = target.first
        bubbleParams.y = target.second
        clampBubblePositionToDisplay(saveIfChanged = false)
        if (saved == null && seedDefaultIfMissing) {
            saveBubblePositionForMode(currentBubbleLayoutMode)
        }
    }

    private fun clampBubblePositionToDisplay(saveIfChanged: Boolean) {
        if (!::bubbleParams.isInitialized) return
        val (clampedX, clampedY) = clampOverlayBubblePosition(
            x = bubbleParams.x,
            y = bubbleParams.y,
            bounds = currentBubbleBounds()
        )
        val changed = clampedX != bubbleParams.x || clampedY != bubbleParams.y
        bubbleParams.x = clampedX
        bubbleParams.y = clampedY
        bubbleView?.takeIf { it.isAttachedToWindow }?.let { bubble ->
            runCatching { windowManager.updateViewLayout(bubble, bubbleParams) }
        }
        if (changed && saveIfChanged) {
            saveBubblePosition()
        }
    }

    private fun activateBubbleOpacity() {
        bubbleIdleJob?.cancel()
        bubbleIdleJob = null
        animateBubbleAlpha(BUBBLE_NORMAL_ALPHA)
    }

    private fun scheduleBubbleIdleFade() {
        bubbleIdleJob?.cancel()
        bubbleIdleJob = null
        if (overlaySurfaceState != OverlaySurfaceState.BubbleVisible || isAppUiVisible) return
        bubbleView?.takeIf { it.isAttachedToWindow } ?: return
        bubbleIdleJob = serviceScope.launch {
            delay(BUBBLE_IDLE_DELAY_MS)
            if (overlaySurfaceState == OverlaySurfaceState.BubbleVisible &&
                !isAppUiVisible &&
                bubbleView?.isAttachedToWindow == true
            ) {
                animateBubbleAlpha(BUBBLE_IDLE_ALPHA)
            }
        }
    }

    private fun animateBubbleAlpha(targetAlpha: Float) {
        if (!::bubbleParams.isInitialized) return
        val bubble = bubbleView?.takeIf { it.isAttachedToWindow } ?: run {
            bubbleParams.alpha = targetAlpha
            return
        }
        val startAlpha = bubbleParams.alpha
        if (abs(startAlpha - targetAlpha) < 0.01f) {
            bubbleParams.alpha = targetAlpha
            runCatching { windowManager.updateViewLayout(bubble, bubbleParams) }
            return
        }
        bubbleAlphaAnimator?.cancel()
        bubbleAlphaAnimator = ValueAnimator.ofFloat(startAlpha, targetAlpha).apply {
            duration = BUBBLE_ALPHA_ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                bubbleParams.alpha = animator.animatedValue as Float
                runCatching { windowManager.updateViewLayout(bubble, bubbleParams) }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    bubbleAlphaAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    bubbleAlphaAnimator = null
                }
            })
            start()
        }
    }

    private fun saveBubblePositionForMode(mode: BubbleLayoutMode) {
        val x = bubbleParams.x
        val y = bubbleParams.y
        serviceScope.launch {
            (application as UaiApplication).container.preferences
                .saveBubblePositionForMode(
                    x = x,
                    y = y,
                    isWideMode = mode == BubbleLayoutMode.Wide
                )
        }
    }

    private fun attachLifecycleOwners(view: View) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun removeSafely(view: View?, immediate: Boolean = false) {
        if (view != null && view.isAttachedToWindow) {
            runCatching {
                if (immediate) windowManager.removeViewImmediate(view) else windowManager.removeView(view)
            }.onFailure { throwable ->
                android.util.Log.e(
                    "UAI_OVERLAY",
                    "Failed to remove ${view.javaClass.simpleName} immediate=$immediate attached=${view.isAttachedToWindow}",
                    throwable
                )
            }
        }
    }

    private fun forceHideOverlayWindows(reason: String) {
        android.util.Log.d(
            "UAI_OVERLAY",
            "forceHideOverlayWindows reason=$reason panelAttached=${chatPanelContainer?.isAttachedToWindow == true} bubbleAttached=${bubbleView?.isAttachedToWindow == true} state=$overlaySurfaceState"
        )
        removeSafely(dismissZoneView, immediate = true)
        isDismissTargetActive = false
        clearChatPanelInteractionState()
        removeSafely(chatPanelContainer, immediate = true)
        chatPanelView?.disposeComposition()
        chatPanelContainer = null
        chatPanelView = null
        isChatPanelVisible = false
        isChatPanelAnimating = false
        hideBubbleWindow(immediate = true)
    }

    private fun nextPanelTransitionGeneration(): Long {
        panelTransitionGeneration += 1
        return panelTransitionGeneration
    }

    private fun saveBubblePosition() {
        val x = bubbleParams.x
        val y = bubbleParams.y
        serviceScope.launch {
            (application as UaiApplication).container.preferences
                .saveBubblePositionForMode(
                    x = x,
                    y = y,
                    isWideMode = currentBubbleLayoutMode == BubbleLayoutMode.Wide
                )
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
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
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
        private const val ACTION_OPEN_CHAT_PANEL = "com.example.uai.OPEN_CHAT_PANEL"
        private const val ACTION_OPEN_DRAFT_CHAT_PANEL = "com.example.uai.OPEN_DRAFT_CHAT_PANEL"
        private const val ACTION_SUPPRESS_FOR_FOREGROUND_APP = "com.example.uai.SUPPRESS_FOR_FOREGROUND_APP"
        private const val EXTRA_CONVERSATION_ID = "conversationId"
        private const val EXTRA_ASSISTANT_ID = "assistantId"
        private const val NOTIFICATION_ID = 1001
        private const val DISMISS_BOTTOM_PAD_DP = 48
        private const val DISMISS_RADIUS_DP = 30
        private const val DISMISS_HIT_DP = 60
        private const val BUBBLE_NORMAL_ALPHA = 0.82f
        private const val BUBBLE_IDLE_ALPHA = 0.55f
        private const val BUBBLE_IDLE_DELAY_MS = 3_000L
        private const val BUBBLE_ALPHA_ANIMATION_MS = 180L
        private val BUBBLE_WINDOW_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        fun startService(context: android.content.Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun openConversation(context: android.content.Context, conversationId: String) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_OPEN_CHAT_PANEL
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun openDraftConversation(
            context: android.content.Context,
            assistantId: String?
        ) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_OPEN_DRAFT_CHAT_PANEL
                putExtra(EXTRA_ASSISTANT_ID, assistantId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun suppressForForegroundApp(context: android.content.Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_SUPPRESS_FOR_FOREGROUND_APP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else
                context.startService(intent)
        }

        fun stopService(context: android.content.Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
