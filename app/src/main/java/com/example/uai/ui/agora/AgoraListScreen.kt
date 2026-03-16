package com.example.uai.ui.agora

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.uai.R
import com.example.uai.data.db.ConversationEntity
import com.example.uai.ui.components.ProductHeroCard
import com.example.uai.ui.components.ProductEmptyStateCard
import com.example.uai.ui.components.ProductPill
import com.example.uai.ui.components.ProductTopBarTitle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgoraListScreen(
    viewModel: AgoraListViewModel,
    onOpenRoom: (String) -> Unit,
    onCreateRoom: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rooms by viewModel.agoraRooms.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    ProductTopBarTitle(
                        title = stringResource(R.string.feature_rooms),
                        subtitle = stringResource(R.string.screen_agora_subtitle)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateRoom,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_room)) }
            )
        }
    ) { padding ->
        if (rooms.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                ProductEmptyStateCard(
                    title = stringResource(R.string.rooms_empty_title),
                    body = stringResource(R.string.rooms_empty_message),
                    actionLabel = stringResource(R.string.new_room),
                    onAction = onCreateRoom,
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    titleAlign = TextAlign.Center,
                    bodyAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ProductHeroCard(
                        eyebrow = stringResource(R.string.feature_rooms),
                        title = stringResource(R.string.agora_hero_title),
                        body = stringResource(R.string.agora_hero_body),
                        actionLabel = stringResource(R.string.new_room),
                        onAction = onCreateRoom
                    )
                }
                items(rooms, key = { it.id }) { room ->
                    AgoraRoomItem(
                        room = room,
                        onClick = { onOpenRoom(room.id) },
                        onDelete = { viewModel.delete(room) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // FAB clearance
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgoraRoomItem(
    room: ConversationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val agentCount = remember(room.agoraAgentIds) {
        if (room.agoraAgentIds.isBlank()) 0
        else try {
            val type = object : TypeToken<List<String>>() {}.type
            (Gson().fromJson<List<String>>(room.agoraAgentIds, type)).size
        } catch (_: Exception) { 0 }
    }

    val dateStr = remember(room.updatedAt) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(room.updatedAt))
    }

    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true }),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        room.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProductPill(label = "$agentCount agents", emphasized = agentCount >= 3)
                        Text(
                            dateStr,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (room.isPinned) {
                    ProductPill(label = "Pinned", emphasized = true)
                } else {
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { showMenu = false; onDelete() }
            )
        }
    }
}
