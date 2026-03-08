package com.example.uai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class UaiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BUBBLE_CHANNEL_ID,
                "AI Chat Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the persistent AI chat bubble overlay"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val BUBBLE_CHANNEL_ID = "uai_bubble_channel"
    }
}
