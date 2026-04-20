package com.mad.screenagent.feature.agents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.mad.screenagent.R
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.AiProviderType
import com.mad.screenagent.data.model.CustomProviderPreset
import com.mad.screenagent.data.model.OnDeviceDownloadState
import com.mad.screenagent.data.model.OnDeviceFailureKind
import com.mad.screenagent.data.model.InstalledOnDeviceModel
import com.mad.screenagent.data.model.OnDeviceModelCatalogEntry
import com.mad.screenagent.data.model.OnDeviceModelLibraryItem
import com.mad.screenagent.data.model.canHandleImageRequests
import com.mad.screenagent.data.model.isGemma3OnDeviceModelId
import com.mad.screenagent.data.model.isOpenRouterFreeModel
import com.mad.screenagent.data.model.isOnDeviceProvider
import com.mad.screenagent.data.model.normalizeOpenAiCompatibleBaseUrl
import com.mad.screenagent.shared.streaming.NativeWebSearchConfig
import com.mad.screenagent.shared.streaming.OnDeviceUserMessages
import com.mad.screenagent.data.model.MONEY_SAVER_MODEL
import com.mad.screenagent.data.model.SIDEAGENT_OPENROUTER_BEST_FREE_MODEL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mad.screenagent.design.components.ProductPill
import com.mad.screenagent.design.components.ProductScreenIntro
import com.mad.screenagent.design.components.ProductTopBarTitle

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
    val publicOnDeviceModelLibrary by viewModel.publicOnDeviceModelLibrary.collectAsStateWithLifecycle()
    val readyOnDeviceModelLibrary by viewModel.readyOnDeviceModelLibrary.collectAsStateWithLifecycle()
    val importedOnDeviceModelLibrary by viewModel.importedOnDeviceModelLibrary.collectAsStateWithLifecycle()
    val nonPublicOnDeviceModelLibrary by viewModel.nonPublicOnDeviceModelLibrary.collectAsStateWithLifecycle()
    val onDeviceDownloadState by viewModel.onDeviceDownloadState.collectAsStateWithLifecycle()
    val onDeviceCatalogUiState by viewModel.onDeviceCatalogUiState.collectAsStateWithLifecycle()
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
    val isOnDeviceProvider = agent.provider.isOnDeviceProvider()
    val isGemmaOnDeviceProvider = agent.provider == AiProviderType.ON_DEVICE_GEMMA3
    val visiblePublicOnDeviceModelLibrary = remember(publicOnDeviceModelLibrary, isGemmaOnDeviceProvider) {
        if (isGemmaOnDeviceProvider) {
            publicOnDeviceModelLibrary.filter { isGemma3OnDeviceModelId(it.catalogEntry.id) }
        } else {
            publicOnDeviceModelLibrary
        }
    }
    val visibleReadyOnDeviceModelLibrary = remember(readyOnDeviceModelLibrary, isGemmaOnDeviceProvider) {
        if (isGemmaOnDeviceProvider) {
            readyOnDeviceModelLibrary.filter { isGemma3OnDeviceModelId(it.catalogEntry.id) }
        } else {
            readyOnDeviceModelLibrary
        }
    }
    val visibleImportedOnDeviceModelLibrary = remember(importedOnDeviceModelLibrary, isGemmaOnDeviceProvider) {
        if (isGemmaOnDeviceProvider) {
            emptyList()
        } else {
            importedOnDeviceModelLibrary
        }
    }
    val visibleNonPublicOnDeviceModelLibrary = remember(nonPublicOnDeviceModelLibrary, isGemmaOnDeviceProvider) {
        if (isGemmaOnDeviceProvider) {
            emptyList()
        } else {
            nonPublicOnDeviceModelLibrary
        }
    }
    val selectedOnDeviceModelId = agent.onDevice.selectedModelId.ifBlank { agent.model }
    val selectedPublicOnDeviceItem = visiblePublicOnDeviceModelLibrary.firstOrNull {
        it.catalogEntry.id == selectedOnDeviceModelId
    }
    val selectedReadyOnDeviceItem = visibleReadyOnDeviceModelLibrary.firstOrNull {
        it.catalogEntry.id == selectedOnDeviceModelId
    }
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
    val ggufImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importOnDeviceModel(uri)
        }
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
                                val defaultModel = when (type) {
                                    AiProviderType.ON_DEVICE_GEMMA3,
                                    AiProviderType.ON_DEVICE -> ""
                                    else -> defaultRecommendedModelId(
                                        provider = type,
                                        openRouterCatalogEntries = openRouterCatalogEntries,
                                        fetchedProviderModels = if (type == agent.provider) providerModels else emptyList(),
                                        freeModelIds = freeModelIds
                                    )
                                }
                                viewModel.switchProvider(type, defaultModel)
                            },
                            label = { Text(type.displayName) }
                        )
                    }
                }
                val providerChangeHint = if (isOnDeviceProvider) {
                    if (isGemmaOnDeviceProvider) {
                        "Pick a pinned Gemma 3 model from the shelf to run it locally with no API key."
                    } else {
                        "Pick your preferred model directly from the \"Models Shelf\" to run it on the device with no API key."
                    }
                } else {
                    stringResource(R.string.assistants_provider_change_hint)
                }
                Text(
                    providerChangeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isOnDeviceProvider) {
                    ProviderInfoCard(info = providerInfo)
                }

                if (isOnDeviceProvider) {
                    OnDeviceModelSection(
                        provider = agent.provider,
                        publicLibrary = visiblePublicOnDeviceModelLibrary,
                        readyLibrary = visibleReadyOnDeviceModelLibrary,
                        importedLibrary = visibleImportedOnDeviceModelLibrary,
                        nonPublicLibrary = visibleNonPublicOnDeviceModelLibrary,
                        selectedModelId = agent.onDevice.selectedModelId.ifBlank { agent.model },
                        downloadState = onDeviceDownloadState,
                        catalogUiState = onDeviceCatalogUiState,
                        onModelSelect = { modelId ->
                            viewModel.selectOnDeviceModel(modelId)
                        },
                        onDownload = viewModel::downloadOnDeviceModel,
                        onImport = { ggufImportLauncher.launch(arrayOf("*/*")) },
                        showImportAction = !isGemmaOnDeviceProvider,
                        onRefresh = viewModel::refreshOnDeviceCatalog,
                        onSortChange = viewModel::setOnDeviceShelfSort,
                        onCancel = viewModel::cancelOnDeviceDownload,
                        onDelete = viewModel::deleteOnDeviceModel,
                    )
                } else if (isCustomProvider) {
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

                if (!isOnDeviceProvider) {
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
                }

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
                } else if (!isOnDeviceProvider && selectedModelChoice != null) {
                    RecommendedModelSelector(
                        selectedModel = selectedModelChoice,
                        choices = recommendedModels,
                        onModelChange = { modelId ->
                            viewModel.update { copy(model = modelId) }
                        }
                    )
                }
                if (!isOnDeviceProvider) {
                    ProviderCatalogStatusNote(
                        provider = agent.provider,
                        hasProviderCatalog = providerModels.isNotEmpty(),
                        isLoadingModels = isLoadingModels,
                        hasApiKey = agent.apiKey.isNotBlank(),
                        hasBaseUrl = !isCustomProvider || normalizeOpenAiCompatibleBaseUrl(agent.customBaseUrl).isNotBlank()
                    )
                }

                CapabilityRow(agent = agent)

                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = when {
                        isOnDeviceProvider -> selectedReadyOnDeviceItem?.installRecord?.downloadState?.isReadyForUse == true
                        else -> agent.apiKey.isNotBlank() &&
                            (!isCustomProvider || normalizeOpenAiCompatibleBaseUrl(agent.customBaseUrl).isNotBlank()) &&
                            agent.model.isNotBlank()
                    } &&
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
                                if (isOnDeviceProvider) {
                                    Text(
                                        "Local runtime tuning",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    OutlinedTextField(
                                        value = agent.onDevice.maxOutputTokens.toString(),
                                        onValueChange = { raw ->
                                            val digits = raw.filter { it.isDigit() }
                                            viewModel.update {
                                                copy(
                                                    onDevice = onDevice.copy(
                                                        maxOutputTokens = digits.toIntOrNull()
                                                            ?: onDevice.maxOutputTokens
                                                    )
                                                )
                                            }
                                        },
                                        label = { Text("Max output tokens") },
                                        supportingText = { Text("How many tokens the local model may generate.") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                } else if (!isCustomProvider) {
                                    if (agent.model == MONEY_SAVER_MODEL) {
                                        val resolvedId = remember(agent.provider, providerModels) {
                                            resolvedMoneySaverModelId(agent.provider, providerModels)
                                        }
                                        OutlinedTextField(
                                            value = resolvedId,
                                            onValueChange = {},
                                            label = { Text(stringResource(R.string.assistants_custom_model_label)) },
                                            supportingText = { Text("Resolved by Money Saver at request time") },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = false,
                                            singleLine = true
                                        )
                                    } else {
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
                                }
                                if (agent.model == SIDEAGENT_OPENROUTER_BEST_FREE_MODEL) {
                                    InternetAccessToggle(
                                        isScreenAgentOptimized = true,
                                        enabled = agent.agentSideInternetAccess ?: true,
                                        onToggle = { viewModel.update { copy(agentSideInternetAccess = it) } }
                                    )
                                }
                                if (agent.provider == AiProviderType.ANTHROPIC || agent.provider == AiProviderType.OPENAI) {
                                    NativeWebSearchToggle(
                                        provider = agent.provider,
                                        enabled = agent.nativeWebSearchEnabled,
                                        toolType = agent.nativeWebSearchToolType
                                            ?.takeIf { it.isNotBlank() }
                                            ?: NativeWebSearchConfig.defaultToolTypeFor(agent.provider),
                                        onToggle = { viewModel.update { copy(nativeWebSearchEnabled = it) } },
                                        onToolTypeChange = { viewModel.update { copy(nativeWebSearchToolType = it) } }
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
                                // Token limit
                                HorizontalDivider()
                                var tokenLimitText by rememberSaveable(agent.id) {
                                    mutableStateOf(agent.tokenLimit?.toString() ?: "")
                                }
                                OutlinedTextField(
                                    value = tokenLimitText,
                                    onValueChange = { raw ->
                                        tokenLimitText = raw.filter { it.isDigit() }
                                        val parsed = tokenLimitText.toLongOrNull()
                                        viewModel.update { copy(tokenLimit = parsed) }
                                    },
                                    label = { Text("Monthly token limit") },
                                    supportingText = { Text("Leave empty for no limit. Counts input + output tokens.") },
                                    placeholder = { Text("e.g. 100000") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                val currentMonth = remember {
                                    SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
                                }
                                val effectiveUsed = if (agent.tokenUsedMonth == currentMonth) agent.tokenUsed else 0L
                                if (effectiveUsed > 0L) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Used this month: ${formatTokenCount(effectiveUsed)} total tokens",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        TextButton(onClick = { viewModel.resetTokenUsage() }) {
                                            Text("Reset usage")
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
        provider.isOnDeviceProvider() -> Pair(
            "Local catalog ready",
            "ScreenAgent will show installed on-device models here once downloads are wired."
        )
        provider == AiProviderType.OPENROUTER -> Pair(
            "Showing fallback list",
            "OpenRouter's live catalog is not available right now, so ScreenAgent is using its built-in fallback list."
        )
        provider == AiProviderType.CUSTOM && !hasBaseUrl -> Pair(
            "Waiting for endpoint",
            "Enter a base URL for Groq, Grok, NVIDIA, or another compatible provider before ScreenAgent can load its model list."
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
private fun LocalModelStatusNoteLegacy(
    hasModels: Boolean,
    isLoadingModels: Boolean,
    selectedModel: String,
    isReady: Boolean
) {
    val (title, body) = when {
        isReady -> Pair(
            "Local model ready",
            "ScreenAgent found an installed on-device model and can use it without an API key."
        )
        isLoadingModels -> Pair(
            "Loading local model catalog…",
            "ScreenAgent is updating the local model list."
        )
        hasModels -> Pair(
            "Local catalog available",
            "Choose a local model below and install it before using On-Device."
        )
        else -> Pair(
            "No local models yet",
            "ScreenAgent will show the curated on-device catalog here once it is wired."
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
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (selectedModel.isNotBlank()) {
                    Text(
                        text = "Selected: $selectedModel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OnDeviceModelSectionLegacy(
    library: List<OnDeviceModelLibraryItem>,
    selectedModelId: String,
    onModelSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Local models", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Choose a native model for On-Device. Downloads and full backend wiring come next.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            TextButton(onClick = onRefresh) {
                Text("Refresh")
            }
        }

            Text(
                "Status: ${if (library.any { it.installRecord != null }) "Some models installed" else "No models installed yet"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            library.forEach { model ->
                val isSelected = model.catalogEntry.id == selectedModelId
                val isInstalled = model.installRecord != null
                ElevatedCard(
                    onClick = { onModelSelect(model.catalogEntry.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.catalogEntry.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    model.catalogEntry.description.ifBlank { "Local on-device model" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Text(
                                    "Selected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { onModelSelect(model.catalogEntry.id) },
                                label = { Text(if (isInstalled) "Installed" else "Not installed") }
                            )
                            if (model.catalogEntry.supportsVision) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Vision") }
                                )
                            }
                        }
                    }
                }
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
                CustomProviderPreset.GROQ,
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
    CustomProviderPreset.MANUAL -> "Use Groq, Grok, NVIDIA, or another compatible provider by entering the endpoint manually."
    CustomProviderPreset.GROQ -> "Prefills Groq's endpoint so you can connect Groq-hosted models with your own API key."
    CustomProviderPreset.GROK -> "Prefills xAI's endpoint so you can connect Grok with your own API key."
    CustomProviderPreset.NVIDIA -> "Prefills NVIDIA's endpoint so you can bring compatible NVIDIA-hosted models into ScreenAgent."
}

private fun customProviderBaseUrlHint(preset: CustomProviderPreset): String = when (preset) {
    CustomProviderPreset.MANUAL -> "Enter the base URL for Groq, Grok, NVIDIA, or another compatible provider."
    CustomProviderPreset.GROQ -> "Groq's endpoint is prefilled. You can still edit it if your account uses a different route."
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

@Composable
private fun InternetAccessToggle(
    isScreenAgentOptimized: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Internet Service", style = MaterialTheme.typography.labelLarge)
            Text(
                text = if (isScreenAgentOptimized) {
                    "Live web search before each reply. On by default for ScreenAgent Free. This is a custom service available only for the ScreenAgent Free configuration."
                } else {
                    "Enables live web search. Works best with capable instruction-following models."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeWebSearchToggle(
    provider: AiProviderType,
    enabled: Boolean,
    toolType: String,
    onToggle: (Boolean) -> Unit,
    onToolTypeChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Internet Service", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = when (provider) {
                        AiProviderType.ANTHROPIC ->
                            "Native web search via Anthropic's built-in search tool. Handled server-side — no extra requests needed."
                        AiProviderType.OPENAI ->
                            "Native web search via OpenAI Responses API. Uses a different endpoint from standard chat."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        AnimatedVisibility(visible = enabled) {
            val presets = NativeWebSearchConfig.presetsFor(provider)
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = toolType,
                    onValueChange = onToolTypeChange,
                    label = { Text("Search tool type") },
                    supportingText = {
                        Text("Tool type sent to the provider. Edit manually if the provider releases an updated version.")
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryEditable)
                        .fillMaxWidth(),
                    singleLine = true
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset) },
                            onClick = {
                                onToolTypeChange(preset)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun OnDeviceModelSection(
    provider: AiProviderType,
    publicLibrary: List<OnDeviceModelLibraryItem>,
    readyLibrary: List<OnDeviceModelLibraryItem>,
    importedLibrary: List<OnDeviceModelLibraryItem>,
    nonPublicLibrary: List<OnDeviceModelLibraryItem>,
    selectedModelId: String,
    downloadState: OnDeviceDownloadState,
    catalogUiState: OnDeviceCatalogUiState,
    onModelSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    onImport: () -> Unit,
    showImportAction: Boolean,
    onRefresh: () -> Unit,
    onSortChange: (OnDeviceShelfSort) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var sortExpanded by rememberSaveable { mutableStateOf(false) }
    val isRefreshing = catalogUiState.refreshStatus == OnDeviceCatalogRefreshStatus.REFRESHING
    val shelfItems = (publicLibrary + importedLibrary)
        .distinctBy { it.catalogEntry.id }
        .sortedWith { left, right ->
            compareShelfItems(left, right, catalogUiState.shelfSort)
        }
    val selectedReadyItem = readyLibrary.firstOrNull { it.catalogEntry.id == selectedModelId }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (provider == AiProviderType.ON_DEVICE_GEMMA3) "Gemma 3 Shelf" else "Models Shelf",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = if (provider == AiProviderType.ON_DEVICE_GEMMA3) {
                    "Download the pinned Gemma 3 GGUF models here. Ready models appear in the selector below."
                } else {
                    "Download curated GGUF models or import your own GGUF files here. Ready models appear in the selector below."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = catalogUiState.lastRefreshedLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (catalogUiState.refreshStatus == OnDeviceCatalogRefreshStatus.FAILED_CACHED) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = catalogUiState.lastRefreshFailureMessage
                            ?: "Showing cached catalog.",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            ExposedDropdownMenuBox(
                expanded = sortExpanded,
                onExpandedChange = { sortExpanded = it }
            ) {
                OutlinedTextField(
                    value = shelfSortLabel(catalogUiState.shelfSort),
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    readOnly = true,
                    label = { Text("Sort models") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sortExpanded) },
                    singleLine = true
                )
                ExposedDropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false }
                ) {
                    OnDeviceShelfSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(shelfSortLabel(sort)) },
                            onClick = {
                                sortExpanded = false
                                onSortChange(sort)
                            }
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Refreshing")
                    } else {
                        Text("Refresh Models")
                    }
                }
                if (showImportAction) {
                    OutlinedButton(onClick = onImport) {
                        Text("Import GGUF")
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (shelfItems.isEmpty()) {
                        Text(
                            text = if (provider == AiProviderType.ON_DEVICE_GEMMA3) {
                                "No Gemma 3 models are available yet."
                            } else {
                                "No GGUF models are available yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        shelfItems.forEach { item ->
                            val entry = item.catalogEntry
                            val record = item.installRecord
                            val state = record?.downloadState ?: OnDeviceDownloadState.NOT_DOWNLOADED
                            val isReady = state.isReadyForUse
                            val isGreyed = !isReady
                            val status = record.statusHeadline(state)
                            val progressText = record.progressText()
                            val canCancel = state == OnDeviceDownloadState.DOWNLOADING ||
                                state == OnDeviceDownloadState.VALIDATING
                            val canRemove = entry.isImported || isReady
                            val actionLabel = when {
                                canCancel -> "Cancel"
                                canRemove -> "Remove"
                                else -> "Download"
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 84.dp)
                                    .alpha(if (isGreyed) 0.55f else 1f),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                entry.userFacingDisplayName(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            capabilityIcon(
                                                icon = Icons.Filled.TextSnippet,
                                                contentDescription = "Text",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            capabilityIcon(
                                                icon = Icons.Filled.Description,
                                                contentDescription = "Documents",
                                                tint = MaterialTheme.colorScheme.secondary
                                            )
                                            if (entry.supportsVision) {
                                                Spacer(Modifier.width(6.dp))
                                                capabilityIcon(
                                                    icon = Icons.Filled.Image,
                                                    contentDescription = "Vision",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            text = listOfNotNull(
                                                status,
                                                entry.estimatedSizeText(),
                                                progressText,
                                                record.failureReasonSummary()
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            when {
                                                canCancel -> onCancel(entry.id)
                                                canRemove -> onDelete(entry.id)
                                                else -> onDownload(entry.id)
                                            }
                                        }
                                    ) {
                                        Text(actionLabel)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selectedReadyItem?.catalogEntry?.userFacingDisplayName() ?: "None Selected",
                    onValueChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    readOnly = true,
                    label = { Text("Model selector") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    supportingText = {
                        Text(
                            if (selectedReadyItem == null) {
                                "Choose a ready model from the shelf. This selection is required before saving."
                            } else {
                                selectedReadyItem.installRecord?.selectorReadinessSummary(
                                    selectedReadyItem.catalogEntry.description
                                ) ?: selectedReadyItem.catalogEntry.description.ifBlank { "Ready to use" }
                            }
                        )
                    },
                    singleLine = true
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "None Selected",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            expanded = false
                            onModelSelect("")
                        }
                    )
                    if (readyLibrary.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No ready GGUF models yet") },
                            onClick = { expanded = false },
                            enabled = false
                        )
                    } else {
                        readyLibrary.forEach { item ->
                            val entry = item.catalogEntry
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(entry.userFacingDisplayName())
                                        Text(
                                            entry.description.ifBlank { "Ready to use" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    onModelSelect(entry.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun InstalledOnDeviceModel?.progressText(): String? {
    val record = this ?: return null
    if (record.downloadState != OnDeviceDownloadState.DOWNLOADING &&
        record.downloadState != OnDeviceDownloadState.VALIDATING
    ) {
        return null
    }
    val downloadedText = record.downloadedBytes.takeIf { it > 0L }?.humanReadableBytes()
    val totalText = record.totalBytes.takeIf { it > 0L }?.humanReadableBytes()
    return when {
        downloadedText != null && totalText != null -> "$downloadedText / $totalText"
        downloadedText != null -> downloadedText
        record.downloadState == OnDeviceDownloadState.DOWNLOADING -> "Downloading"
        record.downloadState == OnDeviceDownloadState.VALIDATING -> "Validating"
        else -> null
    }
}

private fun OnDeviceModelCatalogEntry.userFacingDisplayName(): String {
    val current = displayName.trim()
    if (current.isNotEmpty() && !current.startsWith("imported-")) return current
    val fileLabel = fileName.removeSuffix(".gguf").trim()
    if (fileLabel.isNotEmpty()) return fileLabel
    return id.removePrefix("imported-").trim().ifBlank { id }
}

private fun compareShelfItems(
    left: OnDeviceModelLibraryItem,
    right: OnDeviceModelLibraryItem,
    sort: OnDeviceShelfSort
): Int {
    val leftImported = left.catalogEntry.isImported
    val rightImported = right.catalogEntry.isImported
    if (leftImported != rightImported) {
        return if (leftImported) 1 else -1
    }
    return when (sort) {
        OnDeviceShelfSort.RECOMMENDED -> compareValuesBy(
            left,
            right,
            { it.catalogEntry.recommendedRank },
            { it.catalogEntry.estimatedSizeMb },
            { it.catalogEntry.userFacingDisplayName().lowercase(Locale.getDefault()) }
        )
        OnDeviceShelfSort.SMALLEST -> compareValuesBy(
            left,
            right,
            { it.catalogEntry.estimatedSizeMb },
            { it.catalogEntry.recommendedRank },
            { it.catalogEntry.userFacingDisplayName().lowercase(Locale.getDefault()) }
        )
        OnDeviceShelfSort.QUALITY -> compareValuesBy(
            left,
            right,
            { -it.catalogEntry.recommendedRank.coerceAtMost(10_000) },
            { -it.catalogEntry.estimatedSizeMb },
            { it.catalogEntry.userFacingDisplayName().lowercase(Locale.getDefault()) }
        )
    }
}

private fun shelfSortLabel(sort: OnDeviceShelfSort): String = when (sort) {
    OnDeviceShelfSort.RECOMMENDED -> "Recommended"
    OnDeviceShelfSort.SMALLEST -> "Smallest"
    OnDeviceShelfSort.QUALITY -> "Quality"
}

private fun OnDeviceCatalogUiState.lastRefreshedLabel(): String {
    if (fetchedAt <= 0L) return "Last refreshed: not yet"
    val now = System.currentTimeMillis()
    val deltaMs = (now - fetchedAt).coerceAtLeast(0L)
    val relative = when {
        deltaMs < 60_000L -> "just now"
        deltaMs < 60L * 60L * 1000L -> "${deltaMs / 60_000L} min ago"
        deltaMs < 24L * 60L * 60L * 1000L -> "${deltaMs / (60L * 60L * 1000L)} h ago"
        else -> null
    }
    val absolute = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(fetchedAt))
    return if (relative != null) {
        "Last refreshed: $relative ($absolute)"
    } else {
        "Last refreshed: $absolute"
    }
}

private fun InstalledOnDeviceModel?.statusHeadline(
    fallbackState: OnDeviceDownloadState
): String {
    val record = this
    val state = record?.downloadState ?: fallbackState
    return when (state) {
        OnDeviceDownloadState.DOWNLOADING -> "Downloading"
        OnDeviceDownloadState.VALIDATING -> "Validating"
        OnDeviceDownloadState.READY,
        OnDeviceDownloadState.DOWNLOADED -> when {
            record?.visionReady == true -> "Ready for text + images"
            else -> "Ready for text"
        }
        OnDeviceDownloadState.CANCELLED -> "Cancelled"
        OnDeviceDownloadState.FAILED -> when {
            record != null -> OnDeviceUserMessages.shortStatus(record.failureKind)
            else -> "Download failed"
        }
        OnDeviceDownloadState.UNAVAILABLE -> "Unavailable on this device"
        OnDeviceDownloadState.NOT_DOWNLOADED -> "Not installed"
    }
}

private fun InstalledOnDeviceModel?.failureReasonSummary(): String? {
    val record = this
    val textMessage = record?.errorMessage?.trim().orEmpty()
    if (textMessage.isNotBlank()) {
        return OnDeviceUserMessages.validationMessage(
            record?.failureKind ?: OnDeviceFailureKind.NONE,
            textMessage
        )
    }
    val visionMessage = record?.visionErrorMessage?.trim().orEmpty()
    if (visionMessage.isNotBlank()) {
        return OnDeviceUserMessages.visionValidationMessage(
            record?.visionFailureKind ?: OnDeviceFailureKind.NONE,
            visionMessage
        )
    }
    return null
}

private fun InstalledOnDeviceModel.selectorReadinessSummary(fallbackDescription: String): String = when {
    visionReady -> "Ready for text and images."
    visionErrorMessage?.isNotBlank() == true -> "Ready for text. Image support isn't ready yet."
    else -> fallbackDescription.ifBlank { "Ready for text" }
}

private fun Long.humanReadableBytes(): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

private fun OnDeviceModelCatalogEntry.estimatedSizeText(): String? {
    if (estimatedSizeMb <= 0) return null
    val approxBytes = estimatedSizeMb.toLong() * 1024L * 1024L
    return "~${approxBytes.humanReadableBytes()}"
}

@Composable
private fun capabilityIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(12.dp),
        tint = tint
    )
}

@Composable
private fun LocalModelStatusNote(
    publicModels: List<OnDeviceModelLibraryItem>,
    nonPublicModels: List<OnDeviceModelLibraryItem>,
    isLoadingModels: Boolean,
    selectedModel: String,
    selectedIsPublic: Boolean,
    isReady: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("On-Device status", style = MaterialTheme.typography.labelLarge)
            Text(
                text = when {
                    isLoadingModels -> "Loading public local models..."
                    publicModels.isEmpty() && nonPublicModels.isEmpty() -> "No local models are available yet."
                    publicModels.isEmpty() -> "No public models are available yet."
                    selectedModel.isBlank() -> "Choose a public model to continue."
                    !selectedIsPublic -> "Selected model is listed separately below."
                    isReady -> "Selected public model is ready locally."
                    else -> "Selected public model is not ready yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
