package com.mad.screenagent.data.model

import java.util.UUID

/**
 * Selectable icon set for custom quick actions.
 * Each entry maps to a Material icon via [BubbleQuickAccessMenu].
 */
enum class QuickActionIconKey(val displayName: String) {
    BOLT("Bolt"),
    STAR("Star"),
    BOOKMARK("Bookmark"),
    SEARCH("Search"),
    EDIT("Edit"),
    CODE("Code"),
    AUTO_AWESOME("Auto Awesome"),
    PSYCHOLOGY("Psychology"),
    SUMMARIZE("Summarize"),
    FLASH_ON("Flash"),
    TUNE("Tune"),
    ROCKET("Rocket"),
}

/**
 * Configuration for a user-defined quick action attached to the floating bubble.
 *
 * @param id                Stable UUID for this action.
 * @param name              Display label shown in settings and as the radial-menu tooltip.
 * @param prompt            Implicit prompt prepended to the screenshot (or sent alone when [takeScreenshot] is false).
 * @param iconKey           Which icon to show in the radial menu.
 * @param takeScreenshot    Whether to capture a screenshot before sending.
 * @param conversationName  Name of the dedicated conversation. Defaults to "[name]-Session".
 *                          The service looks for an existing conversation with this exact name;
 *                          if not found, a new one is created.  If the user renames that
 *                          conversation in the app the next trigger will create a fresh session.
 */
data class QuickActionConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val prompt: String = "",
    val iconKey: QuickActionIconKey = QuickActionIconKey.BOLT,
    val takeScreenshot: Boolean = true,
    val conversationName: String = "",
    // Feature 2: Optional dedicated assistant for this action.
    // null = use the currently active agent (default behaviour).
    // If the referenced agent is later deleted the service falls back to the active agent.
    val agentId: String? = null,
    // Explicit slot position (0–3).  null = legacy/unassigned — forSlot() falls back to list index.
    // Gson deserialises missing fields as null for nullable types, so old data is backward-compatible.
    val slotIndex: Int? = null,
) {
    /** Effective conversation name: user-set value or auto-derived from action name. */
    fun effectiveConversationName(): String =
        conversationName.trim().ifBlank { "${name.trim()}-Session" }
}

/**
 * Returns the [QuickActionConfig] assigned to [slot] (0–3), or null if that slot is empty.
 *
 * New data: matched by [QuickActionConfig.slotIndex].
 * Legacy data (all slotIndex == null): falls back to list position for backward compatibility.
 */
fun List<QuickActionConfig>.forSlot(slot: Int): QuickActionConfig? =
    firstOrNull { it.slotIndex == slot }
        ?: if (all { it.slotIndex == null }) getOrNull(slot) else null
