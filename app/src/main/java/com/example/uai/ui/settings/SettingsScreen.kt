package com.example.uai.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.R
import com.example.uai.data.model.AppColorTheme
import com.example.uai.service.FloatingBubbleService
import com.example.uai.service.MiniChatScreenshotAccessibilityService
import com.example.uai.ui.components.ProductPill
import com.example.uai.ui.components.ProductScreenIntro
import com.example.uai.ui.components.ProductTopBarTitle
import com.example.uai.ui.theme.lightScheme

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
    var showMiniChatTipsSheet by rememberSaveable { mutableStateOf(false) }
    val isMiniChatConfigured = bubbleEnabled
    val isMiniChatActive = bubbleEnabled && hasOverlayPermission
    val showOverlayPermissionCard = !hasOverlayPermission &&
        (showOverlayPermissionCallout || isMiniChatConfigured)
    val showMiniChatScreenshotsCard =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
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

    if (showMiniChatTipsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMiniChatTipsSheet = false }
        ) {
            MiniChatTipsSheet(
                onDismiss = { showMiniChatTipsSheet = false }
            )
        }
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

            if (showOverlayPermissionCard) {
                OutlinedCard {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.overlay_permission_required),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(
                                    if (isMiniChatConfigured) {
                                        R.string.mini_chat_permission_needed_message
                                    } else {
                                        R.string.overlay_permission_message
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.mini_chat_requires_overlay),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = ::openOverlaySettings) {
                            Text(stringResource(R.string.action_allow_display_over_other_apps))
                        }
                    }
                }
            }

            ElevatedCard {
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
            }

            MiniChatTipsRow(
                onClick = { showMiniChatTipsSheet = true }
            )

            if (showMiniChatScreenshotsCard) {
                MiniChatScreenshotsCard(
                    isEnabled = isScreenshotAccessibilityEnabled,
                    onOpenAccessibilitySettings = {
                        MiniChatScreenshotAccessibilityService.openSettings(context)
                    }
                )
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
private fun MiniChatTipsRow(onClick: () -> Unit) {
    OutlinedCard(onClick = onClick) {
        ListItem(
            headlineContent = {
                Text(stringResource(R.string.mini_chat_tips_row_title))
            },
            supportingContent = {
                Text(
                    stringResource(R.string.mini_chat_tips_row_body),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            trailingContent = {
                Text(
                    stringResource(R.string.action_view),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
    }
}

@Composable
private fun MiniChatTipsSheet(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.mini_chat_tips_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            stringResource(R.string.mini_chat_tips_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MiniChatTipRow(
            title = stringResource(R.string.mini_chat_tip_enter_title),
            body = stringResource(R.string.mini_chat_tip_enter_body)
        )
        MiniChatTipRow(
            title = stringResource(R.string.mini_chat_tip_minimize_title),
            body = stringResource(R.string.mini_chat_tip_minimize_body)
        )
        MiniChatTipRow(
            title = stringResource(R.string.mini_chat_tip_rooms_title),
            body = stringResource(R.string.mini_chat_tip_rooms_body)
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.action_got_it))
        }
    }
}

@Composable
private fun MiniChatTipRow(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MiniChatScreenshotsCard(
    isEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit
) {
    ElevatedCard {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            headlineContent = {
                Text(stringResource(R.string.mini_chat_screenshots_title))
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProductPill(
                        label = stringResource(
                            if (isEnabled) {
                                R.string.mini_chat_screenshots_status_enabled
                            } else {
                                R.string.mini_chat_screenshots_status_needs_setup
                            }
                        ),
                        emphasized = isEnabled
                    )
                    Text(
                        stringResource(
                            if (isEnabled) {
                                R.string.mini_chat_screenshots_enabled_body
                            } else {
                                R.string.mini_chat_screenshots_disabled_body
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            trailingContent = {
                TextButton(onClick = onOpenAccessibilitySettings) {
                    Text(
                        stringResource(
                            if (isEnabled) {
                                R.string.action_manage_screenshot_helper
                            } else {
                                R.string.action_set_up_screenshots
                            }
                        )
                    )
                }
            }
        )
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
