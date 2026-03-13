package com.example.uai.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!hasOverlayPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.overlay_permission_required),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                stringResource(R.string.overlay_permission_message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }) { Text("Grant") }
                    }
                }
            }

            ListItem(
                leadingContent = { Icon(Icons.Default.BubbleChart, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.feature_mini_chat)) },
                supportingContent = {
                    Text(
                        if (bubbleEnabled) {
                            stringResource(R.string.mini_chat_active)
                        } else {
                            stringResource(R.string.mini_chat_inactive)
                        },
                        color = if (bubbleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Switch(
                        checked = bubbleEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !hasOverlayPermission) {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                            } else {
                                viewModel.setBubbleEnabled(enabled)
                                if (enabled) FloatingBubbleService.startService(context)
                                else FloatingBubbleService.stopService(context)
                            }
                        },
                        enabled = hasOverlayPermission || !bubbleEnabled
                    )
                }
            )
            HorizontalDivider()

            Spacer(Modifier.height(4.dp))
            Text(
                "Color Theme",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
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

            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text("App version 1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeCard(theme: AppColorTheme, isSelected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        border = if (isSelected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(76.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(theme.previewColorArgb)),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Text(theme.emoji, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Text(
                theme.displayName,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
