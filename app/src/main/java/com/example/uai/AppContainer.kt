package com.example.uai

import android.content.Context
import com.example.uai.data.db.AppDatabase
import com.example.uai.data.prefs.AppPreferences
import com.example.uai.shared.streaming.OpenRouterBestFreeRoutingStateStore
import com.example.uai.shared.streaming.BingHtmlSearchProvider
import com.example.uai.shared.streaming.BraveHtmlSearchProvider
import com.example.uai.shared.streaming.DomainRoutingSearchProvider
import com.example.uai.shared.streaming.DuckDuckGoHtmlSearchProvider
import com.example.uai.shared.streaming.DuckDuckGoLiteSearchProvider
import com.example.uai.shared.streaming.FallbackWebSearchProvider
import com.example.uai.shared.streaming.HackerNewsProvider
import com.example.uai.shared.streaming.MetaGerSearchProvider
import com.example.uai.shared.streaming.NewsRssProvider
import com.example.uai.shared.streaming.SearXSearchProvider
import com.example.uai.shared.streaming.SearchPlanningService
import com.example.uai.shared.streaming.ToolAwareAssistantRuntime
import com.example.uai.shared.streaming.WebGateway
import com.example.uai.shared.streaming.WebGatewaySearchToolExecutor
import com.example.uai.shared.streaming.WebGroundingService
import com.example.uai.shared.streaming.WikipediaProvider
import com.example.uai.shared.streaming.YandexSearchProvider
import com.example.uai.shared.streaming.AiProviderFactory
import com.example.uai.shared.streaming.fetchAndCacheSearXInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.model.MONEY_SAVER_MODEL
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

    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        containerScope.launch { fetchAndCacheSearXInstances(okHttpClient) }
        WebGroundingService(domainRouter, okHttpClient)
    }

    val providerFactory: (com.example.uai.data.model.AgentConfig) -> com.example.uai.shared.streaming.AiProvider = { config ->
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

    /**
     * Resolves the [MONEY_SAVER_MODEL] sentinel to the cheapest model available in the cached
     * catalog for the agent's provider. Falls back to the cheapest hardcoded default if the
     * catalog is empty or the provider is not OpenAI/Anthropic.
     */
    suspend fun resolveAgentConfig(agent: com.example.uai.data.model.AgentConfig): com.example.uai.data.model.AgentConfig {
        if (agent.model != MONEY_SAVER_MODEL) return agent
        val resolvedModel = when (agent.provider) {
            AiProviderType.OPENAI -> {
                val models = providerModelCatalogRepository.getCatalog(AiProviderType.OPENAI).models.map { it.id }
                pickCheapestOpenAiModel(models)
            }
            AiProviderType.ANTHROPIC -> {
                val models = providerModelCatalogRepository.getCatalog(AiProviderType.ANTHROPIC).models.map { it.id }
                pickCheapestAnthropicModel(models)
            }
            else -> return agent
        }
        return agent.copy(model = resolvedModel)
    }

    private fun pickCheapestAnthropicModel(catalog: List<String>): String =
        catalog.minByOrNull { id ->
            val n = id.lowercase()
            when {
                n.contains("haiku") -> 0
                n.contains("sonnet") -> 1
                n.contains("opus") -> 2
                else -> 3
            }
        } ?: "claude-haiku-4-5-20251001"

    private fun pickCheapestOpenAiModel(catalog: List<String>): String =
        catalog.minByOrNull { id ->
            val n = id.lowercase()
            when {
                n.contains("nano") -> 0
                n.contains("mini") -> 1
                n.contains("4o") -> 2
                n.contains("4.1") -> 3
                n.contains("gpt-5") -> 4
                else -> 5
            }
        } ?: "gpt-4o-mini"
}
