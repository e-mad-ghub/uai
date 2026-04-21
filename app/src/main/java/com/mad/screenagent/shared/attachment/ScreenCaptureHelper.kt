package com.mad.screenagent.shared.attachment

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap

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
                result = encodeBitmapForAttachment(cropped)
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
