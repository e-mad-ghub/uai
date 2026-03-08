package com.example.uai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.uai.data.db.ConversationEntity
import com.example.uai.ui.navigation.AppNavGraph
import com.example.uai.ui.navigation.Routes
import com.example.uai.ui.theme.UaiTheme
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as UaiApplication).container

        setContent {
            UaiTheme {
                val navController = rememberNavController()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                val conversations by container.conversationRepository
                    .getAllConversations()
                    .collectAsStateWithLifecycle(emptyList())
                val activeAgent by container.agentRepository.activeAgentFlow
                    .collectAsState(null)

                val currentEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentEntry?.destination?.route

                fun openDrawer() = scope.launch { drawerState.open() }
                fun closeDrawer() = scope.launch { drawerState.close() }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            // Header
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("UAI", style = MaterialTheme.typography.titleLarge)
                            }

                            // New Chat button
                            Button(
                                onClick = {
                                    closeDrawer()
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
                                            navController.navigate(Routes.conversationDetail(conv.id)) {
                                                launchSingleTop = true
                                            }
                                        }
                                    } else {
                                        navController.navigate(Routes.AGENTS) { launchSingleTop = true }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("New Chat")
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()

                            // Conversations list
                            if (conversations.isNotEmpty()) {
                                Text(
                                    "Recent",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                                )
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    items(conversations, key = { it.id }) { conv ->
                                        NavigationDrawerItem(
                                            label = {
                                                Text(
                                                    conv.title,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            selected = currentRoute?.contains(conv.id) == true,
                                            onClick = {
                                                closeDrawer()
                                                navController.navigate(Routes.conversationDetail(conv.id)) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }

                            HorizontalDivider()
                            Spacer(Modifier.height(4.dp))

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                                label = { Text("Agents") },
                                selected = currentRoute == Routes.AGENTS,
                                onClick = {
                                    closeDrawer()
                                    navController.navigate(Routes.AGENTS) { launchSingleTop = true }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") },
                                selected = currentRoute == Routes.SETTINGS,
                                onClick = {
                                    closeDrawer()
                                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Spacer(Modifier.height(12.dp))
                        }
                    }
                ) {
                    AppNavGraph(
                        navController = navController,
                        openDrawer = { openDrawer() },
                        container = container
                    )
                }
            }
        }
    }
}
