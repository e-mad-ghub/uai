package com.mad.screenagent.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Transparent trampoline activity that launches pickers and the MediaProjection consent dialog
 * on behalf of FloatingBubbleService (which cannot call startActivityForResult itself).
 *
 * For screenshot: only requests user consent and returns (resultCode, data) via onProjectionConsent.
 * The actual screen capture is performed in FloatingBubbleService so the cached MediaProjection
 * can be reused without showing the consent dialog again.
 */
class MediaPickerActivity : ComponentActivity() {

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        onImageResult?.invoke(uri)
        onImageResult = null
        finish()
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        onBitmapResult?.invoke(bitmap)
        onBitmapResult = null
        finish()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            Toast.makeText(
                this,
                "Camera permission is required to take a photo.",
                Toast.LENGTH_SHORT
            ).show()
            onBitmapResult?.invoke(null)
            onBitmapResult = null
            finish()
        }
    }

    private val fileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        onFileResult?.invoke(uri)
        onFileResult = null
        finish()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onProjectionConsent?.invoke(result.resultCode, result.data!!)
        } else {
            onProjectionConsent?.invoke(Activity.RESULT_CANCELED, Intent())
        }
        onProjectionConsent = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_GALLERY    -> galleryLauncher.launch("image/*")
            ACTION_CAMERA     -> {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    launchCameraCapture()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            ACTION_FILE       -> fileLauncher.launch("*/*")
            ACTION_SCREENSHOT -> {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
            else -> finish()
        }
    }

    private fun launchCameraCapture() {
        runCatching { cameraLauncher.launch(null) }
            .onFailure {
                Toast.makeText(
                    this,
                    "Unable to open the camera right now.",
                    Toast.LENGTH_SHORT
                ).show()
                onBitmapResult?.invoke(null)
                onBitmapResult = null
                finish()
            }
    }

    companion object {
        const val EXTRA_ACTION      = "action"
        const val ACTION_GALLERY    = "gallery"
        const val ACTION_CAMERA     = "camera"
        const val ACTION_FILE       = "file"
        const val ACTION_SCREENSHOT = "screenshot"

        @Volatile var onImageResult: ((Uri?) -> Unit)?                = null
        @Volatile var onBitmapResult: ((Bitmap?) -> Unit)?            = null
        @Volatile var onFileResult: ((Uri?) -> Unit)?                 = null
        /** Called with (resultCode, data) after the user accepts the screen-capture consent. */
        @Volatile var onProjectionConsent: ((Int, Intent) -> Unit)?   = null

        fun clearCallbacks() {
            onImageResult = null
            onBitmapResult = null
            onFileResult = null
            onProjectionConsent = null
        }
    }
}
