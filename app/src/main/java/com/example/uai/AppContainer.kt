package com.example.uai

import android.content.Context
import com.example.uai.data.db.AppDatabase
import com.example.uai.data.prefs.AppPreferences
import com.example.uai.ai.OpenRouterBestFreeRoutingStateStore
import com.example.uai.ai.BingHtmlSearchProvider
import com.example.uai.ai.BraveHtmlSearchProvider
import com.example.uai.ai.DomainRoutingSearchProvider
import com.example.uai.ai.DuckDuckGoHtmlSearchProvider
import com.example.uai.ai.DuckDuckGoLiteSearchProvider
import com.example.uai.ai.FallbackWebSearchProvider
import com.example.uai.ai.HackerNewsProvider
import com.example.uai.ai.MetaGerSearchProvider
import com.example.uai.ai.NewsRssProvider
import com.example.uai.ai.SearXSearchProvider
import com.example.uai.ai.SearchPlanningService
import com.example.uai.ai.ToolAwareAssistantRuntime
import com.example.uai.ai.WebGateway
import com.example.uai.ai.WebGatewaySearchToolExecutor
import com.example.uai.ai.WebGroundingService
import com.example.uai.ai.WikipediaProvider
import com.example.uai.ai.YandexSearchProvider
import com.example.uai.ai.AiProviderFactory
import com.example.uai.ai.fetchAndCacheSearXInstances
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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

    val webGroundingService: WebGroundingService = run {
        // General search fallback chain: SearXNG → MetaGer → Yandex → Brave → DDG Lite → DDG → Bing
        val generalSearchProvider = FallbackWebSearchProvider(
            listOf(
                SearXSearchProvider(okHttpClient),
                MetaGerSearchProvider(okHttpClient),
                YandexSearchProvider(okHttpClient),
                BraveHtmlSearchProvider(okHttpClient),
                DuckDuckGoLiteSearchProvider(okHttpClient),
                DuckDuckGoHtmlSearchProvider(okHttpClient),
                BingHtmlSearchProvider(okHttpClient)
            )
        )
        val domainRouter = DomainRoutingSearchProvider(
            newsProvider = NewsRssProvider(okHttpClient),
            techProvider = HackerNewsProvider(okHttpClient),
            wikiProvider = WikipediaProvider(okHttpClient),
            generalProvider = generalSearchProvider
        )
        // Fetch fresh SearXNG instances in the background on startup
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch { fetchAndCacheSearXInstances(okHttpClient) }
        WebGroundingService(domainRouter, okHttpClient)
    }

    val providerFactory: (com.example.uai.data.model.AgentConfig) -> com.example.uai.ai.AiProvider = { config ->
        AiProviderFactory.create(
            config = config,
            client = okHttpClient,
            openRouterCatalogRepository = openRouterCatalogRepository,
            openRouterBestFreeRoutingStateStore = openRouterBestFreeRoutingStateStore
        )
    }

    val searchPlanningService: SearchPlanningService =
        SearchPlanningService(providerFactory)

    val webGateway: WebGateway =
        WebGateway(
            groundingService = webGroundingService,
            searchPlanningService = searchPlanningService
        )

    val assistantRuntime: ToolAwareAssistantRuntime =
        ToolAwareAssistantRuntime(
            providerFactory = providerFactory,
            searchToolExecutor = WebGatewaySearchToolExecutor(webGateway)
        )

    val conversationRepository: ConversationRepository =
        ConversationRepository(db.conversationDao(), db.messageDao(), context.applicationContext)

    val agentRepository: AgentRepository = AgentRepository(preferences)
}
