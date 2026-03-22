package com.example.uai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.ImageBitmap
import com.example.uai.shared.attachment.performScreenCapture
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

sealed interface OverlayScreenCaptureOutcome {
    data class Success(val base64: String, val bitmap: ImageBitmap) : OverlayScreenCaptureOutcome
    data object Cancelled : OverlayScreenCaptureOutcome
    data class Error(val message: String) : OverlayScreenCaptureOutcome
}

class OverlayScreenCaptureActivity : ComponentActivity() {

    private var deliveredResult = false

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            deliverAndFinish(OverlayScreenCaptureOutcome.Error("Screen capture is unavailable on this device."))
            return@registerForActivityResult
        }

        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            deliverAndFinish(OverlayScreenCaptureOutcome.Cancelled)
            return@registerForActivityResult
        }

        val projection = runCatching {
            manager.getMediaProjection(result.resultCode, result.data!!)
        }.getOrNull()

        if (projection == null) {
            deliverAndFinish(OverlayScreenCaptureOutcome.Error("Could not start screen capture."))
            return@registerForActivityResult
        }

        registerProjection(projection)
        captureNow(projection)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId == null || requestId != pendingRequest.get()?.requestId) {
            finishSilently()
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager == null) {
            deliverAndFinish(OverlayScreenCaptureOutcome.Error("Screen capture is unavailable on this device."))
            return
        }

        consentLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun registerProjection(projection: MediaProjection) {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // No-op. Each projection instance is single-use on API 34+ and is stopped
                // explicitly after capture completes.
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun captureNow(projection: MediaProjection) {
        val dm = resources.displayMetrics
        window.decorView.postDelayed({
            performScreenCapture(
                projection = projection,
                widthPx = dm.widthPixels,
                heightPx = dm.heightPixels,
                densityDpi = dm.densityDpi
            ) { result ->
                if (result != null) {
                    val (base64, bitmap) = result
                    deliverAndFinish(OverlayScreenCaptureOutcome.Success(base64, bitmap))
                } else {
                    deliverAndFinish(OverlayScreenCaptureOutcome.Error("Could not capture the screen."))
                }
                runCatching { projection.stop() }
            }
        }, CAPTURE_DELAY_MS)
    }

    private fun deliverAndFinish(outcome: OverlayScreenCaptureOutcome) {
        if (deliveredResult) return
        deliveredResult = true

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        // Atomically claim the pending request only if it still matches this activity's requestId.
        val pending = pendingRequest.get()
        val callback = pending?.takeIf { it.requestId == requestId }?.onResult
        clearPendingRequest()
        callback?.invoke(outcome)
        finishSilently()
    }

    private fun finishSilently() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "requestId"
        private const val CAPTURE_DELAY_MS = 250L

        private data class PendingRequest(
            val requestId: String,
            val onResult: (OverlayScreenCaptureOutcome) -> Unit
        )

        private val pendingRequest = AtomicReference<PendingRequest?>()

        fun start(context: Context, onResult: (OverlayScreenCaptureOutcome) -> Unit) {
            val requestId = UUID.randomUUID().toString()
            // Write both fields atomically — no window where requestId and callback can mismatch.
            pendingRequest.set(PendingRequest(requestId, onResult))

            val intent = Intent(context, OverlayScreenCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            context.startActivity(intent)
        }

        fun clearPendingRequest() {
            pendingRequest.set(null)
        }
    }
}
