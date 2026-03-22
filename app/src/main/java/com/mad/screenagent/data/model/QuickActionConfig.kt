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
 * @param assignedAgentId   Agent to use for this action; null = use last active / default agent.
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
    val assignedAgentId: String? = null,
    val takeScreenshot: Boolean = true,
    val conversationName: String = "",
) {
    /** Effective conversation name: user-set value or auto-derived from action name. */
    fun effectiveConversationName(): String =
        conversationName.trim().ifBlank { "${name.trim()}-Session" }
}
