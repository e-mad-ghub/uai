package com.example.uai

import android.content.Context
import com.example.uai.data.db.AppDatabase
import com.example.uai.data.prefs.AppPreferences
import com.example.uai.ai.OpenRouterBestFreeRoutingStateStore
import com.example.uai.ai.BraveHtmlSearchProvider
import com.example.uai.ai.DuckDuckGoHtmlSearchProvider
import com.example.uai.ai.FallbackWebSearchProvider
import com.example.uai.ai.SearchPlanningService
import com.example.uai.ai.ToolAwareAssistantRuntime
import com.example.uai.ai.WebGateway
import com.example.uai.ai.WebGatewaySearchToolExecutor
import com.example.uai.ai.WebGroundingService
import com.example.uai.ai.AiProviderFactory
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

    val openRouterBestFreeRoutingStateStore: OpenRouterBestFreeRoutingStateStore =
        OpenRouterBestFreeRoutingStateStore()

    val providerModelCatalogRepository: ProviderModelCatalogRepository =
        ProviderModelCatalogRepository(preferences, okHttpClient)

    val webGroundingService: WebGroundingService =
        WebGroundingService(
            FallbackWebSearchProvider(
                listOf(
                    BraveHtmlSearchProvider(okHttpClient),
                    DuckDuckGoHtmlSearchProvider(okHttpClient)
                )
            ),
            okHttpClient
        )

    private val aiProviderFactory: (com.example.uai.data.model.AgentConfig) -> com.example.uai.ai.AiProvider = { config ->
        AiProviderFactory.create(
            config = config,
            client = okHttpClient,
            openRouterCatalogRepository = openRouterCatalogRepository,
            openRouterBestFreeRoutingStateStore = openRouterBestFreeRoutingStateStore
        )
    }

    val searchPlanningService: SearchPlanningService =
        SearchPlanningService(aiProviderFactory)

    val webGateway: WebGateway =
        WebGateway(
            groundingService = webGroundingService,
            searchPlanningService = searchPlanningService
        )

    val assistantRuntime: ToolAwareAssistantRuntime =
        ToolAwareAssistantRuntime(
            providerFactory = aiProviderFactory,
            searchToolExecutor = WebGatewaySearchToolExecutor(webGateway)
        )

    val conversationRepository: ConversationRepository =
        ConversationRepository(db.conversationDao(), db.messageDao(), context.applicationContext)

    val agentRepository: AgentRepository = AgentRepository(preferences)
}
