package com.example.uai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import com.example.uai.ui.chat.encodeBitmapForAttachment

sealed interface AccessibilityScreenCaptureOutcome {
    data class Success(val base64: String, val bitmap: ImageBitmap) : AccessibilityScreenCaptureOutcome
    data class Error(val message: String) : AccessibilityScreenCaptureOutcome
}

class MiniChatScreenshotAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshot(
        attempt: Int = 0,
        onResult: (AccessibilityScreenCaptureOutcome) -> Unit
    ) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                Thread {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    runCatching { buffer.close() }

                    val outcome = if (bitmap != null) {
                        val (base64, preview) = encodeBitmapForAttachment(bitmap)
                        AccessibilityScreenCaptureOutcome.Success(base64, preview)
                    } else {
                        AccessibilityScreenCaptureOutcome.Error("Could not prepare the captured screenshot.")
                    }

                    mainHandler.post { onResult(outcome) }
                }.start()
            }

            override fun onFailure(errorCode: Int) {
                if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt == 0) {
                    mainHandler.postDelayed(
                        { captureScreenshot(attempt = 1, onResult = onResult) },
                        RETRY_DELAY_MS
                    )
                    return
                }
                onResult(AccessibilityScreenCaptureOutcome.Error(messageForError(errorCode)))
            }
        })
    }

    companion object {
        private const val RETRY_DELAY_MS = 350L

        @Volatile
        private var instance: MiniChatScreenshotAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            if (enabledServices.isBlank()) return false

            val component = ComponentName(context, MiniChatScreenshotAccessibilityService::class.java)
            val flattened = component.flattenToString()
            val shortFlattened = component.flattenToShortString()

            return enabledServices
                .split(':')
                .any { serviceName ->
                    serviceName.equals(flattened, ignoreCase = true) ||
                        serviceName.equals(shortFlattened, ignoreCase = true)
                }
        }

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        fun requestScreenshot(
            onResult: (AccessibilityScreenCaptureOutcome) -> Unit
        ): Boolean {
            val service = instance
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || service == null) {
                return false
            }
            service.captureScreenshot(onResult = onResult)
            return true
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun messageForError(errorCode: Int): String {
            return when (errorCode) {
                ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
                    "Android could not capture the screen."
                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                    "Enable the SideAgent screenshot accessibility service to capture the screen."
                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                    "Please wait a moment and try the screenshot again."
                ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                    "This screen could not be captured."
                ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                    "Android blocked this screenshot because the current screen is secure."
                else ->
                    "Android could not capture the screen."
            }
        }
    }
}
