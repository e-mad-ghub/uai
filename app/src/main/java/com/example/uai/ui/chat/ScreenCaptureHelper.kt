package com.example.uai.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream

/**
 * Shared screen capture implementation used by both the floating bubble service
 * and the in-app screens (ConversationDetailScreen, AgoraDetailScreen).
 *
 * Must be called on the main thread. [onComplete] is invoked on the main thread with a
 * base64-encoded JPEG string and a preview [ImageBitmap], or null if capture fails / times out.
 * The caller is responsible for stopping [projection] after [onComplete] fires.
 */
fun performScreenCapture(
    projection: MediaProjection,
    widthPx: Int,
    heightPx: Int,
    densityDpi: Int,
    onComplete: (Pair<String, ImageBitmap>?) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    val imageReader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 2)
    var virtualDisplay: VirtualDisplay? = null
    var finished = false

    fun finish(result: Pair<String, ImageBitmap>?) {
        if (finished) return
        finished = true
        virtualDisplay?.release()
        runCatching { imageReader.close() }
        onComplete(result)
    }

    val timeoutRunnable = Runnable { finish(null) }
    mainHandler.postDelayed(timeoutRunnable, 5000L)

    var imageAcquired = false
    imageReader.setOnImageAvailableListener({ reader ->
        if (imageAcquired) return@setOnImageAvailableListener
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        image ?: return@setOnImageAvailableListener
        imageAcquired = true
        mainHandler.removeCallbacks(timeoutRunnable)
        Thread {
            var result: Pair<String, ImageBitmap>? = null
            try {
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * widthPx
                val raw = Bitmap.createBitmap(
                    widthPx + rowPadding / plane.pixelStride, heightPx, Bitmap.Config.ARGB_8888
                )
                raw.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(raw, 0, 0, widthPx, heightPx)
                raw.recycle()

                val scale = maxOf(1, maxOf(widthPx, heightPx) / 1024)
                val scaled = if (scale > 1) {
                    Bitmap.createScaledBitmap(cropped, widthPx / scale, heightPx / scale, true)
                        .also { if (it !== cropped) cropped.recycle() }
                } else cropped

                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                result = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to scaled.asImageBitmap()
            } catch (e: Exception) {
                android.util.Log.e("ScreenCapture", "capture error: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                image.close()
                val r = result
                mainHandler.post { finish(r) }
            }
        }.start()
    }, mainHandler)

    virtualDisplay = projection.createVirtualDisplay(
        "ScreenCapture", widthPx, heightPx, densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        imageReader.surface, null, null
    )
}

@Composable
fun rememberScreenCaptureLauncher(
    onCaptured: (String, ImageBitmap) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val currentContext by rememberUpdatedState(context)
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnError by rememberUpdatedState(onError)
    var cachedProjection by remember { mutableStateOf<MediaProjection?>(null) }

    fun captureNow(projection: MediaProjection) {
        val dm = currentContext.resources.displayMetrics
        performScreenCapture(
            projection = projection,
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
            densityDpi = dm.densityDpi
        ) { result ->
            if (result != null) {
                val (base64, bitmap) = result
                currentOnCaptured(base64, bitmap)
            } else {
                currentOnError("Could not capture the screen.")
            }
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            currentOnError("Screen capture was cancelled.")
            return@rememberLauncherForActivityResult
        }

        val manager = currentContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
        if (manager == null) {
            currentOnError("Screen capture is unavailable on this device.")
            return@rememberLauncherForActivityResult
        }

        val projection = runCatching {
            manager.getMediaProjection(result.resultCode, result.data!!)
        }.getOrNull()

        if (projection == null) {
            currentOnError("Could not start screen capture.")
            return@rememberLauncherForActivityResult
        }

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cachedProjection = null
            }
        }, Handler(Looper.getMainLooper()))

        cachedProjection = projection
        captureNow(projection)
    }

    DisposableEffect(Unit) {
        onDispose {
            cachedProjection?.stop()
            cachedProjection = null
        }
    }

    return remember(consentLauncher) {
        {
            val projection = cachedProjection
            if (projection != null) {
                captureNow(projection)
            } else if (currentContext.findActivity() == null) {
                currentOnError("Screen capture is unavailable here.")
            } else {
                val manager = currentContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as? MediaProjectionManager
                if (manager == null) {
                    currentOnError("Screen capture is unavailable on this device.")
                } else {
                    consentLauncher.launch(manager.createScreenCaptureIntent())
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
