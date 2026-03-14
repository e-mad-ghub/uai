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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.res.stringResource
import com.example.uai.R
import com.example.uai.data.model.AgentConfig
import com.example.uai.data.model.AiProviderType
import com.example.uai.data.model.CustomProviderPreset
import com.example.uai.data.model.canHandleImageRequests
import com.example.uai.data.model.isOpenRouterFreeModel
import com.example.uai.data.model.normalizeOpenAiCompatibleBaseUrl
import com.example.uai.ui.components.ProductPill
import com.example.uai.ui.components.ProductScreenIntro
import com.example.uai.ui.components.ProductTopBarTitle

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
    val nameValidationMessage by viewModel.nameValidationMessage.collectAsStateWithLifecycle()
    val apiKeyValidationMessage by viewModel.apiKeyValidationMessage.collectAsStateWithLifecycle()
    val baseUrlValidationMessage by viewModel.baseUrlValidationMessage.collectAsStateWithLifecycle()
    val saveValidationMessage by viewModel.saveValidationMessage.collectAsStateWithLifecycle()
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
        ?: recommendedModels.firstOrNull()
    val isCustomProvider = agent.provider == AiProviderType.CUSTOM
    val canSave = saveValidationMessage == null
    val saveButtonLabel = when {
        viewModel.isEditing -> stringResource(R.string.assistants_save_changes)
        viewModel.isDuplicating -> stringResource(R.string.assistants_save_duplicate_assistant)
        else -> stringResource(R.string.assistants_save_and_use)
    }
    val saveHelperText = saveValidationMessage ?: when {
        viewModel.isEditing -> stringResource(R.string.assistants_bottom_edit)
        viewModel.isDuplicating -> stringResource(R.string.assistants_bottom_duplicate)
        else -> stringResource(R.string.assistants_bottom_create)
    }
    val saveHelperColor = if (saveValidationMessage != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
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
                    ProductTopBarTitle(
                        title = when {
                            viewModel.isEditing -> stringResource(R.string.assistants_edit_title)
                            viewModel.isDuplicating -> stringResource(R.string.assistants_duplicate_title)
                            else -> stringResource(R.string.assistants_create_title)
                        },
                        subtitle = when {
                            viewModel.isEditing -> stringResource(R.string.screen_assistant_edit_subtitle)
                            viewModel.isDuplicating -> stringResource(R.string.screen_assistant_duplicate_subtitle)
                            else -> stringResource(R.string.screen_assistant_create_subtitle)
                        }
                    )
                },
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
                            viewModel.save(
                                setActiveAfterSave = !viewModel.isEditing && !viewModel.isDuplicating
                            )
                        },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(saveButtonLabel)
                    }
                    Text(
                        saveHelperText,
                        style = MaterialTheme.typography.bodySmall,
                        color = saveHelperColor,
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
            ProductScreenIntro(
                eyebrow = stringResource(R.string.agent_edit_hero_eyebrow),
                title = when {
                    viewModel.isEditing -> stringResource(R.string.assistants_hero_edit_title)
                    viewModel.isDuplicating -> stringResource(R.string.assistants_hero_duplicate_title)
                    else -> stringResource(R.string.assistants_hero_create_title)
                },
                body = when {
                    viewModel.isEditing -> stringResource(R.string.assistants_hero_edit_body)
                    viewModel.isDuplicating -> stringResource(R.string.assistants_hero_duplicate_body)
                    else -> stringResource(R.string.assistants_hero_create_body)
                }
            )

            SetupSection(
                eyebrow = stringResource(R.string.agent_edit_basic_eyebrow),
                title = stringResource(R.string.assistants_section_basic_title),
                description = stringResource(R.string.assistants_section_basic_body)
            ) {
                OutlinedTextField(
                    value = agent.name,
                    onValueChange = { viewModel.update { copy(name = it) } },
                    label = { Text(stringResource(R.string.assistants_field_name)) },
                    placeholder = { Text(stringResource(R.string.assistants_field_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = nameValidationMessage != null,
                    supportingText = {
                        Text(nameValidationMessage ?: stringResource(R.string.assistants_field_name_hint))
                    },
                    singleLine = true
                )

                Text(
                    stringResource(R.string.assistants_provider_label),
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
                                viewModel.switchProvider(type, defaultModel)
                            },
                            label = { Text(type.displayName) }
                        )
                    }
                }
                Text(
                    stringResource(R.string.assistants_provider_change_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ProviderInfoCard(info = providerInfo)

                if (isCustomProvider) {
                    CustomProviderPresetSelector(
                        selectedPreset = agent.customPreset,
                        onPresetSelect = viewModel::applyCustomPreset
                    )
                    OutlinedTextField(
                        value = agent.customBaseUrl,
                        onValueChange = { viewModel.update { copy(customBaseUrl = it) } },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.example.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = baseUrlValidationMessage != null,
                        supportingText = {
                            Text(
                                baseUrlValidationMessage
                                    ?: customProviderBaseUrlHint(agent.customPreset)
                            )
                        },
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProductPill(label = agent.provider.displayName, emphasized = true)
                    if (isCustomProvider) {
                        ProductPill(label = agent.customPreset.displayName)
                    } else if (selectedModelChoice != null) {
                        ProductPill(label = selectedModelChoice.label)
                    }
                }

                OutlinedTextField(
                    value = agent.apiKey,
                    onValueChange = { viewModel.update { copy(apiKey = it) } },
                    label = { Text(stringResource(R.string.assistants_field_api_key)) },
                    placeholder = { Text(providerInfo.apiKeyPlaceholder) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = apiKeyValidationMessage != null,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text(apiKeyValidationMessage ?: providerInfo.apiKeyHint)
                    },
                    trailingIcon = {
                        TextButton(onClick = { showApiKey = !showApiKey }) {
                            Text(
                                if (showApiKey) {
                                    stringResource(R.string.assistants_action_hide)
                                } else {
                                    stringResource(R.string.assistants_action_show)
                                }
                            )
                        }
                    },
                    singleLine = true
                )
                ApiKeyHelpCallout(
                    info = providerInfo,
                    onOpenLink = providerInfo.apiKeyActionUrl?.let { url ->
                        { uriHandler.openUri(url) }
                    }
                )

                if (isCustomProvider) {
                    RawModelSelector(
                        provider = agent.provider,
                        selectedModel = agent.model,
                        onModelChange = { viewModel.update { copy(model = it) } },
                        fetchedProviderModels = providerModels,
                        freeModelIds = freeModelIds,
                        isLoadingModels = isLoadingModels,
                        labelText = "Model ID",
                        supportingText = if (providerModels.isNotEmpty()) {
                            "Choose a detected model or type a model ID from this endpoint."
                        } else {
                            "Enter a model ID from this endpoint. ScreenAgent will detect models after a successful availability check."
                        }
                    )
                } else if (selectedModelChoice != null) {
                    RecommendedModelSelector(
                        selectedModel = selectedModelChoice,
                        choices = recommendedModels,
                        onModelChange = { modelId ->
                            viewModel.update { copy(model = modelId) }
                        }
                    )
                }
                ProviderCatalogStatusNote(
                    provider = agent.provider,
                    hasProviderCatalog = providerModels.isNotEmpty(),
                    isLoadingModels = isLoadingModels,
                    hasApiKey = agent.apiKey.isNotBlank(),
                    hasBaseUrl = !isCustomProvider || normalizeOpenAiCompatibleBaseUrl(agent.customBaseUrl).isNotBlank()
                )

                CapabilityRow(agent = agent)

                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = agent.apiKey.isNotBlank() &&
                        (!isCustomProvider || normalizeOpenAiCompatibleBaseUrl(agent.customBaseUrl).isNotBlank()) &&
                        agent.model.isNotBlank() &&
                        connectionTestState !is ConnectionTestState.Testing
                ) {
                    if (connectionTestState is ConnectionTestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.assistants_testing_availability))
                    } else {
                        Text(stringResource(R.string.assistants_test_availability))
                    }
                }

                ConnectionStateBanner(connectionTestState = connectionTestState)
            }

            SetupSection(
                eyebrow = stringResource(R.string.agent_edit_advanced_eyebrow),
                title = stringResource(R.string.assistants_section_advanced_title),
                description = stringResource(R.string.assistants_section_advanced_body)
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
                                    stringResource(R.string.assistants_advanced_card_title),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    stringResource(R.string.assistants_advanced_card_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { advancedExpanded = !advancedExpanded }) {
                                Icon(
                                    imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (advancedExpanded) {
                                        stringResource(R.string.assistants_advanced_collapse)
                                    } else {
                                        stringResource(R.string.assistants_advanced_expand)
                                    }
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
                                if (!isCustomProvider) {
                                    RawModelSelector(
                                        provider = agent.provider,
                                        selectedModel = agent.model,
                                        onModelChange = { viewModel.update { copy(model = it) } },
                                        fetchedProviderModels = providerModels,
                                        freeModelIds = freeModelIds,
                                        isLoadingModels = isLoadingModels,
                                        labelText = stringResource(R.string.assistants_custom_model_label),
                                        supportingText = stringResource(R.string.assistants_custom_model_hint)
                                    )
                                }
                                OutlinedTextField(
                                    value = agent.systemPrompt,
                                    onValueChange = { viewModel.update { copy(systemPrompt = it) } },
                                    label = { Text(stringResource(R.string.assistants_field_system_prompt)) },
                                    supportingText = {
                                        Text(stringResource(R.string.assistants_field_system_prompt_hint))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 4,
                                    maxLines = 8
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        stringResource(
                                            R.string.assistants_creativity_label,
                                            agent.temperature
                                        ),
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
                                            stringResource(R.string.assistants_creativity_precise),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            stringResource(R.string.assistants_creativity_creative),
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
    eyebrow: String,
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = {
                Text(
                    eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
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
    onOpenLink: (() -> Unit)? = null
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
                if (info.apiKeyActionLabel != null && onOpenLink != null) {
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
}

@Composable
private fun ProviderCatalogStatusNote(
    provider: AiProviderType,
    hasProviderCatalog: Boolean,
    isLoadingModels: Boolean,
    hasApiKey: Boolean,
    hasBaseUrl: Boolean = true
) {
    val (title, body) = when {
        hasProviderCatalog -> Pair(
            "Latest ${provider.displayName} catalog loaded",
            "Recommended and custom model choices are using the latest catalog ScreenAgent has loaded for ${provider.displayName}."
        )
        isLoadingModels -> Pair(
            "Loading latest ${provider.displayName} catalog…",
            "ScreenAgent is updating the available model list for this provider."
        )
        provider == AiProviderType.OPENROUTER -> Pair(
            "Showing fallback list",
            "OpenRouter's live catalog is not available right now, so ScreenAgent is using its built-in fallback list."
        )
        provider == AiProviderType.CUSTOM && !hasBaseUrl -> Pair(
            "Waiting for endpoint",
            "Enter a base URL for Grok, NVIDIA, or another compatible provider before ScreenAgent can load its model list."
        )
        provider == AiProviderType.CUSTOM && hasApiKey -> Pair(
            "Manual model entry",
            "ScreenAgent could not load models from this endpoint right now, so you can still enter the model ID manually."
        )
        hasApiKey -> Pair(
            "Showing starter list",
            "ScreenAgent could not load the latest ${provider.displayName} catalog right now, so the editor is using its starter list."
        )
        else -> Pair(
            "Showing starter list",
            if (provider == AiProviderType.CUSTOM) {
                "Enter a base URL and API key, then test availability to load models from this endpoint."
            } else {
                "Enter an API key and test availability to load the latest ${provider.displayName} catalog."
            }
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
            CapabilityBadge(label = capability)
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
            label = { Text(stringResource(R.string.assistants_recommended_model_label)) },
            supportingText = {
                Text(
                    buildString {
                        if (selectedModel.isRecommended) {
                            append(stringResource(R.string.assistants_recommended_tag))
                            append(". ")
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
                                        stringResource(R.string.assistants_recommended_tag),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                if (choice.isFree) {
                                    Text(
                                        stringResource(R.string.assistants_free_tag),
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
    isLoadingModels: Boolean = false,
    labelText: String,
    supportingText: String
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
                Text(labelText)
            },
            supportingText = {
                Text(
                    if (isLoadingModels && provider != AiProviderType.CUSTOM) {
                        stringResource(R.string.assistants_custom_model_hint)
                    } else {
                        supportingText
                    }
                )
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
                                stringResource(
                                    R.string.assistants_custom_model_fetching,
                                    provider.displayName
                                ),
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
                                        stringResource(R.string.assistants_free_tag),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                buildString {
                                    append(
                                        when {
                                            config.canHandleImageRequests() && config.supportsDocuments ->
                                                stringResource(R.string.assistants_capability_images_documents)
                                            config.canHandleImageRequests() ->
                                                stringResource(R.string.assistants_capability_images)
                                            config.supportsDocuments ->
                                                stringResource(R.string.assistants_capability_documents)
                                            else ->
                                                stringResource(R.string.assistants_capability_text_chat)
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

@Composable
private fun CustomProviderPresetSelector(
    selectedPreset: CustomProviderPreset,
    onPresetSelect: (CustomProviderPreset) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Compatibility target",
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                CustomProviderPreset.GROK,
                CustomProviderPreset.NVIDIA,
                CustomProviderPreset.MANUAL
            ).forEach { preset ->
                FilterChip(
                    selected = selectedPreset == preset,
                    onClick = { onPresetSelect(preset) },
                    label = { Text(preset.displayName) }
                )
            }
        }
        Text(
            customProviderPresetDescription(selectedPreset),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun customProviderPresetDescription(preset: CustomProviderPreset): String = when (preset) {
    CustomProviderPreset.MANUAL -> "Use Grok, NVIDIA, or another compatible provider by entering the endpoint manually."
    CustomProviderPreset.GROK -> "Prefills xAI's endpoint so you can connect Grok with your own API key."
    CustomProviderPreset.NVIDIA -> "Prefills NVIDIA's endpoint so you can bring compatible NVIDIA-hosted models into ScreenAgent."
}

private fun customProviderBaseUrlHint(preset: CustomProviderPreset): String = when (preset) {
    CustomProviderPreset.MANUAL -> "Enter the base URL for Grok, NVIDIA, or another compatible provider."
    CustomProviderPreset.GROK -> "xAI's endpoint is prefilled. You can still edit it if your account uses a different route."
    CustomProviderPreset.NVIDIA -> "NVIDIA's endpoint is prefilled. You can still edit it if needed."
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
