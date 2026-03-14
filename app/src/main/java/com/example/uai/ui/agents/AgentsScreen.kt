package com.example.uai.ui.agents

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.R
import com.example.uai.data.model.AgentConfig
import com.example.uai.ui.components.ProductEmptyStateCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    viewModel: AgentsViewModel,
    onAddAgent: () -> Unit,
    onEditAgent: (String) -> Unit,
    onDuplicateAgent: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_agents)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAgent) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.assistants_add_assistant)
                )
            }
        }
    ) { padding ->
        if (uiState.agents.isEmpty()) {
            AssistantsEmptyState(
                onAddAgent = onAddAgent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ElevatedCard {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                stringResource(R.string.assistants_choose_default_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.assistants_choose_default_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(uiState.agents, key = { it.id }) { agent ->
                    AgentItem(
                        agent = agent,
                        isActive = agent.id == uiState.activeAgentId,
                        onSetActive = { viewModel.setActiveAgent(agent.id) },
                        onEdit = { onEditAgent(agent.id) },
                        onDuplicate = { onDuplicateAgent(agent.id) },
                        replacementDefaultName = if (agent.id == uiState.activeAgentId) {
                            uiState.agents.firstOrNull { it.id != agent.id }?.name
                        } else {
                            null
                        },
                        onDelete = { viewModel.deleteAgent(agent) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantsEmptyState(
    onAddAgent: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        ProductEmptyStateCard(
            title = stringResource(R.string.assistants_empty_title),
            body = stringResource(R.string.assistants_empty_body),
            actionLabel = stringResource(R.string.assistants_empty_cta),
            onAction = onAddAgent
        )
    }
}

@Composable
private fun AgentItem(
    agent: AgentConfig,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    replacementDefaultName: String?,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            agent.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isActive) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        stringResource(R.string.assistants_default_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        assistantSummary(agent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${agent.provider.displayName} · ${agent.model}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.assistants_actions)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.assistants_edit)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.assistants_duplicate)) },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            }
                        )
                        if (!isActive) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.assistants_use_as_default)) },
                                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onSetActive()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.assistants_delete)) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                assistantCapabilities(agent).forEach { capability ->
                    CapabilityBadge(label = capability)
                }
            }

            if (isActive) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        stringResource(R.string.assistants_default_body),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            } else {
                TextButton(onClick = onSetActive) {
                    Text(stringResource(R.string.assistants_use_as_default_button))
                }
            }
        }
    }

    if (showDeleteDialog) {
        val deleteMessage = when {
            isActive && replacementDefaultName != null -> stringResource(
                R.string.assistants_delete_dialog_default_reassigned,
                agent.name,
                replacementDefaultName
            )
            isActive -> stringResource(
                R.string.assistants_delete_dialog_default_removed,
                agent.name
            )
            else -> stringResource(R.string.assistants_delete_dialog_body, agent.name)
        }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.assistants_delete_dialog_title)) },
            text = { Text(deleteMessage) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text(stringResource(R.string.assistants_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.assistants_cancel))
                }
            }
        )
    }
}

@Composable
private fun CapabilityBadge(label: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
