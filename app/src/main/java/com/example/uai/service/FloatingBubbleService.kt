package com.example.uai.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import com.example.uai.ai.AiProviderFactory
import com.example.uai.ai.ChatMessage
import com.example.uai.ai.StreamChunk
import com.example.uai.data.db.ConversationEntity
import com.example.uai.data.db.MessageEntity
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AppColorTheme
import com.example.uai.ui.chat.BubbleContent
import com.example.uai.ui.chat.ChatPanel
import com.example.uai.ui.theme.UaiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // Switching agents mid-session keeps the current conversation — just swaps the AI.
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
                        // First emission after service start — restore latest conversation
                        restoreLatestConversation(agent)
                    }
                    // When switching agents mid-session: keep chatMessages + conversationId
                }
                activeAgent = agent
            }
            .catch { }
            .launchIn(serviceScope)

        serviceScope.launch {
            val (x, y) = container.preferences.bubblePosFlow.first()
            bubbleParams.x = x
            bubbleParams.y = y
            bubbleView?.let { windowManager.updateViewLayout(it, bubbleParams) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleOwner.onDestroy()
        serviceScope.cancel()
        removeSafely(chatPanelContainer)
        removeSafely(dismissZoneView)
        removeSafely(bubbleView)
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        }

        bubbleView = ComposeView(this).apply {
            attachLifecycleOwners(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                UaiTheme(colorTheme = colorTheme) {
                    BubbleContent(isLoading = isLoading)
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
                        toggleChatPanel()
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
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
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
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            dimAmount = 0.4f
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            windowAnimations = android.R.style.Animation_InputMethod
        }

        chatPanelView = ComposeView(this).apply {
            attachLifecycleOwners(this)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                UaiTheme(colorTheme = colorTheme) {
                    ChatPanel(
                        messages = chatMessages,
                        inputText = inputText,
                        isLoading = isLoading,
                        agentName = activeAgent?.name ?: "AI Agent",
                        agents = allAgents,
                        onInputChange = { inputText = it },
                        onSend = ::sendMessage,
                        onStop = ::stopResponse,
                        onClose = ::hideChatPanel,
                        onAgentSelect = { agent ->
                            serviceScope.launch {
                                (application as UaiApplication).container
                                    .agentRepository.setActiveAgent(agent.id)
                            }
                        },
                        onNewConversation = ::startNewConversation
                    )
                }
            }
        }

        // Wrap in a FrameLayout that intercepts BACK to close the panel
        chatPanelContainer = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    hideChatPanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.also { container ->
            // Lifecycle owners must be on the root view added to WindowManager
            attachLifecycleOwners(container)
            container.addView(
                chatPanelView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun toggleChatPanel() {
        if (isChatPanelVisible) hideChatPanel() else showChatPanel()
    }

    private fun showChatPanel() {
        chatPanelContainer?.let {
            if (!it.isAttachedToWindow) {
                windowManager.addView(it, panelParams)
                isChatPanelVisible = true
            }
        }
    }

    private fun hideChatPanel() {
        removeSafely(chatPanelContainer)
        isChatPanelVisible = false
    }

    // ----- Message sending -----

    private fun startNewConversation() {
        streamingJob?.cancel()
        streamingJob = null
        isLoading = false
        currentConversationId = null
        chatMessages.clear()
    }

    private fun stopResponse() {
        streamingJob?.cancel()
        streamingJob = null
        isLoading = false
    }

    private fun sendMessage(text: String) {
        val agent = activeAgent
        if (text.isBlank() || isLoading || agent == null) return

        val container = (application as UaiApplication).container

        streamingJob = serviceScope.launch {
            isLoading = true
            inputText = ""

            var assistantId: String? = null
            var accumulated = ""

            try {
                if (currentConversationId == null) {
                    val conv = ConversationEntity(
                        id = UUID.randomUUID().toString(),
                        title = text.take(60),
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
                    content = text,
                    createdAt = System.currentTimeMillis()
                )
                container.conversationRepository.insertMessage(userMsg)
                chatMessages.add(userMsg)

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

                val history = chatMessages
                    .filter { !it.isStreaming }
                    .map { ChatMessage(it.role, it.content) }

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
                                val errContent = "$accumulated\n[Error: ${chunk.cause.message}]"
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val DISMISS_BOTTOM_PAD_DP = 48
        private const val DISMISS_RADIUS_DP = 30
        private const val DISMISS_HIT_DP = 60

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
    }
}
