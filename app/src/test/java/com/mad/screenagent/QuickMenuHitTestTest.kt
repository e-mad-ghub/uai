package com.mad.screenagent

import com.mad.screenagent.feature.bubble.QUICK_MENU_ACTION_GAP_DP
import com.mad.screenagent.feature.bubble.QUICK_MENU_ACTION_ICON_SIZE_DP
import com.mad.screenagent.feature.bubble.QuickMenuItemId
import com.mad.screenagent.feature.bubble.hitTestQuickMenuItemPure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the quick-access menu gesture hit-testing logic.
 *
 * All coordinates use density = 1f (dp == px) so the numbers are
 * human-readable and match the layout constants directly.
 *
 * Screen: 1080 × 2340, bubble at top-right (800, 400), size 64.
 * Bubble centre: (832, 432).
 */
class QuickMenuHitTestTest {

    // ── Shared fixture ────────────────────────────────────────────────────────

    private val screenW     = 1080f
    private val bubbleSize  = 64f
    private val iconSize    = QUICK_MENU_ACTION_ICON_SIZE_DP.toFloat()   // 44
    private val gap         = QUICK_MENU_ACTION_GAP_DP.toFloat()         // 8
    private val half        = iconSize / 2f                              // 22

    // Bubble on the RIGHT side of the screen
    private val cxRight = 832f   // 800 + 64/2
    private val cyRight = 432f   // 400 + 64/2

    // Bubble on the LEFT side of the screen
    private val cxLeft  = 248f   // 216 + 64/2
    private val cyLeft  = 432f

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hitRight(x: Float, y: Float, hasSlot2: Boolean = false) =
        hitTestQuickMenuItemPure(x, y, cxRight, cyRight, bubbleSize, iconSize, gap, screenW, hasSlot2)

    private fun hitLeft(x: Float, y: Float, hasSlot2: Boolean = false) =
        hitTestQuickMenuItemPure(x, y, cxLeft, cyLeft, bubbleSize, iconSize, gap, screenW, hasSlot2)

    // ── Open App (above bubble centre) ────────────────────────────────────────

    @Test
    fun openApp_centerHit_bubbleOnRight() {
        // centre: (cx, cy - bubbleSize/2 - half - gap) = (832, 432 - 32 - 22 - 8) = (832, 370)
        assertEquals(QuickMenuItemId.OPEN_APP, hitRight(832f, 370f))
    }

    @Test
    fun openApp_centerHit_bubbleOnLeft() {
        assertEquals(QuickMenuItemId.OPEN_APP, hitLeft(248f, 370f))
    }

    @Test
    fun openApp_withinHitRadius_returnsHit() {
        // Hit radius = iconSize * 0.75 = 33. Move 20px off-centre — still a hit.
        assertEquals(QuickMenuItemId.OPEN_APP, hitRight(832f + 20f, 370f))
    }

    @Test
    fun openApp_outsideHitRadius_returnsNull() {
        // 40px off-centre > 33px radius
        assertNull(hitRight(832f + 40f, 370f))
    }

    // ── More Details (side, closer to bubble) ─────────────────────────────────

    @Test
    fun moreDetails_centerHit_bubbleOnRight() {
        // centre: (cx - bubbleSize/2 - half - gap, cy) = (832 - 32 - 22 - 8, 432) = (770, 432)
        assertEquals(QuickMenuItemId.MORE_DETAILS, hitRight(770f, 432f))
    }

    @Test
    fun moreDetails_centerHit_bubbleOnLeft() {
        // centre: (cx + bubbleSize/2 + gap + half, cy) = (248 + 32 + 8 + 22, 432) = (310, 432)
        assertEquals(QuickMenuItemId.MORE_DETAILS, hitLeft(310f, 432f))
    }

    // ── Translate (side, farther from bubble) ─────────────────────────────────

    @Test
    fun translate_centerHit_bubbleOnRight() {
        // centre: (cx - bubbleSize/2 - iconSize*1.5 - gap*2.5, cy)
        //       = (832 - 32 - 66 - 20, 432) = (714, 432)
        assertEquals(QuickMenuItemId.TRANSLATE, hitRight(714f, 432f))
    }

    @Test
    fun translate_centerHit_bubbleOnLeft() {
        // centre: (cx + bubbleSize/2 + iconSize*1.5 + gap*2.5, cy)
        //       = (248 + 32 + 66 + 20, 432) = (366, 432)
        assertEquals(QuickMenuItemId.TRANSLATE, hitLeft(366f, 432f))
    }

    // ── Slot 1 (below bubble centre) ──────────────────────────────────────────

    @Test
    fun slot1_centerHit() {
        // s1y = cy + bubbleSize/2 + gap + half = 432 + 32 + 8 + 22 = 494
        assertEquals(QuickMenuItemId.SLOT1, hitRight(832f, 494f))
    }

    // ── Slot 2 (below slot 1) ─────────────────────────────────────────────────

    @Test
    fun slot2_centerHit_whenHasSlot2() {
        // s2y = s1y + iconSize + gap = 494 + 44 + 8 = 546
        assertEquals(QuickMenuItemId.SLOT2, hitRight(832f, 546f, hasSlot2 = true))
    }

    @Test
    fun slot2_notHit_whenNoSlotConfigured() {
        // hasSlot2=false means no slot1 either (empty quick actions) — slot2 not in hit targets
        assertNull(hitRight(832f, 546f, hasSlot2 = false))
    }

    // ── Miss ──────────────────────────────────────────────────────────────────

    @Test
    fun noItemNearFinger_returnsNull() {
        // Centre of screen, far from all items
        assertNull(hitRight(540f, 432f))
    }
}
