package com.mad.screenagent.data.repository

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mad.screenagent.R
import com.mad.screenagent.UaiApplication
import com.mad.screenagent.data.model.OnDeviceDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class OnDeviceModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return Result.failure()
        val container = (applicationContext as? UaiApplication)?.container ?: return Result.failure()
        val repository = container.onDeviceModelRepository
        val catalogEntry = repository.catalogFlow.first()
            .models
            .firstOrNull { it.id == modelId }
            ?: return Result.failure()

        setForeground(createForegroundInfo(
            modelName = catalogEntry.displayName,
            state = OnDeviceDownloadState.DOWNLOADING,
            downloadedBytes = 0L,
            totalBytes = catalogEntry.estimatedSizeMb.toLong() * 1024L * 1024L
        ))

        return try {
            repository.downloadModel(modelId) { state, downloadedBytes, totalBytes, _ ->
                setProgress(
                    workDataOf(
                        KEY_MODEL_ID to modelId as Any,
                        KEY_STATE to state.name as Any,
                        KEY_DOWNLOADED_BYTES to downloadedBytes as Any,
                        KEY_TOTAL_BYTES to totalBytes as Any
                    )
                )
                setForeground(
                    createForegroundInfo(
                        modelName = catalogEntry.displayName,
                        state = state,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes
                    )
                )
            }
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure()
        }
    }

    private fun createForegroundInfo(
        modelName: String,
        state: OnDeviceDownloadState,
        downloadedBytes: Long,
        totalBytes: Long
    ): ForegroundInfo {
        val progressText = when {
            totalBytes > 0L -> "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
            downloadedBytes > 0L -> formatBytes(downloadedBytes)
            else -> "Starting…"
        }
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            UaiApplication.DOWNLOAD_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_screenagent_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText("$modelName · ${state.displayLabel()} · $progressText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$modelName · ${state.displayLabel()} · $progressText"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(
                100,
                if (totalBytes > 0L) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0,
                totalBytes <= 0L
            )
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun OnDeviceDownloadState.displayLabel(): String = when (this) {
        OnDeviceDownloadState.DOWNLOADING -> "Downloading"
        OnDeviceDownloadState.VALIDATING -> "Validating"
        OnDeviceDownloadState.READY -> "Ready"
        OnDeviceDownloadState.DOWNLOADED -> "Ready"
        OnDeviceDownloadState.FAILED -> "Failed"
        OnDeviceDownloadState.CANCELLED -> "Cancelled"
        OnDeviceDownloadState.UNAVAILABLE -> "Unavailable"
        OnDeviceDownloadState.NOT_DOWNLOADED -> "Queued"
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) {
            "${value.toLong()} ${units[index]}"
        } else {
            String.format(java.util.Locale.US, "%.1f %s", value, units[index])
        }
    }

    companion object {
        const val KEY_MODEL_ID = "on_device_model_id"
        const val KEY_STATE = "on_device_download_state"
        const val KEY_DOWNLOADED_BYTES = "on_device_downloaded_bytes"
        const val KEY_TOTAL_BYTES = "on_device_total_bytes"
        private const val NOTIFICATION_ID = 41027
    }
}
