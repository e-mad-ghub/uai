package com.mad.screenagent

import android.app.Application
import com.mad.screenagent.data.db.AppDatabase
import com.mad.screenagent.data.prefs.AppPreferences
import com.mad.screenagent.shared.streaming.OpenRouterBestFreeRoutingStateStore
import com.mad.screenagent.shared.streaming.BingHtmlSearchProvider
import com.mad.screenagent.shared.streaming.BraveHtmlSearchProvider
import com.mad.screenagent.shared.streaming.DomainRoutingSearchProvider
import com.mad.screenagent.shared.streaming.DuckDuckGoHtmlSearchProvider
import com.mad.screenagent.shared.streaming.DuckDuckGoLiteSearchProvider
import com.mad.screenagent.shared.streaming.FallbackWebSearchProvider
import com.mad.screenagent.shared.streaming.HackerNewsProvider
import com.mad.screenagent.shared.streaming.MetaGerSearchProvider
import com.mad.screenagent.shared.streaming.NewsRssProvider
import com.mad.screenagent.shared.streaming.SearXSearchProvider
import com.mad.screenagent.shared.streaming.SearchPlanningService
import com.mad.screenagent.shared.streaming.ToolAwareAssistantRuntime
import com.mad.screenagent.shared.streaming.WebGateway
import com.mad.screenagent.shared.streaming.WebGatewaySearchToolExecutor
import com.mad.screenagent.shared.streaming.WebGroundingService
import com.mad.screenagent.shared.streaming.WikipediaProvider
import com.mad.screenagent.shared.streaming.YandexSearchProvider
import com.mad.screenagent.shared.streaming.AiProviderFactory
import com.mad.screenagent.shared.streaming.fetchAndCacheSearXInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.MONEY_SAVER_MODEL
import com.mad.screenagent.data.repository.AgentRepository
import com.mad.screenagent.data.repository.ConversationRepository
import com.mad.screenagent.data.repository.OpenRouterCatalogRepository
import com.mad.screenagent.data.repository.ProviderModelCatalogRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection container. Both MainActivity and FloatingBubbleService
 * resolve all singletons from here via (application as UaiApplication).container.
 */
class AppContainer(private val application: Application) {

    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val db: AppDatabase = AppDatabase.getInstance(application)

    val preferences: AppPreferences = AppPreferences(application)

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

    val providerFactory: (com.mad.screenagent.data.model.AgentConfig) -> com.mad.screenagent.shared.streaming.AiProvider = { config ->
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
        ConversationRepository(db.conversationDao(), db.messageDao(), application)

    val agentRepository: AgentRepository = AgentRepository(preferences)

    /**
     * Resolves the [MONEY_SAVER_MODEL] sentinel to the cheapest model available in the cached
     * catalog for the agent's provider. Falls back to the cheapest hardcoded default if the
     * catalog is empty or the provider is not OpenAI/Anthropic.
     */
    suspend fun resolveAgentConfig(agent: com.mad.screenagent.data.model.AgentConfig): com.mad.screenagent.data.model.AgentConfig {
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
