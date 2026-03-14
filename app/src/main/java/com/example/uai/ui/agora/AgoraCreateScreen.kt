package com.example.uai.ui.agora

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.R
import com.example.uai.ui.components.ProductHeroCard
import com.example.uai.ui.components.ProductTopBarTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgoraCreateScreen(
    viewModel: AgoraCreateViewModel,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val agents by viewModel.agents.collectAsStateWithLifecycle()
    val roomName by viewModel.roomName.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedAgentIds.collectAsStateWithLifecycle()
    val createdId by viewModel.createdId.collectAsStateWithLifecycle()
    val canCreate by viewModel.canCreate.collectAsStateWithLifecycle()

    LaunchedEffect(createdId) {
        createdId?.let { onCreated(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    ProductTopBarTitle(
                        title = stringResource(R.string.new_room),
                        subtitle = stringResource(R.string.screen_new_council_subtitle)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ProductHeroCard(
                    eyebrow = stringResource(R.string.feature_rooms),
                    title = stringResource(R.string.new_room),
                    body = stringResource(R.string.agora_hero_body)
                )
            }
            item {
                OutlinedTextField(
                    value = roomName,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.room_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            item {
                Text(
                    "Select agents (at least 2)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (agents.isEmpty()) {
                item {
                    Text(
                        "No agents configured. Go to Agents to add some first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                items(agents, key = { it.id }) { agent ->
                    val checked = agent.id in selectedIds
                    Surface(
                        onClick = { viewModel.toggleAgent(agent.id) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (checked)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.toggleAgent(agent.id) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(agent.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${agent.provider.displayName} · ${agent.model}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::create,
                    enabled = canCreate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.create_room))
                }
                if (selectedIds.size in 1..1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Select at least one more agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
