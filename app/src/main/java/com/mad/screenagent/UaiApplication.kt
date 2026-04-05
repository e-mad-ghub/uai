package com.mad.screenagent

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.os.Build
import com.mad.screenagent.data.model.AiProviderType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class UaiApplication : Application() {

    lateinit var container: AppContainer
        private set
    private val _isAppUiVisible = MutableStateFlow(false)
    val isAppUiVisible: StateFlow<Boolean> = _isAppUiVisible.asStateFlow()
    private var startedActivityCount = 0
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
        applicationScope.launch {
            container.preferences.initializeMiniChatDefaultIfNeeded()
        }
        applicationScope.launch {
            container.openRouterCatalogRepository.refreshCatalogIfStale()
            val agents = container.agentRepository.agentsFlow.first()
            val apiKeysByProvider = agents
                .filter { it.apiKey.isNotBlank() }
                .associateBy { it.provider }

            apiKeysByProvider[AiProviderType.OPENAI]?.let { agent ->
                container.providerModelCatalogRepository.refreshCatalogIfStale(
                    provider = AiProviderType.OPENAI,
                    apiKey = agent.apiKey
                )
            }
            apiKeysByProvider[AiProviderType.ANTHROPIC]?.let { agent ->
                container.providerModelCatalogRepository.refreshCatalogIfStale(
                    provider = AiProviderType.ANTHROPIC,
                    apiKey = agent.apiKey
                )
            }
        }
        createNotificationChannel()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                _isAppUiVisible.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                _isAppUiVisible.value = startedActivityCount > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                _isAppUiVisible.value = true
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bubbleChannel = NotificationChannel(
                BUBBLE_CHANNEL_ID,
                getString(R.string.bubble_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.bubble_channel_description)
            }
            val downloadChannel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_channel_description)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(bubbleChannel)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(downloadChannel)
        }
    }

    companion object {
        const val BUBBLE_CHANNEL_ID = "uai_bubble_channel"
        const val DOWNLOAD_CHANNEL_ID = "uai_download_channel"
    }
}
