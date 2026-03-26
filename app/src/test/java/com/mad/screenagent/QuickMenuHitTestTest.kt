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
 *
 * All 4 slots are always present in the hit area (empty ones show a "+" placeholder).
 * Slot layout:
 *   SLOT1 — diagonal-0: 45° lower-inward from bubble (closest)
 *   SLOT2 — bottom-0:   directly below bubble (closest)
 *   SLOT3 — diagonal-1: 45° lower-inward (farther)
 *   SLOT4 — bottom-1:   directly below bubble (farther)
 */
class QuickMenuHitTestTest {

    // ── Shared fixture ────────────────────────────────────────────────────────

    private val screenW    = 1080f
    private val bubbleSize = 64f
    private val iconSize   = QUICK_MENU_ACTION_ICON_SIZE_DP.toFloat()   // 44
    private val gap        = QUICK_MENU_ACTION_GAP_DP.toFloat()         // 8
    private val half       = iconSize / 2f                              // 22

    // Bubble on the RIGHT side of the screen
    private val cxRight = 832f   // 800 + 64/2
    private val cyRight = 432f   // 400 + 64/2

    // Bubble on the LEFT side of the screen
    private val cxLeft = 248f    // 216 + 64/2
    private val cyLeft = 432f

    private val sin45    = (kotlin.math.sqrt(2.0) / 2.0).toFloat()
    private val sideBase = bubbleSize / 2f + gap + half  // 62
    private val sideStep = iconSize + gap                // 52

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hitRight(x: Float, y: Float) =
        hitTestQuickMenuItemPure(x, y, cxRight, cyRight, bubbleSize, iconSize, gap, screenW)

    private fun hitLeft(x: Float, y: Float) =
        hitTestQuickMenuItemPure(x, y, cxLeft, cyLeft, bubbleSize, iconSize, gap, screenW)

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

    // ── SLOT1 (diagonal-0) ────────────────────────────────────────────────────
    // dist = sideBase = 62
    // right: cx = 832 - sin45*62 ≈ 788.2, cy = 432 + sin45*62 ≈ 475.8

    @Test
    fun slot1_centerHit_bubbleOnRight() {
        val cx = cxRight - sin45 * sideBase
        val cy = cyRight + sin45 * sideBase
        assertEquals(QuickMenuItemId.SLOT1, hitRight(cx, cy))
    }

    @Test
    fun slot1_centerHit_bubbleOnLeft() {
        val cx = cxLeft + sin45 * sideBase
        val cy = cyLeft + sin45 * sideBase
        assertEquals(QuickMenuItemId.SLOT1, hitLeft(cx, cy))
    }

    // ── SLOT2 (bottom-0) ──────────────────────────────────────────────────────
    // bottomY = cy + bubbleSize/2 + gap + half = 432 + 32 + 8 + 22 = 494

    @Test
    fun slot2_centerHit_bubbleOnRight() {
        val bottomY = cyRight + bubbleSize / 2f + gap + half  // 494
        assertEquals(QuickMenuItemId.SLOT2, hitRight(cxRight, bottomY))
    }

    @Test
    fun slot2_centerHit_bubbleOnLeft() {
        val bottomY = cyLeft + bubbleSize / 2f + gap + half
        assertEquals(QuickMenuItemId.SLOT2, hitLeft(cxLeft, bottomY))
    }

    // ── SLOT3 (diagonal-1) ────────────────────────────────────────────────────
    // dist = sideBase + sideStep = 114
    // right: cx = 832 - sin45*114 ≈ 751.4, cy = 432 + sin45*114 ≈ 512.6

    @Test
    fun slot3_centerHit_bubbleOnRight() {
        val dist = sideBase + sideStep
        val cx = cxRight - sin45 * dist
        val cy = cyRight + sin45 * dist
        assertEquals(QuickMenuItemId.SLOT3, hitRight(cx, cy))
    }

    @Test
    fun slot3_centerHit_bubbleOnLeft() {
        val dist = sideBase + sideStep
        val cx = cxLeft + sin45 * dist
        val cy = cyLeft + sin45 * dist
        assertEquals(QuickMenuItemId.SLOT3, hitLeft(cx, cy))
    }

    // ── SLOT4 (bottom-1) ──────────────────────────────────────────────────────
    // slot4Y = bottomY + iconSize + gap = 494 + 44 + 8 = 546

    @Test
    fun slot4_centerHit_bubbleOnRight() {
        val slot4Y = cyRight + bubbleSize / 2f + gap + half + iconSize + gap  // 546
        assertEquals(QuickMenuItemId.SLOT4, hitRight(cxRight, slot4Y))
    }

    @Test
    fun slot4_centerHit_bubbleOnLeft() {
        val slot4Y = cyLeft + bubbleSize / 2f + gap + half + iconSize + gap
        assertEquals(QuickMenuItemId.SLOT4, hitLeft(cxLeft, slot4Y))
    }

    // ── Miss ──────────────────────────────────────────────────────────────────

    @Test
    fun noItemNearFinger_returnsNull() {
        // Centre of screen, far from all items
        assertNull(hitRight(540f, 432f))
    }
}
