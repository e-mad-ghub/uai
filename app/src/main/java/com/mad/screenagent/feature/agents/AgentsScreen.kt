package com.mad.screenagent.feature.agents

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mad.screenagent.R
import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.data.model.canHandleImageRequests
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mad.screenagent.design.components.ProductEmptyStateCard
import com.mad.screenagent.design.components.ProductPill
import com.mad.screenagent.design.components.ProductScreenIntro
import com.mad.screenagent.design.components.ProductTopBarTitle

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

    // Local list used for drag reorder — stays in sync with VM when not dragging
    val localAgents = remember { mutableStateListOf<AgentConfig>() }
    var isDragging by remember { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingOffsetY by remember { mutableStateOf(0f) }

    LaunchedEffect(uiState.agents) {
        if (!isDragging) {
            localAgents.clear()
            localAgents.addAll(uiState.agents)
        }
    }

    val lazyListState = rememberLazyListState()

    // Auto-scroll when dragging near the top or bottom edge of the list.
    // Runs a ~60fps loop while a drag is active; compensates draggingOffsetY so
    // the card stays visually pinned under the finger as the list scrolls.
    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        while (isDragging) {
            val draggingItemInfo = lazyListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == draggingId }
            if (draggingItemInfo != null) {
                val viewport = lazyListState.layoutInfo.viewportEndOffset.toFloat()
                val scrollZone = viewport * 0.18f
                val itemTop = draggingItemInfo.offset + draggingOffsetY
                val itemBottom = itemTop + draggingItemInfo.size

                val scrollAmount = when {
                    itemTop < scrollZone ->
                        -((scrollZone - itemTop) / scrollZone * 25f).coerceAtLeast(2f)
                    itemBottom > viewport - scrollZone ->
                        ((itemBottom - (viewport - scrollZone)) / scrollZone * 25f).coerceAtLeast(2f)
                    else -> 0f
                }
                if (scrollAmount != 0f) {
                    val actualScrolled = lazyListState.scrollBy(scrollAmount)
                    // Shift the visual offset to keep the card under the finger
                    draggingOffsetY -= actualScrolled
                }
            }
            delay(16L)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    ProductTopBarTitle(
                        title = stringResource(R.string.feature_agents),
                        subtitle = stringResource(R.string.screen_assistants_subtitle)
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
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAgent) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.assistants_add_assistant)
                )
            }
        }
    ) { padding ->
        if (localAgents.isEmpty() && uiState.agents.isEmpty()) {
            AssistantsEmptyState(
                onAddAgent = onAddAgent,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            )
        } else {
            LazyColumn(
                state = lazyListState,
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
                    ProductScreenIntro(
                        eyebrow = stringResource(R.string.feature_agents),
                        title = stringResource(R.string.assistants_choose_default_title),
                        body = stringResource(R.string.assistants_choose_default_body)
                    )
                }

                items(localAgents, key = { it.id }) { agent ->
                    val isThisDragging = agent.id == draggingId
                    val haptic = LocalHapticFeedback.current

                    AgentItem(
                        agent = agent,
                        isActive = agent.id == uiState.activeAgentId,
                        isDragging = isThisDragging,
                        onSetActive = { viewModel.setActiveAgent(agent.id) },
                        onEdit = { onEditAgent(agent.id) },
                        onDuplicate = { onDuplicateAgent(agent.id) },
                        replacementDefaultName = if (agent.id == uiState.activeAgentId) {
                            localAgents.firstOrNull { it.id != agent.id }?.name
                        } else null,
                        onDelete = { viewModel.deleteAgent(agent) },
                        modifier = Modifier
                            // Non-dragged items animate to their new positions;
                            // the dragged item is positioned manually via graphicsLayer.
                            .then(if (!isThisDragging) Modifier.animateItem() else Modifier)
                            .zIndex(if (isThisDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isThisDragging) draggingOffsetY else 0f
                                // Slight scale-up so the card visually "lifts"
                                val scale = if (isThisDragging) 1.035f else 1f
                                scaleX = scale
                                scaleY = scale
                            }
                            .pointerInput(agent.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = agent.id
                                        draggingOffsetY = 0f
                                        isDragging = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        draggingOffsetY += dragAmount.y

                                        val draggingIdx = localAgents.indexOfFirst { it.id == draggingId }
                                        if (draggingIdx < 0) return@detectDragGesturesAfterLongPress

                                        val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                                        val draggingItemInfo = visibleItems.firstOrNull { it.key == draggingId }
                                            ?: return@detectDragGesturesAfterLongPress
                                        // Current visual center of the dragged card
                                        val draggingCenter = draggingItemInfo.offset +
                                            draggingItemInfo.size / 2 + draggingOffsetY.toInt()

                                        // Position-based check — no direction lock, works in both directions
                                        if (draggingIdx < localAgents.lastIndex) {
                                            val nextAgent = localAgents[draggingIdx + 1]
                                            val nextInfo = visibleItems.firstOrNull { it.key == nextAgent.id }
                                            if (nextInfo != null && draggingCenter > nextInfo.offset + nextInfo.size / 2) {
                                                val adj = nextInfo.offset - draggingItemInfo.offset
                                                localAgents.add(draggingIdx + 1, localAgents.removeAt(draggingIdx))
                                                draggingOffsetY -= adj
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                return@detectDragGesturesAfterLongPress // one swap per event
                                            }
                                        }
                                        if (draggingIdx > 0) {
                                            val prevAgent = localAgents[draggingIdx - 1]
                                            val prevInfo = visibleItems.firstOrNull { it.key == prevAgent.id }
                                            if (prevInfo != null && draggingCenter < prevInfo.offset + prevInfo.size / 2) {
                                                val adj = draggingItemInfo.offset - prevInfo.offset
                                                localAgents.add(draggingIdx - 1, localAgents.removeAt(draggingIdx))
                                                draggingOffsetY += adj
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                return@detectDragGesturesAfterLongPress // one swap per event
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        isDragging = false
                                        draggingId = null
                                        draggingOffsetY = 0f
                                        viewModel.reorderAgents(localAgents.toList())
                                    },
                                    onDragCancel = {
                                        isDragging = false
                                        draggingId = null
                                        draggingOffsetY = 0f
                                        localAgents.clear()
                                        localAgents.addAll(uiState.agents)
                                    }
                                )
                            }
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
    isDragging: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    replacementDefaultName: String?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        onClick = onEdit,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = if (isDragging) 8.dp else 2.dp
        )
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
                    // Token usage line (only shown when there is a limit OR some usage)
                    val currentMonth = remember { SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) }
                    val effectiveUsed = if (agent.tokenUsedMonth == currentMonth) agent.tokenUsed else 0L
                    val tokenLimit = agent.tokenLimit
                    if (tokenLimit != null || effectiveUsed > 0L) {
                        val usageText = if (tokenLimit != null) {
                            "(${formatTokenCount(effectiveUsed)}/${formatTokenCount(tokenLimit)} total)"
                        } else {
                            "(${formatTokenCount(effectiveUsed)} total tokens)"
                        }
                        val usageColor = when {
                            tokenLimit != null && effectiveUsed >= tokenLimit * 0.85 -> Color(0xFFD32F2F)
                            tokenLimit != null && effectiveUsed >= tokenLimit * 0.60 -> Color(0xFFF57C00)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            usageText,
                            style = MaterialTheme.typography.labelSmall,
                            color = usageColor
                        )
                    }
                    Text(
                        assistantSummary(agent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProductPill(label = agent.provider.displayName, emphasized = true)
                        ProductPill(label = agent.model)
                    }
                    Text(
                        buildString {
                            append(if (agent.apiKey.isBlank()) "Connection pending" else "Configured")
                            append(" · ")
                            append(if (agent.canHandleImageRequests()) "Images enabled" else "Text only")
                        },
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
