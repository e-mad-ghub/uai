package com.example.uai.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.example.uai.ui.chat.BubbleContent
import com.example.uai.ui.chat.ChatPanel
import com.example.uai.ui.theme.UaiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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

    private var bubbleView: ComposeView? = null
    private var chatPanelView: ComposeView? = null
    private var isChatPanelVisible = false

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var currentConversationId: String? = null

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

        // Observe active agent from DataStore
        container.agentRepository.activeAgentFlow
            .onEach { agent ->
                activeAgent = agent
                // Reset conversation when agent changes
                if (agent?.id != activeAgent?.id) {
                    currentConversationId = null
                    chatMessages.clear()
                }
            }
            .catch { /* ignore */ }
            .launchIn(serviceScope)

        // Restore bubble position
        serviceScope.launch {
            container.preferences.bubblePosFlow.collect { (x, y) ->
                bubbleParams.x = x
                bubbleParams.y = y
                bubbleView?.let { windowManager.updateViewLayout(it, bubbleParams) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleOwner.onDestroy()
        serviceScope.cancel()
        removeSafely(chatPanelView)
        removeSafely(bubbleView)
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
                UaiTheme {
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

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialRawX = event.rawX
                    initialRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialRawX
                    val dy = event.rawY - initialRawY
                    if (abs(dx) > 8 || abs(dy) > 8) isDragging = true
                    if (isDragging) {
                        bubbleParams.x = (initialX + dx).toInt()
                        bubbleParams.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(view, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggleChatPanel()
                    saveBubblePosition()
                    true
                }
                else -> false
            }
        }
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
                UaiTheme {
                    ChatPanel(
                        messages = chatMessages,
                        inputText = inputText,
                        isLoading = isLoading,
                        agentName = activeAgent?.name ?: "AI Agent",
                        onInputChange = { inputText = it },
                        onSend = ::sendMessage,
                        onClose = ::hideChatPanel
                    )
                }
            }
        }
    }

    private fun toggleChatPanel() {
        if (isChatPanelVisible) hideChatPanel() else showChatPanel()
    }

    private fun showChatPanel() {
        chatPanelView?.let {
            if (!it.isAttachedToWindow) {
                windowManager.addView(it, panelParams)
                isChatPanelVisible = true
            }
        }
    }

    private fun hideChatPanel() {
        removeSafely(chatPanelView)
        isChatPanelVisible = false
    }

    // ----- Message sending -----

    private fun sendMessage(text: String) {
        val agent = activeAgent
        if (text.isBlank() || isLoading || agent == null) return

        val container = (application as UaiApplication).container

        serviceScope.launch {
            isLoading = true
            inputText = ""

            // Ensure conversation exists
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

            // Insert user message
            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = "user",
                content = text,
                createdAt = System.currentTimeMillis()
            )
            container.conversationRepository.insertMessage(userMsg)
            chatMessages.add(userMsg)

            // Insert streaming placeholder for assistant
            val assistantId = UUID.randomUUID().toString()
            val assistantMsg = MessageEntity(
                id = assistantId,
                conversationId = convId,
                role = "assistant",
                content = "",
                createdAt = System.currentTimeMillis(),
                isStreaming = true
            )
            container.conversationRepository.insertMessage(assistantMsg)
            chatMessages.add(assistantMsg)

            // Build history (exclude current streaming placeholder)
            val history = chatMessages
                .filter { !it.isStreaming }
                .map { ChatMessage(it.role, it.content) }

            var accumulated = ""
            val provider = AiProviderFactory.create(agent, container.okHttpClient)

            provider.streamResponse(history, agent)
                .catch { e -> emit(StreamChunk.Error(e)) }
                .collect { chunk ->
                    val idx = chatMessages.indexOfFirst { it.id == assistantId }
                    when (chunk) {
                        is StreamChunk.Token -> {
                            accumulated += chunk.text
                            if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(content = accumulated)
                            container.conversationRepository.updateMessageContent(assistantId, accumulated, true)
                        }
                        is StreamChunk.Done -> {
                            if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(isStreaming = false)
                            container.conversationRepository.updateMessageContent(assistantId, accumulated, false)
                            container.conversationRepository.touchConversation(convId)
                        }
                        is StreamChunk.Error -> {
                            val errContent = "$accumulated\n[Error: ${chunk.cause.message}]"
                            if (idx != -1) chatMessages[idx] = chatMessages[idx].copy(content = errContent, isStreaming = false)
                            container.conversationRepository.updateMessageContent(assistantId, errContent, false)
                        }
                    }
                }

            isLoading = false
        }
    }

    // ----- Helpers -----

    private fun attachLifecycleOwners(view: ComposeView) {
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
