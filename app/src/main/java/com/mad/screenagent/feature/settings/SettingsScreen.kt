package com.mad.screenagent.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.sqrt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.QuickActionConfig
import com.mad.screenagent.data.model.QuickActionIconKey
import com.mad.screenagent.data.model.forSlot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mad.screenagent.R
import com.mad.screenagent.data.model.AppColorTheme
import com.mad.screenagent.feature.bubble.FloatingBubbleService
import com.mad.screenagent.feature.bubble.MiniChatScreenshotAccessibilityService
import com.mad.screenagent.design.components.ProductPill
import com.mad.screenagent.design.components.ProductScreenIntro
import com.mad.screenagent.design.components.ProductTopBarTitle
import com.mad.screenagent.design.theme.lightScheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val bubbleEnabled by viewModel.bubbleEnabled.collectAsStateWithLifecycle()
    val colorTheme by viewModel.colorTheme.collectAsStateWithLifecycle()
    val currentBubbleEnabled by rememberUpdatedState(bubbleEnabled)
    val appVersionName = remember(context) { resolveAppVersionName(context) }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var isScreenshotAccessibilityEnabled by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                MiniChatScreenshotAccessibilityService.isEnabled(context)
        )
    }
    var pendingEnableMiniChat by rememberSaveable { mutableStateOf(false) }
    var showOverlayPermissionCallout by rememberSaveable { mutableStateOf(false) }
    var showScreenshotAccessibilityDisclosure by rememberSaveable { mutableStateOf(false) }
    val isMiniChatConfigured = bubbleEnabled
    val isMiniChatActive = bubbleEnabled && hasOverlayPermission
    val showOverlayPermissionCard = !hasOverlayPermission &&
        (showOverlayPermissionCallout || isMiniChatConfigured)
    val showMiniChatScreenshotsCard =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isScreenshotAccessibilityEnabled
    val miniChatStatusLabel = stringResource(
        when {
            isMiniChatActive -> R.string.mini_chat_status_active
            isMiniChatConfigured -> R.string.mini_chat_status_needs_permission
            else -> R.string.mini_chat_status_off
        }
    )
    val miniChatStateDescription = stringResource(
        when {
            isMiniChatActive -> R.string.mini_chat_active
            isMiniChatConfigured -> R.string.mini_chat_needs_permission
            else -> R.string.mini_chat_inactive
        }
    )

    fun openOverlaySettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun openScreenshotAccessibilitySettings() {
        MiniChatScreenshotAccessibilityService.openSettings(context)
    }

    if (showScreenshotAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showScreenshotAccessibilityDisclosure = false },
            title = {
                Text(stringResource(R.string.mini_chat_screenshot_disclosure_title))
            },
            text = {
                Text(stringResource(R.string.mini_chat_screenshot_disclosure_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showScreenshotAccessibilityDisclosure = false
                        openScreenshotAccessibilitySettings()
                    }
                ) {
                    Text(stringResource(R.string.action_continue_to_accessibility_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showScreenshotAccessibilityDisclosure = false }) {
                    Text(stringResource(R.string.action_not_now))
                }
            }
        )
    }

    DisposableEffect(lifecycleOwner, context, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val previousOverlayPermission = hasOverlayPermission
                val overlayPermissionGranted = Settings.canDrawOverlays(context)
                hasOverlayPermission = overlayPermissionGranted
                isScreenshotAccessibilityEnabled =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        MiniChatScreenshotAccessibilityService.isEnabled(context)

                if (overlayPermissionGranted) {
                    if (currentBubbleEnabled) {
                        FloatingBubbleService.startService(context)
                    }
                    pendingEnableMiniChat = false
                    showOverlayPermissionCallout = false
                }

                // Prevent the UI from showing the mini chat as enabled when Android permission
                // was revoked outside the app.
                if (!overlayPermissionGranted && previousOverlayPermission && currentBubbleEnabled) {
                    pendingEnableMiniChat = false
                    showOverlayPermissionCallout = true
                    FloatingBubbleService.stopService(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    ProductTopBarTitle(
                        title = stringResource(R.string.settings_title),
                        subtitle = stringResource(R.string.screen_settings_subtitle)
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
            ProductScreenIntro(
                eyebrow = stringResource(R.string.settings_title),
                title = stringResource(R.string.settings_hero_title),
                body = stringResource(R.string.settings_hero_body)
            )

            SettingsSectionHeader(title = stringResource(R.string.settings_section_mini_chat))

            ElevatedCard {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    ListItem(
                        leadingContent = { Icon(Icons.Default.BubbleChart, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.feature_mini_chat)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ProductPill(
                                        label = miniChatStatusLabel,
                                        emphasized = isMiniChatConfigured
                                    )
                                }
                                Text(
                                    stringResource(
                                        if (isMiniChatConfigured && !hasOverlayPermission) {
                                            R.string.mini_chat_enabled_waiting_for_permission
                                        } else {
                                            R.string.mini_chat_description
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = bubbleEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setBubbleEnabled(enabled)
                                    if (enabled) {
                                        if (!hasOverlayPermission) {
                                            pendingEnableMiniChat = true
                                            showOverlayPermissionCallout = true
                                            openOverlaySettings()
                                        } else {
                                            pendingEnableMiniChat = false
                                            showOverlayPermissionCallout = false
                                            FloatingBubbleService.startService(context)
                                        }
                                    } else {
                                        pendingEnableMiniChat = false
                                        showOverlayPermissionCallout = false
                                        FloatingBubbleService.stopService(context)
                                    }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = context.getString(R.string.feature_mini_chat)
                                    stateDescription = miniChatStateDescription
                                }
                            )
                        }
                    )

                    if (showOverlayPermissionCard) {
                        HorizontalDivider()
                        MiniChatHelperItem(
                            icon = { Icon(Icons.Default.Security, contentDescription = null) },
                            title = stringResource(R.string.overlay_permission_required),
                            status = stringResource(R.string.mini_chat_screenshots_status_needs_setup),
                            body = stringResource(
                                if (isMiniChatConfigured) {
                                    R.string.mini_chat_permission_needed_message
                                } else {
                                    R.string.overlay_permission_message
                                }
                            ),
                            emphasized = false,
                            actionLabel = stringResource(R.string.action_allow_display_over_other_apps),
                            onAction = ::openOverlaySettings
                        )
                    }

                    if (showMiniChatScreenshotsCard) {
                        HorizontalDivider()
                        MiniChatHelperItem(
                            icon = { Icon(Icons.Default.Screenshot, contentDescription = null) },
                            title = stringResource(R.string.mini_chat_screenshots_title),
                            status = stringResource(
                                if (isScreenshotAccessibilityEnabled) {
                                    R.string.mini_chat_screenshots_status_enabled
                                } else {
                                    R.string.mini_chat_screenshots_status_needs_setup
                                }
                            ),
                            body = stringResource(
                                if (isScreenshotAccessibilityEnabled) {
                                    R.string.mini_chat_screenshots_enabled_body
                                } else {
                                    R.string.mini_chat_screenshots_disabled_body
                                }
                            ),
                            emphasized = isScreenshotAccessibilityEnabled,
                            actionLabel = stringResource(
                                if (isScreenshotAccessibilityEnabled) {
                                    R.string.action_manage_screenshot_helper
                                } else {
                                    R.string.action_set_up_screenshots
                                }
                            ),
                            onAction = {
                                showScreenshotAccessibilityDisclosure = true
                            }
                        )
                    }
                }
            }
            HorizontalDivider()

            SettingsSectionHeader(title = stringResource(R.string.settings_section_appearance))

            ElevatedCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.settings_color_theme),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProductPill(
                            label = stringResource(R.string.settings_current_theme_label, colorTheme.displayName),
                            emphasized = true
                        )
                    }
                    Text(
                        stringResource(R.string.settings_color_theme_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppColorTheme.entries.forEach { theme ->
                            ThemeCard(
                                theme = theme,
                                isSelected = theme == colorTheme,
                                onClick = { viewModel.setColorTheme(theme) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            SettingsSectionHeader(title = stringResource(R.string.settings_section_about))
            ElevatedCard {
                Text(
                    stringResource(R.string.settings_version, appVersionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniChatHelperItem(
    icon: @Composable () -> Unit,
    title: String,
    status: String,
    body: String,
    emphasized: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    icon()
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    ProductPill(
                        label = status,
                        emphasized = emphasized
                    )
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(theme: AppColorTheme, isSelected: Boolean, onClick: () -> Unit) {
    val themeDescription = stringResource(R.string.settings_theme_option, theme.displayName)
    val selectionState = stringResource(
        if (isSelected) R.string.selected else R.string.not_selected
    )
    val previewScheme = remember(theme) { theme.lightScheme() }

    OutlinedCard(
        onClick = onClick,
        border = if (isSelected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier
            .width(92.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                selected = isSelected
                contentDescription = themeDescription
                stateDescription = selectionState
            }
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                previewScheme.primary,
                                previewScheme.secondary,
                                previewScheme.background
                            )
                        )
                    )
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(previewScheme.surfaceVariant)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    theme.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                style = MaterialTheme.typography.labelSmall,
                text = stringResource(
                    if (theme == AppColorTheme.DEFAULT) {
                        R.string.settings_theme_default_label
                    } else {
                        R.string.settings_theme_optional_label
                    }
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Quick Actions ──────────────────────────────────────────────────────────

@Composable
internal fun QuickActionsSection(
    bubbleEnabled: Boolean,
    quickActions: List<QuickActionConfig>,
    // Feature 2: agent list for the per-action agent picker.
    agents: List<AgentConfig> = emptyList(),
    onDisabledTap: () -> Unit,
    onSaveActions: (List<QuickActionConfig>) -> Unit,
) {
    val contentAlpha = if (bubbleEnabled) 1f else 0.38f
    // Always read the *current* quickActions inside lambdas — avoids stale-closure
    // bugs where an onSave lambda captured an earlier (possibly empty) snapshot.
    val currentQuickActions by rememberUpdatedState(quickActions)
    // Editing state: slot index (0–3) currently being edited, null = none
    var editingSlot by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Feature 3: 4 configurable slots replace the former hardcoded More Details / Translate.
        repeat(4) { slotIndex ->
            val action = quickActions.forSlot(slotIndex)
            val isEditing = editingSlot == slotIndex

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha)
            ) {
                if (action == null) {
                    // Empty slot → "Add Action" row + optional inline editor
                    ListItem(
                        leadingContent = {
                            QuickActionPositionDiagram(
                                slotIndex = slotIndex,
                                modifier = Modifier.size(40.dp)
                            )
                        },
                        headlineContent = {
                            Text(
                                // Number removed — position is already shown by the diagram.
                                "Add Quick Action",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (bubbleEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable(enabled = true) {
                            if (!bubbleEnabled) { onDisabledTap(); return@clickable }
                            editingSlot = if (isEditing) null else slotIndex
                        }
                    )
                    if (isEditing) {
                        HorizontalDivider()
                        QuickActionEditor(
                            action = QuickActionConfig(),
                            agents = agents,
                            onSave = { newAction ->
                                // Remove any existing action for this slot by id (handles both
                                // new data with explicit slotIndex and legacy data where
                                // slotIndex is null — filtering by id is always reliable).
                                val existing = currentQuickActions.forSlot(slotIndex)
                                val updated = currentQuickActions
                                    .filter { it.id != existing?.id }
                                    .toMutableList()
                                    .also { it.add(newAction.copy(slotIndex = slotIndex)) }
                                onSaveActions(updated)
                                editingSlot = null
                            },
                            onCancel = { editingSlot = null }
                        )
                    }
                } else {
                    // Existing action → tap row to expand/collapse editor inline
                    ListItem(
                        leadingContent = {
                            QuickActionPositionDiagram(
                                slotIndex = slotIndex,
                                modifier = Modifier.size(40.dp)
                            )
                        },
                        headlineContent = { Text(action.name.ifBlank { "Unnamed Action" }) },
                        supportingContent = {
                            Text(
                                action.prompt.take(60).let { if (action.prompt.length > 60) "$it…" else it },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                if (!bubbleEnabled) { onDisabledTap(); return@IconButton }
                                val updated = currentQuickActions.filter { it.id != action.id }
                                onSaveActions(updated)
                                if (editingSlot == slotIndex) editingSlot = null
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.clickable(enabled = true) {
                            if (!bubbleEnabled) { onDisabledTap(); return@clickable }
                            editingSlot = if (isEditing) null else slotIndex
                        }
                    )

                    if (isEditing) {
                        HorizontalDivider()
                        QuickActionEditor(
                            action = action,
                            agents = agents,
                            onSave = { updated ->
                                // Replace by id — safe for legacy data (same reason as delete).
                                val list = currentQuickActions
                                    .filter { it.id != action.id }
                                    .toMutableList()
                                    .also { it.add(updated.copy(slotIndex = slotIndex)) }
                                onSaveActions(list)
                                editingSlot = null
                            },
                            onCancel = { editingSlot = null }
                        )
                    }
                }
            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionEditor(
    action: QuickActionConfig,
    // Feature 2: list of available agents for per-action agent selection.
    agents: List<AgentConfig> = emptyList(),
    onSave: (QuickActionConfig) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(action.name) }
    var prompt by rememberSaveable { mutableStateOf(action.prompt) }
    var selectedIcon by rememberSaveable { mutableStateOf(action.iconKey) }
    var takeScreenshot by rememberSaveable { mutableStateOf(action.takeScreenshot) }
    var conversationName by rememberSaveable { mutableStateOf(action.conversationName) }
    // Feature 2: null = "Use Active Agent" (default); non-null = dedicated agent id.
    var selectedAgentId by rememberSaveable { mutableStateOf(action.agentId) }
    var agentDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Name
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Action Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Prompt
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt") },
            placeholder = { Text("What should the assistant do?") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )

        // Icon picker
        Text("Icon", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            QuickActionIconKey.entries.forEach { key ->
                val isSelected = key == selectedIcon
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                             else null,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { selectedIcon = key }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            quickActionIconForKey(key),
                            contentDescription = key.displayName,
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Screenshot toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Capture Screenshot", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Take a screenshot when this action is triggered",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = takeScreenshot, onCheckedChange = { takeScreenshot = it })
        }

        // Conversation name
        val defaultConvName = "${name.trim().ifBlank { "Action" }}-Session"
        OutlinedTextField(
            value = conversationName,
            onValueChange = { conversationName = it },
            label = { Text("Conversation Name") },
            placeholder = { Text(defaultConvName) },
            supportingText = { Text("Leave blank to use \"$defaultConvName\"") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Feature 2: agent picker — "Use Active Agent" or a specific assistant
        if (agents.isNotEmpty()) {
            Text("Starting Assistant", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val selectedAgent = agents.firstOrNull { it.id == selectedAgentId }
            ExposedDropdownMenuBox(
                expanded = agentDropdownExpanded,
                onExpandedChange = { agentDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedAgent?.name ?: "Use Active Agent",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = agentDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = agentDropdownExpanded,
                    onDismissRequest = { agentDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Use Active Agent") },
                        onClick = { selectedAgentId = null; agentDropdownExpanded = false }
                    )
                    agents.forEach { agent ->
                        DropdownMenuItem(
                            text = { Text(agent.name) },
                            onClick = { selectedAgentId = agent.id; agentDropdownExpanded = false }
                        )
                    }
                }
            }
        }

        // Save / Cancel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    onSave(
                        action.copy(
                            name = name.trim(),
                            prompt = prompt.trim(),
                            iconKey = selectedIcon,
                            takeScreenshot = takeScreenshot,
                            conversationName = conversationName.trim(),
                            // Feature 2: save the selected agent id (null = use active agent).
                            agentId = selectedAgentId,
                        )
                    )
                },
                enabled = name.isNotBlank() && prompt.isNotBlank()
            ) { Text("Save") }
        }
    }
}

/**
 * Feature 3: mini dot diagram that shows the position of [slotIndex] in the quick-access menu,
 * relative to the floating bubble. Assumes bubble is on the right side (canonical view).
 *
 * Slot layout (fill order):
 *   SLOT1 — diagonal-0 (lower-left of bubble, closest)
 *   SLOT2 — bottom-0   (directly below bubble, closest)
 *   SLOT3 — diagonal-1 (lower-left, farther)
 *   SLOT4 — bottom-1   (directly below, farther)
 */
@Composable
private fun QuickActionPositionDiagram(slotIndex: Int, modifier: Modifier = Modifier) {
    val primary   = MaterialTheme.colorScheme.primary
    val outline   = MaterialTheme.colorScheme.outlineVariant
    val surface   = MaterialTheme.colorScheme.surfaceVariant
    val sin45     = (sqrt(2.0) / 2.0).toFloat()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        val buR  = w * 0.13f        // bubble radius
        val dotR = w * 0.07f        // inactive dot radius
        val actR = w * 0.10f        // active (highlighted) dot radius
        val dist1 = buR + w * 0.12f + dotR   // distance centre-to-dot for closest items
        val dist3 = dist1 + dotR * 2f + w * 0.06f  // farther diagonal distance

        // Positions of all 5 interactive dots (OPEN_APP + SLOT1–SLOT4)
        // Assumes bubble is on the right → diagonal goes lower-left
        val openAppPos  = Offset(cx, cy - dist1)
        val slot1Pos    = Offset(cx - sin45 * dist1,  cy + sin45 * dist1)
        val slot2Pos    = Offset(cx,                  cy + dist1)
        val slot3Pos    = Offset(cx - sin45 * dist3,  cy + sin45 * dist3)
        val slot4Pos    = Offset(cx,                  cy + dist3)

        val slotPositions = listOf(slot1Pos, slot2Pos, slot3Pos, slot4Pos)

        // Bubble circle
        drawCircle(color = surface,   radius = buR, center = Offset(cx, cy))
        drawCircle(color = outline,   radius = buR, center = Offset(cx, cy),
            style = Stroke(width = 1.5f))

        // Open App dot (always dim — not a configurable slot)
        drawCircle(color = outline, radius = dotR, center = openAppPos)

        // Slot dots — highlight the current slot
        slotPositions.forEachIndexed { i, pos ->
            if (i == slotIndex) {
                drawCircle(color = primary, radius = actR, center = pos)
            } else {
                drawCircle(color = outline, radius = dotR, center = pos)
            }
        }
    }
}

private fun quickActionIconForKey(key: QuickActionIconKey): ImageVector = when (key) {
    QuickActionIconKey.BOLT         -> Icons.Outlined.Bolt
    QuickActionIconKey.STAR         -> Icons.Outlined.Star
    QuickActionIconKey.BOOKMARK     -> Icons.Outlined.Bookmark
    QuickActionIconKey.SEARCH       -> Icons.Outlined.Search
    QuickActionIconKey.EDIT         -> Icons.Outlined.Edit
    QuickActionIconKey.CODE         -> Icons.Outlined.Code
    QuickActionIconKey.AUTO_AWESOME -> Icons.Outlined.AutoAwesome
    QuickActionIconKey.PSYCHOLOGY   -> Icons.Outlined.Psychology
    QuickActionIconKey.SUMMARIZE    -> Icons.Outlined.Summarize
    QuickActionIconKey.FLASH_ON     -> Icons.Outlined.FlashOn
    QuickActionIconKey.TUNE         -> Icons.Outlined.Tune
    QuickActionIconKey.ROCKET       -> Icons.Outlined.RocketLaunch
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() }
    )
}

private fun resolveAppVersionName(context: Context): String {
    val versionName = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    return versionName?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.settings_version_unknown)
}
