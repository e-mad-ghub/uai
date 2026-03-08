package com.example.uai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.uai.AppContainer
import com.example.uai.ui.agents.AgentEditScreen
import com.example.uai.ui.agents.AgentEditViewModel
import com.example.uai.ui.agents.AgentsScreen
import com.example.uai.ui.agents.AgentsViewModel
import com.example.uai.ui.conversations.ConversationDetailScreen
import com.example.uai.ui.conversations.ConversationDetailViewModel
import com.example.uai.ui.settings.SettingsScreen
import com.example.uai.ui.settings.SettingsViewModel
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
                    httpClient = container.okHttpClient
                )
            )
            ConversationDetailScreen(
                viewModel = vm,
                openDrawer = openDrawer
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
            ConversationDetailScreen(
                viewModel = vm,
                openDrawer = openDrawer
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
