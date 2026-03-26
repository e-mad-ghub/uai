package com.mad.screenagent.feature.bubble

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mad.screenagent.data.model.QuickActionConfig
import com.mad.screenagent.data.model.QuickActionIconKey
import com.mad.screenagent.data.model.forSlot
import kotlin.math.sqrt

// ── Item IDs (shared with FloatingBubbleService for gesture hit-testing) ─────

object QuickMenuItemId {
    const val OPEN_APP = "open_app"
    // Feature 3: 4 custom-action slots replace the former hardcoded More Details / Translate.
    // Fill order (closest-first, diagonal priority): SLOT1 → SLOT2 → SLOT3 → SLOT4.
    const val SLOT1    = "slot1"   // Diagonal pos 0 (closest, diagonal)
    const val SLOT2    = "slot2"   // Bottom  pos 0 (closest, below)
    const val SLOT3    = "slot3"   // Diagonal pos 1 (farther, diagonal)
    const val SLOT4    = "slot4"   // Bottom  pos 1 (farther, below)
}

// ── Sizing constants (internal so FloatingBubbleService can hit-test) ─────────

internal const val QUICK_MENU_ACTION_ICON_SIZE_DP = 44
internal const val QUICK_MENU_ACTION_GAP_DP       = 8

// sin(45°) = √2/2 — side items fan out along a 45° diagonal from vertical.
private val SIN45 = (sqrt(2.0) / 2.0).toFloat()

// ── Hit-testing (extracted for testability) ───────────────────────────────────

/**
 * Maps a raw screen coordinate to a [QuickMenuItemId], or null if the finger is
 * not close enough to any item.
 *
 * All parameters are in the same unit (pixels).  Pass [density] = 1f and use
 * dp values directly when writing unit tests.
 *
 * @param bubbleCenterX  Centre X of the floating bubble.
 * @param bubbleCenterY  Centre Y of the floating bubble.
 * @param bubbleSizePx   Diameter of the bubble in px.
 * @param iconSizePx     Diameter of each action icon in px.
 * @param gapPx          Gap between bubble edge and first icon in px.
 * @param screenWidthPx  Full screen width in px (used to decide left/right side).
 */
internal fun hitTestQuickMenuItemPure(
    rawX: Float,
    rawY: Float,
    bubbleCenterX: Float,
    bubbleCenterY: Float,
    bubbleSizePx: Float,
    iconSizePx: Float,
    gapPx: Float,
    screenWidthPx: Float,
): String? {
    val onRight  = bubbleCenterX > screenWidthPx / 2f
    val half     = iconSizePx / 2f
    val hitR     = iconSizePx * 0.75f
    val sin45    = SIN45
    val dirX     = if (onRight) -sin45 else sin45
    val step     = iconSizePx + gapPx
    val sideBase = bubbleSizePx / 2f + gapPx + half  // bubble edge → first diagonal icon centre
    val bottomY  = bubbleCenterY + bubbleSizePx / 2f + gapPx + half

    // Feature 3: all 4 slots are always visible (empty ones show a "+" placeholder).
    data class IC(val id: String, val x: Float, val y: Float)
    val items = listOf(
        IC(QuickMenuItemId.OPEN_APP, bubbleCenterX, bubbleCenterY - bubbleSizePx / 2f - half - gapPx),
        IC(QuickMenuItemId.SLOT1,    bubbleCenterX + dirX * sideBase,        bubbleCenterY + sin45 * sideBase),
        IC(QuickMenuItemId.SLOT2,    bubbleCenterX,                          bottomY),
        IC(QuickMenuItemId.SLOT3,    bubbleCenterX + dirX * (sideBase + step), bubbleCenterY + sin45 * (sideBase + step)),
        IC(QuickMenuItemId.SLOT4,    bubbleCenterX,                          bottomY + iconSizePx + gapPx),
    )
    return items.firstOrNull { (_, ix, iy) ->
        val dx = rawX - ix; val dy = rawY - iy
        dx * dx + dy * dy <= hitR * hitR
    }?.id
}

// ── Icon mapping ──────────────────────────────────────────────────────────────

@Composable
fun quickActionIconVector(key: QuickActionIconKey): ImageVector = when (key) {
    QuickActionIconKey.BOLT          -> Icons.Outlined.Bolt
    QuickActionIconKey.STAR          -> Icons.Outlined.Star
    QuickActionIconKey.BOOKMARK      -> Icons.Outlined.Bookmark
    QuickActionIconKey.SEARCH        -> Icons.Outlined.Search
    QuickActionIconKey.EDIT          -> Icons.Outlined.Edit
    QuickActionIconKey.CODE          -> Icons.Outlined.Code
    QuickActionIconKey.AUTO_AWESOME  -> Icons.Outlined.AutoAwesome
    QuickActionIconKey.PSYCHOLOGY    -> Icons.Outlined.Psychology
    QuickActionIconKey.SUMMARIZE     -> Icons.Outlined.Summarize
    QuickActionIconKey.FLASH_ON      -> Icons.Outlined.FlashOn
    QuickActionIconKey.TUNE          -> Icons.Outlined.Tune
    QuickActionIconKey.ROCKET        -> Icons.Outlined.RocketLaunch
}

