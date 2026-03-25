package com.mad.screenagent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mad.screenagent.data.db.ConversationEntity
import com.mad.screenagent.data.model.AppColorTheme
import com.mad.screenagent.feature.bubble.FloatingBubbleService
import com.mad.screenagent.design.components.BrandMarkBadge
import com.mad.screenagent.ui.navigation.AppNavGraph
import com.mad.screenagent.ui.navigation.Routes
import com.mad.screenagent.design.theme.UaiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Set via onCreate/onNewIntent; consumed once by LaunchedEffect to navigate
    private var pendingOpenConversationId by mutableStateOf<String?>(null)
    private var pendingOpenAgoraId by mutableStateOf<String?>(null)
    private var pendingOpenQuickActions by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as UaiApplication).container

        // Auto-start bubble service if it was enabled in a previous session
        lifecycleScope.launch {
            val enabled = container.preferences.bubbleEnabledFlow.first()
            if (enabled && Settings.canDrawOverlays(this@MainActivity)) {
                FloatingBubbleService.startService(this@MainActivity)
            }
        }

        intent?.let { handleBubbleIntent(it) }

        setContent {
            val colorTheme by container.agentRepository.colorThemeFlow
                .collectAsState(AppColorTheme.DEFAULT)

            UaiTheme(colorTheme = colorTheme) {
                val navController = rememberNavController()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                // Navigate to a specific conversation when requested by the bubble service
                LaunchedEffect(pendingOpenConversationId) {
                    pendingOpenConversationId?.let { convId ->
                        navController.navigate(Routes.conversationDetail(convId)) {
                            launchSingleTop = true
                        }
                        pendingOpenConversationId = null
                    }
                }
                if (FeatureFlags.AGORA_ENABLED) {
                    LaunchedEffect(pendingOpenAgoraId) {
                        pendingOpenAgoraId?.let { agoraId ->
                            navController.navigate(Routes.agoraDetail(agoraId)) {
                                launchSingleTop = true
                            }
                            pendingOpenAgoraId = null
                        }
                    }
                }

                val conversations by container.conversationRepository
                    .getAllConversations()
                    .collectAsStateWithLifecycle(emptyList())
                val activeAgent by container.agentRepository.activeAgentFlow
                    .collectAsState(null)
                val bubbleEnabled by container.agentRepository.bubbleEnabledFlow
                    .collectAsState(true)

                LaunchedEffect(pendingOpenQuickActions) {
                    if (pendingOpenQuickActions) {
                        navController.navigate(Routes.QUICK_ACTIONS) { launchSingleTop = true }
                        pendingOpenQuickActions = false
                    }
                }

                val currentEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentEntry?.destination?.route
                val currentOpenId = currentEntry?.arguments?.getString("conversationId")
                    ?: currentEntry?.arguments?.getString("agoraId")

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
                                BrandMarkBadge()
                                Spacer(Modifier.width(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        stringResource(R.string.drawer_workspace_subtitle),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // New Chat button
                            Button(
                                onClick = {
                                    closeDrawer()
                                    if (activeAgent != null) {
                                        navController.navigate(Routes.NEW_CONVERSATION) {
                                            popUpTo(Routes.NEW_CONVERSATION) { inclusive = true }
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

                            if (FeatureFlags.AGORA_ENABLED) {
                                Spacer(Modifier.height(6.dp))

                                // New Room button
                                OutlinedButton(
                                    onClick = {
                                        closeDrawer()
                                        navController.navigate(Routes.AGORA_CREATE) { launchSingleTop = true }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.Default.Groups, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.new_room))
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()

                            // Conversations list — all chats including Rooms
                            if (conversations.isNotEmpty()) {
                                Text(
                                    "Recent",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                                )
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    items(
                                        conversations.filter { FeatureFlags.AGORA_ENABLED || !it.isAgora },
                                        key = { it.id }
                                    ) { conv ->
                                        DrawerConversationItem(
                                            conv = conv,
                                            isSelected = currentOpenId == conv.id,
                                            isAgora = conv.isAgora,
                                            onClick = {
                                                closeDrawer()
                                                val route = if (conv.isAgora)
                                                    Routes.agoraDetail(conv.id)
                                                else
                                                    Routes.conversationDetail(conv.id)
                                                navController.navigate(route) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onPin = {
                                                scope.launch {
                                                    container.conversationRepository.upsertConversation(
                                                        conv.copy(isPinned = !conv.isPinned)
                                                    )
                                                }
                                            },
                                            onRename = { newTitle ->
                                                scope.launch {
                                                    container.conversationRepository.upsertConversation(
                                                        conv.copy(title = newTitle)
                                                    )
                                                }
                                            },
                                            onDelete = {
                                                scope.launch {
                                                    container.conversationRepository.deleteConversation(conv)
                                                    if (currentOpenId == conv.id) {
                                                        navController.navigate(Routes.NEW_CONVERSATION) {
                                                            popUpTo(0) { inclusive = true }
                                                        }
                                                    }
                                                }
                                            }
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
                                label = { Text(stringResource(R.string.feature_agents)) },
                                selected = currentRoute == Routes.AGENTS,
                                onClick = {
                                    closeDrawer()
                                    navController.navigate(Routes.AGENTS) { launchSingleTop = true }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            NavigationDrawerItem(
                                icon = {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = if (bubbleEnabled)
                                            androidx.compose.ui.graphics.Color.Unspecified
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                },
                                label = {
                                    Text(
                                        "Quick Actions",
                                        color = if (bubbleEnabled)
                                            androidx.compose.ui.graphics.Color.Unspecified
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                },
                                selected = currentRoute == Routes.QUICK_ACTIONS,
                                onClick = {
                                    if (!bubbleEnabled) {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Enable the floating bubble to configure quick actions.",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        closeDrawer()
                                        navController.navigate(Routes.QUICK_ACTIONS) { launchSingleTop = true }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text(stringResource(R.string.settings_title)) },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleBubbleIntent(intent)
    }

    private fun handleBubbleIntent(intent: Intent) {
        when (intent.action) {
            "com.mad.screenagent.OPEN_CONVERSATION" -> {
                FloatingBubbleService.suppressForForegroundApp(this)
                intent.getStringExtra("conversationId")?.let { pendingOpenConversationId = it }
            }
            FloatingBubbleService.ACTION_OPEN_QUICK_ACTIONS_SETTINGS -> {
                pendingOpenQuickActions = true
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerConversationItem(
    conv: ConversationEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    isAgora: Boolean = false
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(conv.id) { mutableStateOf(conv.title) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                ),
            shape = RoundedCornerShape(28.dp),
            color = if (isSelected)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (conv.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = conv.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isAgora) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            stringResource(R.string.feature_room),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (conv.isPinned) "Unpin" else "Pin") },
                leadingIcon = {
                    Icon(
                        if (conv.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = null
                    )
                },
                onClick = { showMenu = false; onPin() }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    showMenu = false
                    renameText = conv.title
                    showRenameDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = { showMenu = false; onDelete() }
            )
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    if (renameText.isNotBlank()) onRename(renameText.trim())
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}
