package com.example.uai

import android.content.Context
import com.example.uai.data.db.AppDatabase
import com.example.uai.data.prefs.AppPreferences
import com.example.uai.data.repository.AgentRepository
import com.example.uai.data.repository.ConversationRepository
import com.example.uai.data.repository.OpenRouterCatalogRepository
import com.example.uai.data.repository.ProviderModelCatalogRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection container. Both MainActivity and FloatingBubbleService
 * resolve all singletons from here via (application as UaiApplication).container.
 */
class AppContainer(context: Context) {

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val db: AppDatabase = AppDatabase.getInstance(context)

    val preferences: AppPreferences = AppPreferences(context)

    val openRouterCatalogRepository: OpenRouterCatalogRepository =
        OpenRouterCatalogRepository(preferences, okHttpClient)

    val providerModelCatalogRepository: ProviderModelCatalogRepository =
        ProviderModelCatalogRepository(preferences, okHttpClient)

    val conversationRepository: ConversationRepository =
        ConversationRepository(db.conversationDao(), db.messageDao())

    val agentRepository: AgentRepository = AgentRepository(preferences)
}