// ── Public entry point ────────────────────────────────────────────────────────

/**
 * Full-screen radial quick-access menu shown on long-press of the floating bubble.
 *
 * @param hoveredItemId ID of the item currently under the user's finger (from service
 *                      touch listener), or null. Used for the press-and-slide gesture.
 */
@Composable
fun BubbleQuickAccessMenu(
    bubbleX: Int,
    bubbleY: Int,
    bubbleSizePx: Int,
    screenWidthPx: Int,
    quickActions: List<QuickActionConfig>,
    hoveredItemId: String?,
    onOpenApp: () -> Unit,
    onCustomAction: (QuickActionConfig) -> Unit,
    onCreateAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val bubbleCenterXDp = with(density) { (bubbleX + bubbleSizePx / 2).toDp() }
    val bubbleCenterYDp = with(density) { (bubbleY + bubbleSizePx / 2).toDp() }
    val bubbleSizeDp    = with(density) { bubbleSizePx.toDp() }

    val bubbleOnRight = bubbleX + bubbleSizePx / 2 > screenWidthPx / 2

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val menuAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(160),
        label = "menuAlpha"
    )

    Box(modifier = Modifier.fillMaxSize().alpha(menuAlpha)) {

        // Scrim — tap to dismiss
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }
        )

        // ── TOP: Open App ──────────────────────────────────────────────────
        QuickAccessItem(
            icon        = Icons.Outlined.Launch,
            label       = "Open App",
            labelSide   = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
            animDelay   = 0,
            visible     = visible,
            isHovered   = hoveredItemId == QuickMenuItemId.OPEN_APP,
            modifier    = Modifier.offset {
                IntOffset(
                    x = (bubbleCenterXDp - QUICK_MENU_ACTION_ICON_SIZE_DP.dp / 2).roundToPx(),
                    y = (bubbleCenterYDp - bubbleSizeDp / 2 - QUICK_MENU_ACTION_ICON_SIZE_DP.dp - QUICK_MENU_ACTION_GAP_DP.dp).roundToPx()
                )
            },
            onClick = onOpenApp
        )

        // ── Feature 3: 4 configurable action slots — all always visible ──────
        // Empty slots show a "+" placeholder that opens settings to add an action.
        // Lookup uses forSlot() which supports both new data (explicit slotIndex)
        // and legacy data (list-position fallback).
        val sideDir: Float = if (bubbleOnRight) -SIN45 else SIN45
        val iconDp         = QUICK_MENU_ACTION_ICON_SIZE_DP.dp
        val gapDp          = QUICK_MENU_ACTION_GAP_DP.dp
        val sideBase       = bubbleSizeDp / 2 + gapDp + iconDp / 2
        val sideStep       = iconDp + gapDp

        // SLOT1 — diagonal pos 0
        val slot1Action = quickActions.forSlot(0)
        QuickAccessItem(
            icon          = if (slot1Action != null) quickActionIconVector(slot1Action.iconKey) else Icons.Outlined.Add,
            label         = slot1Action?.name ?: "Add Action",
            labelSide     = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
            animDelay     = 30,
            visible       = visible,
            isHovered     = hoveredItemId == QuickMenuItemId.SLOT1,
            isPlaceholder = slot1Action == null,
            modifier      = Modifier.offset {
                val cx = bubbleCenterXDp + sideBase * sideDir
                val cy = bubbleCenterYDp + sideBase * SIN45
                IntOffset(x = (cx - iconDp / 2).roundToPx(), y = (cy - iconDp / 2).roundToPx())
            },
            onClick = if (slot1Action != null) ({ onCustomAction(slot1Action) }) else onCreateAction
        )

        // SLOT2 — bottom pos 0
        val slot2Action = quickActions.forSlot(1)
        QuickAccessItem(
            icon          = if (slot2Action != null) quickActionIconVector(slot2Action.iconKey) else Icons.Outlined.Add,
            label         = slot2Action?.name ?: "Add Action",
            labelSide     = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
            animDelay     = 60,
            visible       = visible,
            isHovered     = hoveredItemId == QuickMenuItemId.SLOT2,
            isPlaceholder = slot2Action == null,
            modifier      = Modifier.offset {
                IntOffset(
                    x = (bubbleCenterXDp - iconDp / 2).roundToPx(),
                    y = (bubbleCenterYDp + bubbleSizeDp / 2 + gapDp).roundToPx()
                )
            },
            onClick = if (slot2Action != null) ({ onCustomAction(slot2Action) }) else onCreateAction
        )

        // SLOT3 — diagonal pos 1
        val slot3Action = quickActions.forSlot(2)
        val dist3 = sideBase + sideStep
        QuickAccessItem(
            icon          = if (slot3Action != null) quickActionIconVector(slot3Action.iconKey) else Icons.Outlined.Add,
            label         = slot3Action?.name ?: "Add Action",
            labelSide     = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
            animDelay     = 90,
            visible       = visible,
            isHovered     = hoveredItemId == QuickMenuItemId.SLOT3,
            isPlaceholder = slot3Action == null,
            modifier      = Modifier.offset {
                val cx = bubbleCenterXDp + dist3 * sideDir
                val cy = bubbleCenterYDp + dist3 * SIN45
                IntOffset(x = (cx - iconDp / 2).roundToPx(), y = (cy - iconDp / 2).roundToPx())
            },
            onClick = if (slot3Action != null) ({ onCustomAction(slot3Action) }) else onCreateAction
        )

        // SLOT4 — bottom pos 1
        val slot4Action = quickActions.forSlot(3)
        QuickAccessItem(
            icon          = if (slot4Action != null) quickActionIconVector(slot4Action.iconKey) else Icons.Outlined.Add,
            label         = slot4Action?.name ?: "Add Action",
            labelSide     = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
            animDelay     = 120,
            visible       = visible,
            isHovered     = hoveredItemId == QuickMenuItemId.SLOT4,
            isPlaceholder = slot4Action == null,
            modifier      = Modifier.offset {
                IntOffset(
                    x = (bubbleCenterXDp - iconDp / 2).roundToPx(),
                    y = (bubbleCenterYDp + bubbleSizeDp / 2 + gapDp + iconDp + gapDp).roundToPx()
                )
            },
            onClick = if (slot4Action != null) ({ onCustomAction(slot4Action) }) else onCreateAction
        )
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

