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
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.Translate
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mad.screenagent.data.model.QuickActionConfig
import com.mad.screenagent.data.model.QuickActionIconKey

// ── Item IDs (shared with FloatingBubbleService for gesture hit-testing) ─────

object QuickMenuItemId {
    const val OPEN_APP     = "open_app"
    const val MORE_DETAILS = "more_details"
    const val TRANSLATE    = "translate"
    const val SLOT1        = "slot1"
    const val SLOT2        = "slot2"
}

// ── Sizing constants (internal so FloatingBubbleService can hit-test) ─────────

internal const val QUICK_MENU_ACTION_ICON_SIZE_DP = 44
internal const val QUICK_MENU_ACTION_GAP_DP       = 8

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
 * @param hasSlot2       Whether a second custom-action slot should be included.
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
    hasSlot2: Boolean,
): String? {
    val onRight = bubbleCenterX > screenWidthPx / 2f
    val half    = iconSizePx / 2f
    val hitR    = iconSizePx * 0.75f

    data class IC(val id: String, val x: Float, val y: Float)
    val items = buildList<IC> {
        add(IC(QuickMenuItemId.OPEN_APP, bubbleCenterX, bubbleCenterY - bubbleSizePx / 2f - half - gapPx))
        val mdX = if (onRight) bubbleCenterX - bubbleSizePx / 2f - half - gapPx
                  else         bubbleCenterX + bubbleSizePx / 2f + gapPx + half
        add(IC(QuickMenuItemId.MORE_DETAILS, mdX, bubbleCenterY))
        val trX = if (onRight) bubbleCenterX - bubbleSizePx / 2f - iconSizePx * 1.5f - gapPx * 2.5f
                  else         bubbleCenterX + bubbleSizePx / 2f + iconSizePx * 1.5f + gapPx * 2.5f
        add(IC(QuickMenuItemId.TRANSLATE, trX, bubbleCenterY))
        val s1y = bubbleCenterY + bubbleSizePx / 2f + gapPx + half
        add(IC(QuickMenuItemId.SLOT1, bubbleCenterX, s1y))
        if (hasSlot2) add(IC(QuickMenuItemId.SLOT2, bubbleCenterX, s1y + iconSizePx + gapPx))
    }
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
    onMoreDetails: () -> Unit,
    onTranslate: () -> Unit,
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

        // ── SIDE: More Details (closer to bubble) ──────────────────────────
        val moreDetsOffsetX = if (bubbleOnRight)
            bubbleCenterXDp - bubbleSizeDp / 2 - QUICK_MENU_ACTION_ICON_SIZE_DP.dp - QUICK_MENU_ACTION_GAP_DP.dp
        else
            bubbleCenterXDp + bubbleSizeDp / 2 + QUICK_MENU_ACTION_GAP_DP.dp

        QuickAccessItem(
            icon       = Icons.Outlined.FindInPage,
            label      = "More Details",
            labelSide  = LabelSide.ABOVE,
            animDelay  = 30,
            visible    = visible,
            isHovered  = hoveredItemId == QuickMenuItemId.MORE_DETAILS,
            modifier   = Modifier.offset {
                IntOffset(
                    x = moreDetsOffsetX.roundToPx(),
                    y = (bubbleCenterYDp - QUICK_MENU_ACTION_ICON_SIZE_DP.dp / 2).roundToPx()
                )
            },
            onClick = onMoreDetails
        )

        // ── SIDE: Translate (one step farther from bubble) ─────────────────
        val translateOffsetX = if (bubbleOnRight)
            bubbleCenterXDp - bubbleSizeDp / 2 - (QUICK_MENU_ACTION_ICON_SIZE_DP * 2 + QUICK_MENU_ACTION_GAP_DP * 2.5f).dp
        else
            bubbleCenterXDp + bubbleSizeDp / 2 + (QUICK_MENU_ACTION_ICON_SIZE_DP + QUICK_MENU_ACTION_GAP_DP * 2.5f).dp

        QuickAccessItem(
            icon       = Icons.Outlined.Translate,
            label      = "Translate",
            labelSide  = LabelSide.ABOVE,
            animDelay  = 60,
            visible    = visible,
            isHovered  = hoveredItemId == QuickMenuItemId.TRANSLATE,
            modifier   = Modifier.offset {
                IntOffset(
                    x = translateOffsetX.roundToPx(),
                    y = (bubbleCenterYDp - QUICK_MENU_ACTION_ICON_SIZE_DP.dp / 2).roundToPx()
                )
            },
            onClick = onTranslate
        )

        // ── BOTTOM: Custom action slots ────────────────────────────────────
        val slot1Action = quickActions.getOrNull(0)
        val slot2Action = quickActions.getOrNull(1)
        val slot1Y = bubbleCenterYDp + bubbleSizeDp / 2 + QUICK_MENU_ACTION_GAP_DP.dp

        if (slot1Action != null) {
            QuickAccessItem(
                icon       = quickActionIconVector(slot1Action.iconKey),
                label      = slot1Action.name,
                labelSide  = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
                animDelay  = 30,
                visible    = visible,
                isHovered  = hoveredItemId == QuickMenuItemId.SLOT1,
                modifier   = Modifier.offset {
                    IntOffset(
                        x = (bubbleCenterXDp - QUICK_MENU_ACTION_ICON_SIZE_DP.dp / 2).roundToPx(),
                        y = slot1Y.roundToPx()
                    )
                },
                onClick = { onCustomAction(slot1Action) }
            )
        } else {
            QuickAccessItem(
                icon       = Icons.Outlined.Add,
                label      = "Create Action",
                labelSide  = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
                animDelay  = 30,
                visible    = visible,
                isHovered  = hoveredItemId == QuickMenuItemId.SLOT1,
                isPlaceholder = true,
                modifier   = Modifier.offset {
                    IntOffset(
                        x = (bubbleCenterXDp - QUICK_MENU_ACTION_ICON_SIZE_DP.dp / 2).roundToPx(),
                        y = slot1Y.roundToPx()
                    )
                },
                onClick = onCreateAction
            )
        }

        if (slot1Action != null) {
            val slot2Y = slot1Y + QUICK_MENU_ACTION_ICON_SIZE_DP.dp + QUICK_MENU_ACTION_GAP_DP.dp
            if (slot2Action != null) {
                QuickAccessItem(
                    icon       = quickActionIconVector(slot2Action.iconKey),
                    label      = slot2Action.name,
                    labelSide  = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
                    animDelay  = 60,
                    visible    = visible,
                    isHovered  = hoveredItemId == QuickMenuItemId.SLOT2,
                    modifier   = Modifier.offset {
                        IntOffset(
                            x = (bubbleCenterXDp - QUICK_MENU_ACTION_ICON_SIZE_DP.dp / 2).roundToPx(),
                            y = slot2Y.roundToPx()
                        )
                    },
                    onClick = { onCustomAction(slot2Action) }
                )
            } else {
                QuickAccessItem(
                    icon       = Icons.Outlined.Add,
                    label      = "Create Action",
                    labelSide  = if (bubbleOnRight) LabelSide.LEFT else LabelSide.RIGHT,
                    animDelay  = 60,
                    visible    = visible,
                    isHovered  = hoveredItemId == QuickMenuItemId.SLOT2,
                    isPlaceholder = true,
                    modifier   = Modifier.offset {
                        IntOffset(
                            x = (bubbleCenterXDp - QUICK_MENU_ACTION_ICON_SIZE_DP.dp / 2).roundToPx(),
                            y = slot2Y.roundToPx()
                        )
                    },
                    onClick = onCreateAction
                )
            }
        }
    }
}

// ── Internal helpers ──────────────────────────────────────────────────────────

private enum class LabelSide { LEFT, RIGHT, ABOVE }

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
                LabelSide.ABOVE -> Modifier
                    .align(Alignment.TopCenter)
                    .requiredSize(0.dp)
                    .offset(y = -QUICK_MENU_ACTION_GAP_DP.dp)
                    .wrapContentSize(Alignment.BottomCenter, unbounded = true)
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
