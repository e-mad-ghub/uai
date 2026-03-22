package com.mad.screenagent.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mad.screenagent.AppContainer
import com.mad.screenagent.FeatureFlags
import com.mad.screenagent.feature.agora.AgoraCreateScreen
import com.mad.screenagent.feature.agora.AgoraCreateViewModel
import com.mad.screenagent.feature.agora.AgoraDetailScreen
import com.mad.screenagent.feature.agora.AgoraDetailViewModel
import com.mad.screenagent.feature.agora.AgoraListScreen
import com.mad.screenagent.feature.agora.AgoraListViewModel
import com.mad.screenagent.feature.agents.AgentEditScreen
import com.mad.screenagent.feature.agents.AgentEditViewModel
import com.mad.screenagent.feature.agents.AgentsScreen
import com.mad.screenagent.feature.agents.AgentsViewModel
import com.mad.screenagent.feature.conversations.ConversationDetailScreen
import com.mad.screenagent.feature.conversations.ConversationDetailViewModel
import com.mad.screenagent.feature.settings.SettingsScreen
import com.mad.screenagent.feature.settings.SettingsViewModel
import java.util.UUID

@Composable
fun AppNavGraph(
    navController: NavHostController,
    openDrawer: () -> Unit,
    container: AppContainer
) {
    NavHost(
        navController = navController,
        startDestination = Routes.NEW_CONVERSATION
    ) {
        // New conversation — generates a fresh UUID; conversation is only saved to DB on first send
        composable(Routes.NEW_CONVERSATION) {
            val conversationId = remember { UUID.randomUUID().toString() }
            val vm: ConversationDetailViewModel = viewModel(
                key = conversationId,
                factory = ConversationDetailViewModel.Factory(
                    conversationId = conversationId,
                    repo = container.conversationRepository,
                    agentRepo = container.agentRepository,
                    assistantRuntime = container.assistantRuntime,
                    webGateway = container.webGateway,
                    providerFactory = container.providerFactory,
                    agentResolver = container::resolveAgentConfig
                )
            )
            ConversationDetailScreen(
                viewModel = vm,
                openDrawer = openDrawer,
                onOpenAssistants = { navController.navigate(Routes.AGENTS) { launchSingleTop = true } }
            )
        }

        composable(
            Routes.CONVERSATION_DETAIL,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStack ->
            val conversationId = backStack.arguments!!.getString("conversationId")!!
            val vm: ConversationDetailViewModel = viewModel(
                key = conversationId,
                factory = ConversationDetailViewModel.Factory(
                    conversationId = conversationId,
                    repo = container.conversationRepository,
                    agentRepo = container.agentRepository,
                    assistantRuntime = container.assistantRuntime,
                    webGateway = container.webGateway,
                    providerFactory = container.providerFactory,
                    agentResolver = container::resolveAgentConfig
                )
            )
            ConversationDetailScreen(
                viewModel = vm,
                openDrawer = openDrawer,
                onOpenAssistants = { navController.navigate(Routes.AGENTS) { launchSingleTop = true } }
            )
        }

        composable(Routes.AGENTS) {
            val vm: AgentsViewModel = viewModel(
                factory = AgentsViewModel.Factory(container.agentRepository)
            )
            AgentsScreen(
                viewModel = vm,
                onAddAgent = { navController.navigate(Routes.agentEdit()) },
                onEditAgent = { id -> navController.navigate(Routes.agentEdit(id)) },
                onDuplicateAgent = { id ->
                    navController.navigate(Routes.agentEdit(duplicateFromId = id))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.AGENT_EDIT,
            arguments = listOf(
                navArgument("agentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("duplicateFromId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStack ->
            val agentId = backStack.arguments?.getString("agentId")?.takeIf { it.isNotEmpty() }
            val duplicateFromId = backStack.arguments?.getString("duplicateFromId")
                ?.takeIf { it.isNotEmpty() }
            val vm: AgentEditViewModel = viewModel(
                factory = AgentEditViewModel.Factory(
                    container.agentRepository,
                    agentId,
                    duplicateFromId,
                    container.okHttpClient,
                    container.openRouterCatalogRepository,
                    container.providerModelCatalogRepository
                )
            )
            AgentEditScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(container.agentRepository)
            )
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        if (FeatureFlags.AGORA_ENABLED) {
            composable(Routes.AGORA_LIST) {
                val vm: AgoraListViewModel = viewModel(
                    factory = AgoraListViewModel.Factory(container.conversationRepository)
                )
                AgoraListScreen(
                    viewModel = vm,
                    onOpenRoom = { id -> navController.navigate(Routes.agoraDetail(id)) },
                    onCreateRoom = { navController.navigate(Routes.AGORA_CREATE) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.AGORA_CREATE) {
                val vm: AgoraCreateViewModel = viewModel(
                    factory = AgoraCreateViewModel.Factory(
                        container.conversationRepository,
                        container.agentRepository
                    )
                )
                AgoraCreateScreen(
                    viewModel = vm,
                    onCreated = { id ->
                        navController.navigate(Routes.agoraDetail(id)) {
                            // Always pop the create screen itself so back from the room
                            // never returns here regardless of how the user reached it.
                            popUpTo(Routes.AGORA_CREATE) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                Routes.AGORA_DETAIL,
                arguments = listOf(navArgument("agoraId") { type = NavType.StringType })
            ) { backStack ->
                val agoraId = backStack.arguments!!.getString("agoraId")!!
                val vm: AgoraDetailViewModel = viewModel(
                    key = agoraId,
                    factory = AgoraDetailViewModel.Factory(
                        conversationId = agoraId,
                        repo = container.conversationRepository,
                        agentRepo = container.agentRepository,
                        assistantRuntime = container.assistantRuntime,
                        webGateway = container.webGateway,
                        providerFactory = container.providerFactory,
                        agentResolver = container::resolveAgentConfig
                    )
                )
                AgoraDetailScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