private enum class LabelSide { LEFT, RIGHT }

@Composable
private fun QuickAccessItem(
    icon: ImageVector,
    label: String,
    labelSide: LabelSide,
    animDelay: Int,
    visible: Boolean,
    isHovered: Boolean = false,
    modifier: Modifier = Modifier,
    isPlaceholder: Boolean = false,
    onClick: () -> Unit,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            kotlinx.coroutines.delay(animDelay.toLong())
            appeared = true
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (appeared) (if (isHovered) 1.15f else 1f) else 0.4f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "itemScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(120),
        label = "itemAlpha"
    )

    Box(
        modifier = modifier
            .zIndex(if (isHovered) 1f else 0f)  // draw above siblings so label isn't covered
            .size(QUICK_MENU_ACTION_ICON_SIZE_DP.dp)
            .scale(scale)
            .alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = when {
                isHovered     -> MaterialTheme.colorScheme.primary
                isPlaceholder -> MaterialTheme.colorScheme.surfaceVariant
                else          -> MaterialTheme.colorScheme.primaryContainer
            },
            shadowElevation = if (isHovered) 12.dp else 6.dp,
            modifier = Modifier
                .size(QUICK_MENU_ACTION_ICON_SIZE_DP.dp)
                .shadow(if (isHovered) 12.dp else 6.dp, CircleShape)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = when {
                        isHovered     -> MaterialTheme.colorScheme.onPrimary
                        isPlaceholder -> MaterialTheme.colorScheme.onSurfaceVariant
                        else          -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Label — visible only when hovered
        if (appeared && isHovered) {
            val labelModifier = when (labelSide) {
                LabelSide.LEFT  -> Modifier
                    .align(Alignment.CenterStart)
                    .requiredSize(0.dp)
                    .offset(x = -QUICK_MENU_ACTION_GAP_DP.dp)
                    .wrapContentSize(Alignment.CenterEnd, unbounded = true)
                LabelSide.RIGHT -> Modifier
                    .align(Alignment.CenterEnd)
                    .requiredSize(0.dp)
                    .offset(x = QUICK_MENU_ACTION_GAP_DP.dp)
                    .wrapContentSize(Alignment.CenterStart, unbounded = true)
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isHovered) MaterialTheme.colorScheme.primary
                        else           MaterialTheme.colorScheme.inverseSurface,
                modifier = labelModifier
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = if (isHovered) MaterialTheme.colorScheme.onPrimary
                            else           MaterialTheme.colorScheme.inverseOnSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    maxLines = 1
                )
            }
        }
    }
}
