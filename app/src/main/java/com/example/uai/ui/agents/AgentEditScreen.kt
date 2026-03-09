package com.example.uai.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
    viewModel: AgentEditViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val openRouterModels by viewModel.openRouterModels.collectAsStateWithLifecycle()
    val freeModelIds by viewModel.freeModelIds.collectAsStateWithLifecycle()
    val isLoadingModels by viewModel.isLoadingModels.collectAsStateWithLifecycle()
    val connectionTestState by viewModel.connectionTestState.collectAsStateWithLifecycle()
    var showApiKey by remember { mutableStateOf(false) }

    LaunchedEffect(isSaved) {
        if (isSaved) onBack()
    }

    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.agent.value.name == "New Agent") "New Agent" else "Edit Agent") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Agent name
            OutlinedTextField(
                value = agent.name,
                onValueChange = { viewModel.update { copy(name = it) } },
                label = { Text("Agent name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Provider selector
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProviderType.entries.forEach { type ->
                    FilterChip(
                        selected = agent.provider == type,
                        onClick = {
                            val defaultModel = AgentConfig.defaultModels[type]?.first() ?: ""
                            viewModel.update { copy(provider = type, model = defaultModel) }
                        },
                        label = { Text(type.displayName) }
                    )
                }
            }

            OutlinedTextField(
                value = agent.apiKey,
                onValueChange = { viewModel.update { copy(apiKey = it) } },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showApiKey = !showApiKey }) {
                        Text(if (showApiKey) "Hide" else "Show")
                    }
                },
                singleLine = true
            )

            // Model
            ModelSelector(
                provider = agent.provider,
                selectedModel = agent.model,
                onModelChange = { viewModel.update { copy(model = it) } },
                fetchedOpenRouterModels = openRouterModels,
                freeModelIds = freeModelIds,
                isLoadingModels = isLoadingModels
            )

            // Capability badges
            if (agent.supportsVision || agent.supportsDocuments) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (agent.supportsVision) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Images", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                    if (agent.supportsDocuments) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Documents", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            // Test connection
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = connectionTestState !is ConnectionTestState.Testing
                ) {
                    if (connectionTestState is ConnectionTestState.Testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Testing…")
                    } else {
                        Text("Test connection")
                    }
                }
                when (val state = connectionTestState) {
                    is ConnectionTestState.Success -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    is ConnectionTestState.Failure -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    else -> {}
                }
            }

            // System prompt
            OutlinedTextField(
                value = agent.systemPrompt,
                onValueChange = { viewModel.update { copy(systemPrompt = it) } },
                label = { Text("System prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6
            )

            // Temperature
            Column {
                Text(
                    "Temperature: ${"%.1f".format(agent.temperature)}",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = agent.temperature,
                    onValueChange = { viewModel.update { copy(temperature = it) } },
                    valueRange = 0f..2f,
                    steps = 19
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Precise", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Creative", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text("Save Agent")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    provider: AiProviderType,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    fetchedOpenRouterModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet(),
    isLoadingModels: Boolean = false
) {
    val presets = if (provider == AiProviderType.OPENROUTER && fetchedOpenRouterModels.isNotEmpty())
        fetchedOpenRouterModels
    else
        AgentConfig.defaultModels[provider] ?: emptyList()
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = onModelChange,
            label = {
                Text(
                    if (provider == AiProviderType.OPENROUTER && isLoadingModels)
                        "Model (loading…)"
                    else
                        "Model"
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (provider == AiProviderType.OPENROUTER && isLoadingModels) {
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Fetching models from OpenRouter…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {},
                    enabled = false
                )
                HorizontalDivider()
            }
            presets.forEach { model ->
                val config = AgentConfig(provider = provider, model = model)
                val isFree = provider == AiProviderType.OPENROUTER &&
                    (freeModelIds.contains(model) || model.endsWith(":free"))
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(model, modifier = Modifier.weight(1f))
                            if (isFree) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("Free", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                            if (config.supportsVision) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = "Supports images",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (config.supportsDocuments) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = "Supports documents",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    onClick = { onModelChange(model); expanded = false }
                )
            }
        }
    }
}
