package com.mad.screenagent.shared.attachment

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun rememberCameraPermissionRequester(
    onGranted: () -> Unit,
    onDenied: () -> Unit = {}
): () -> Unit {
    val context = LocalContext.current
    val latestOnGranted = rememberUpdatedState(onGranted)
    val latestOnDenied = rememberUpdatedState(onDenied)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            latestOnGranted.value()
        } else {
            latestOnDenied.value()
        }
    }

    return remember(context, permissionLauncher) {
        {
            val permissionState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            )
            if (permissionState == PackageManager.PERMISSION_GRANTED) {
                latestOnGranted.value()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}
