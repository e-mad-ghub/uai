package com.example.uai.ui.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.model.isOpenRouterFreeModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
    viewModel: AgentEditViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val openRouterCatalogEntries by viewModel.openRouterCatalogEntries.collectAsStateWithLifecycle()
    val freeModelIds by viewModel.freeModelIds.collectAsStateWithLifecycle()
    val providerModels by viewModel.providerModels.collectAsStateWithLifecycle()
    val isLoadingModels by viewModel.isLoadingModels.collectAsStateWithLifecycle()
    val connectionTestState by viewModel.connectionTestState.collectAsStateWithLifecycle()
    val providerInfo = remember(agent.provider) { providerUiInfo(agent.provider) }
    val uriHandler = LocalUriHandler.current
    val recommendedModels = remember(agent.provider, openRouterCatalogEntries, providerModels, freeModelIds, agent.model) {
        recommendedModelChoices(
            provider = agent.provider,
            openRouterCatalogEntries = openRouterCatalogEntries,
            fetchedProviderModels = providerModels,
            freeModelIds = freeModelIds,
            currentModel = agent.model
        )
    }
    val selectedModelChoice = recommendedModels.firstOrNull { it.id == agent.model }
        ?: recommendedModels.first()
    val canSave = remember(agent.name, agent.apiKey, agent.model) {
        agent.name.isNotBlank() && agent.model.isNotBlank()
    }

    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable(viewModel.isEditing) {
        mutableStateOf(viewModel.isEditing)
    }

    LaunchedEffect(isSaved) {
        if (isSaved) onBack()
    }

    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(if (viewModel.isEditing) "Edit Assistant" else "Create Assistant")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.save(setActiveAfterSave = !viewModel.isEditing)
                        },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (viewModel.isEditing) "Save changes" else "Save and use assistant")
                    }
                    Text(
                        if (viewModel.isEditing)
                            "Changes apply to future chats with this assistant."
                        else
                            "You can always add more assistants later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard {
                Row(
                    modifier = Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (viewModel.isEditing) "Refine how this assistant behaves"
                            else "Start with a simple setup",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (viewModel.isEditing)
                                "Keep the basics easy to understand. Advanced instructions are still available below."
                            else
                                "Choose your provider, connect it, and SideAgent will use this assistant in new chats.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SetupSection(
                title = "Basic setup",
                description = "This is all most customers need."
            ) {
                OutlinedTextField(
                    value = agent.name,
                    onValueChange = { viewModel.update { copy(name = it) } },
                    label = { Text("Assistant name") },
                    placeholder = { Text("General Assistant") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    "Provider",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    assistantProviderOrder().forEach { type ->
                        FilterChip(
                            selected = agent.provider == type,
                            onClick = {
                                val defaultModel = defaultRecommendedModelId(
                                    provider = type,
                                    openRouterCatalogEntries = openRouterCatalogEntries,
                                    fetchedProviderModels = if (type == agent.provider) providerModels else emptyList(),
                                    freeModelIds = freeModelIds
                                )
                                viewModel.update { copy(provider = type, model = defaultModel) }
                            },
                            label = { Text(type.displayName) }
                        )
                    }
                }
                ProviderInfoCard(info = providerInfo)

                OutlinedTextField(
                    value = agent.apiKey,
                    onValueChange = { viewModel.update { copy(apiKey = it) } },
                    label = { Text("API key") },
                    placeholder = { Text(providerInfo.apiKeyPlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text(providerInfo.apiKeyHint)
                    },
                    trailingIcon = {
                        TextButton(onClick = { showApiKey = !showApiKey }) {
                            Text(if (showApiKey) "Hide" else "Show")
                        }
                    },
                    singleLine = true
                )
                ApiKeyHelpCallout(
                    info = providerInfo,
                    onOpenLink = { uriHandler.openUri(providerInfo.apiKeyActionUrl) }
                )

                RecommendedModelSelector(
                    selectedModel = selectedModelChoice,
                    choices = recommendedModels,
                    onModelChange = { modelId ->
                        viewModel.update { copy(model = modelId) }
                    }
                )
                ProviderCatalogStatusNote(
                    provider = agent.provider,
                    hasProviderCatalog = providerModels.isNotEmpty(),
                    isLoadingModels = isLoadingModels,
                    hasApiKey = agent.apiKey.isNotBlank()
                )

                CapabilityRow(agent = agent)

                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = agent.apiKey.isNotBlank() &&
                        agent.model.isNotBlank() &&
                        connectionTestState !is ConnectionTestState.Testing
                ) {
                    if (connectionTestState is ConnectionTestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Testing availability…")
                    } else {
                        Text("Test availability")
                    }
                }

                ConnectionStateBanner(connectionTestState = connectionTestState)
            }

            SetupSection(
                title = "Advanced settings",
                description = "Only open this when you want to fine-tune model behavior."
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Custom instructions and model tuning",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "Use a raw model ID, add detailed guidance, or tune creativity.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { advancedExpanded = !advancedExpanded }) {
                                Icon(
                                    imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (advancedExpanded) "Collapse advanced settings" else "Expand advanced settings"
                                )
                            }
                        }
                        AnimatedVisibility(visible = advancedExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                HorizontalDivider()
                                RawModelSelector(
                                    provider = agent.provider,
                                    selectedModel = agent.model,
                                    onModelChange = { viewModel.update { copy(model = it) } },
                                    fetchedProviderModels = providerModels,
                                    freeModelIds = freeModelIds,
                                    isLoadingModels = isLoadingModels
                                )
                                OutlinedTextField(
                                    value = agent.systemPrompt,
                                    onValueChange = { viewModel.update { copy(systemPrompt = it) } },
                                    label = { Text("System prompt") },
                                    supportingText = {
                                        Text("Use this to shape tone, role, and answer style.")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 4,
                                    maxLines = 8
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Creativity: ${"%.1f".format(agent.temperature)}",
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
                                        Text(
                                            "Precise",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "Creative",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                content()
            }
        )
    }
}

@Composable
private fun ProviderInfoCard(info: ProviderUiInfo) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info.label, style = MaterialTheme.typography.labelLarge)
                Text(
                    info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ApiKeyHelpCallout(
    info: ProviderUiInfo,
    onOpenLink: () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.secondaryContainer
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = info.apiKeyCalloutTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor
                )
                Text(
                    text = info.apiKeyCalloutBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
                TextButton(
                    onClick = onOpenLink,
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor)
                ) {
                    Text(info.apiKeyActionLabel)
                }
            }
        }
    }
}

