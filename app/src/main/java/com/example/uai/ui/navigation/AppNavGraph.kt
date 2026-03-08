package com.example.uai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.uai.AppContainer
import com.example.uai.data.db.ConversationEntity
import com.example.uai.ui.agents.AgentEditScreen
import com.example.uai.ui.agents.AgentEditViewModel
import com.example.uai.ui.agents.AgentsScreen
import com.example.uai.ui.agents.AgentsViewModel
import com.example.uai.ui.conversations.ConversationDetailScreen
import com.example.uai.ui.conversations.ConversationDetailViewModel
import com.example.uai.ui.conversations.ConversationsScreen
import com.example.uai.ui.conversations.ConversationsViewModel
import com.example.uai.ui.settings.SettingsScreen
import com.example.uai.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun AppNavGraph(
    navController: NavHostController,
    openDrawer: () -> Unit,
    container: AppContainer
) {
    NavHost(
        navController = navController,
        startDestination = Routes.CONVERSATIONS
    ) {
        composable(Routes.CONVERSATIONS) {
            val vm: ConversationsViewModel = viewModel(
                factory = ConversationsViewModel.Factory(container.conversationRepository)
            )
            val activeAgent by container.agentRepository.activeAgentFlow.collectAsState(null)
            val scope = rememberCoroutineScope()

            ConversationsScreen(
                viewModel = vm,
                openDrawer = openDrawer,
                onConversationClick = { id ->
                    navController.navigate(Routes.conversationDetail(id))
                },
                onNewConversation = {
                    val agent = activeAgent
                    if (agent != null) {
                        scope.launch {
                            val conv = ConversationEntity(
                                id = UUID.randomUUID().toString(),
                                title = "New conversation",
                                agentId = agent.id,
                                agentName = agent.name,
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                            container.conversationRepository.upsertConversation(conv)
                            navController.navigate(Routes.conversationDetail(conv.id))
                        }
                    } else {
                        navController.navigate(Routes.AGENTS)
                    }
                }
            )
        }

        composable(
            Routes.CONVERSATION_DETAIL,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { backStack ->
            val conversationId = backStack.arguments!!.getString("conversationId")!!
            val vm: ConversationDetailViewModel = viewModel(
                factory = ConversationDetailViewModel.Factory(
                    conversationId = conversationId,
                    repo = container.conversationRepository,
                    agentRepo = container.agentRepository,
                    httpClient = container.okHttpClient
                )
            )
            val activeAgent by container.agentRepository.activeAgentFlow.collectAsState(null)
            ConversationDetailScreen(
                viewModel = vm,
                activeAgent = activeAgent,
                openDrawer = openDrawer,
                onBack = { navController.popBackStack() }
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
                }
            )
        ) { backStack ->
            val agentId = backStack.arguments?.getString("agentId")?.takeIf { it.isNotEmpty() }
            val vm: AgentEditViewModel = viewModel(
                factory = AgentEditViewModel.Factory(container.agentRepository, agentId, container.okHttpClient)
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
    }
}
