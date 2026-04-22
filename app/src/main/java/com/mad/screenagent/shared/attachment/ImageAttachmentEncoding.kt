package com.mad.screenagent.shared.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

private const val MAX_IMAGE_ATTACHMENT_SIDE_PX = 2048
private const val IMAGE_ATTACHMENT_JPEG_QUALITY = 90
private const val CAMERA_CAPTURE_DIR = "camera_captures"

fun createCameraCaptureUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, CAMERA_CAPTURE_DIR).apply { mkdirs() }
    val file = File(dir, "camera_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
}.getOrNull()

fun encodeBitmapForAttachment(bitmap: Bitmap): Pair<String, ImageBitmap> {
    val maxSide = maxOf(bitmap.width, bitmap.height)
    val scaled = if (maxSide > MAX_IMAGE_ATTACHMENT_SIDE_PX) {
        val ratio = MAX_IMAGE_ATTACHMENT_SIDE_PX.toFloat() / maxSide
        Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * ratio).toInt()),
            maxOf(1, (bitmap.height * ratio).toInt()),
            true
        ).also {
            if (it !== bitmap) bitmap.recycle()
        }
    } else {
        bitmap
    }

    return encodeBitmapAsJpeg(scaled)
}

fun encodeImageUriForAttachment(
    context: Context,
    uri: Uri
): Pair<String?, ImageBitmap?> = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

    val sampleSize = calculateImageDecodeSampleSize(
        width = bounds.outWidth,
        height = bounds.outHeight,
        maxSidePx = MAX_IMAGE_ATTACHMENT_SIDE_PX
    )
    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, decodeOptions)
    } ?: return null to null

    val (base64, preview) = encodeBitmapForAttachment(bitmap)
    base64 to preview
}.getOrElse { null to null }

internal fun calculateImageDecodeSampleSize(
    width: Int,
    height: Int,
    maxSidePx: Int
): Int {
    if (width <= 0 || height <= 0 || maxSidePx <= 0) return 1
    val maxDimension = maxOf(width, height)
    return ((maxDimension + maxSidePx - 1) / maxSidePx).coerceAtLeast(1)
}

private fun encodeBitmapAsJpeg(bitmap: Bitmap): Pair<String, ImageBitmap> {
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_ATTACHMENT_JPEG_QUALITY, out)
    return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP) to
        bitmap.asImageBitmap()
}