@Composable
private fun ProviderCatalogStatusNote(
    provider: AiProviderType,
    hasProviderCatalog: Boolean,
    isLoadingModels: Boolean,
    hasApiKey: Boolean
) {
    val (title, body) = when {
        hasProviderCatalog -> Pair(
            "Latest ${provider.displayName} catalog loaded",
            "Recommended and custom model choices are using the latest catalog SideAgent has loaded for ${provider.displayName}."
        )
        isLoadingModels -> Pair(
            "Loading latest ${provider.displayName} catalog…",
            "SideAgent is updating the available model list for this provider."
        )
        provider == AiProviderType.OPENROUTER -> Pair(
            "Showing fallback list",
            "OpenRouter's live catalog is not available right now, so SideAgent is using its built-in fallback list."
        )
        hasApiKey -> Pair(
            "Showing starter list",
            "SideAgent could not load the latest ${provider.displayName} catalog right now, so the editor is using its starter list."
        )
        else -> Pair(
            "Showing starter list",
            "Enter an API key and test availability to load the latest ${provider.displayName} catalog."
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CapabilityRow(agent: AgentConfig) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        assistantCapabilities(agent).forEach { capability ->
            AssistChip(
                onClick = {},
                label = { Text(capability) }
            )
        }
    }
}

@Composable
private fun ConnectionStateBanner(connectionTestState: ConnectionTestState) {
    when (connectionTestState) {
        is ConnectionTestState.Success -> {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        connectionTestState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        is ConnectionTestState.Failure -> {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    connectionTestState.message,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        else -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecommendedModelSelector(
    selectedModel: RecommendedModelChoice,
    choices: List<RecommendedModelChoice>,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedModel.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Recommended model") },
            supportingText = {
                Text(
                    buildString {
                        if (selectedModel.isRecommended) {
                            append("Recommended. ")
                        }
                        append(selectedModel.description)
                    }
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(choice.label)
                                if (choice.isRecommended) {
                                    Text(
                                        "Recommended",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                if (choice.isFree) {
                                    Text(
                                        "Free",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                choice.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onModelChange(choice.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawModelSelector(
    provider: AiProviderType,
    selectedModel: String,
    onModelChange: (String) -> Unit,
    fetchedProviderModels: List<String> = emptyList(),
    freeModelIds: Set<String> = emptySet(),
    isLoadingModels: Boolean = false
) {
    val presets = if (fetchedProviderModels.isNotEmpty())
        fetchedProviderModels
    else
        AgentConfig.defaultModels[provider] ?: emptyList()
    var expanded by remember(provider, selectedModel) { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = onModelChange,
            label = {
                Text(
                    if (isLoadingModels)
                        "Custom model ID (loading…)"
                    else
                        "Custom model ID"
                )
            },
            supportingText = {
                Text("Use this only if you want a specific raw model name.")
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isLoadingModels) {
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Fetching models from ${provider.displayName}…",
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
                    isOpenRouterFreeModel(model, freeModelIds)
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(model)
                                if (isFree) {
                                    Text(
                                        "Free",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                buildString {
                                    append(
                                        when {
                                            config.canHandleImageRequests() && config.supportsDocuments -> "Images and documents"
                                            config.canHandleImageRequests() -> "Images"
                                            config.supportsDocuments -> "Documents"
                                            else -> "Text chat"
                                        }
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = { onModelChange(model); expanded = false }
                )
            }
        }
    }
}
