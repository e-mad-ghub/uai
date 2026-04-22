package com.mad.screenagent

import com.google.gson.Gson
import com.mad.screenagent.data.model.QuickActionConfig
import com.mad.screenagent.data.model.canSaveQuickAction
import com.mad.screenagent.data.model.decideQuickActionExecution
import com.mad.screenagent.data.model.isQuickActionPromptEditable
import com.mad.screenagent.data.model.normalizedQuickActionMediaToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionConfigTest {

    @Test
    fun defaultQuickAction_hasCameraOffAndPromptAutoUseOn() {
        val action = QuickActionConfig()

        assertFalse(action.takePhoto)
        assertTrue(action.usePromptAutomatically)
    }

    @Test
    fun legacyQuickActionJson_defaultsCameraOffAndPromptAutoUseOn() {
        val json = """
            {
              "id": "legacy-action",
              "name": "Summarize",
              "prompt": "Summarize this",
              "iconKey": "SUMMARIZE",
              "takeScreenshot": true,
              "conversationName": "",
              "agentId": null,
              "slotIndex": 0
            }
        """.trimIndent()

        val action = Gson().fromJson(json, QuickActionConfig::class.java)

        assertFalse(action.takePhoto)
        assertTrue(action.usePromptAutomatically)
    }

    @Test
    fun canSaveQuickAction_requiresPromptWhenAutoUseEnabled() {
        assertFalse(
            canSaveQuickAction(
                name = "Explain",
                prompt = " ",
                usePromptAutomatically = true
            )
        )
    }

    @Test
    fun canSaveQuickAction_allowsBlankPromptWhenAutoUseDisabled() {
        assertTrue(
            canSaveQuickAction(
                name = "Explain",
                prompt = " ",
                usePromptAutomatically = false
            )
        )
    }

    @Test
    fun disabledPromptAutoUse_keepsStoredPromptButMakesPromptNotEditable() {
        val action = QuickActionConfig(
            prompt = "Saved prompt",
            usePromptAutomatically = false
        )

        assertEquals("Saved prompt", action.prompt)
        assertFalse(isQuickActionPromptEditable(action.usePromptAutomatically))
    }

    @Test
    fun quickActionDecision_cameraOnlyCanceledDoesNotOpenOrSend() {
        val decision = decideQuickActionExecution(
            usePromptAutomatically = true,
            requestedCamera = true,
            cameraCaptureSucceeded = false,
            capturedAttachmentCount = 0
        )

        assertFalse(decision.shouldOpenMiniChat)
        assertFalse(decision.shouldSendPrompt)
    }

    @Test
    fun quickActionDecision_screenshotPlusCanceledCameraContinues() {
        val decision = decideQuickActionExecution(
            usePromptAutomatically = true,
            requestedCamera = true,
            cameraCaptureSucceeded = false,
            capturedAttachmentCount = 1
        )

        assertTrue(decision.shouldOpenMiniChat)
        assertTrue(decision.shouldSendPrompt)
    }

    @Test
    fun quickActionDecision_cameraSuccessWithAutoUseSendsPrompt() {
        val decision = decideQuickActionExecution(
            usePromptAutomatically = true,
            requestedCamera = true,
            cameraCaptureSucceeded = true,
            capturedAttachmentCount = 1
        )

        assertTrue(decision.shouldOpenMiniChat)
        assertTrue(decision.shouldSendPrompt)
    }

    @Test
    fun quickActionDecision_cameraSuccessWithoutAutoUseWaitsInMiniChat() {
        val decision = decideQuickActionExecution(
            usePromptAutomatically = false,
            requestedCamera = true,
            cameraCaptureSucceeded = true,
            capturedAttachmentCount = 1
        )

        assertTrue(decision.shouldOpenMiniChat)
        assertFalse(decision.shouldSendPrompt)
    }

    @Test
    fun quickActionDecision_screenshotAndCameraCanBothBeRequested() {
        val decision = decideQuickActionExecution(
            usePromptAutomatically = true,
            requestedCamera = true,
            cameraCaptureSucceeded = true,
            capturedAttachmentCount = 2
        )

        assertTrue(decision.shouldOpenMiniChat)
        assertTrue(decision.shouldSendPrompt)
    }

    @Test
    fun normalizedQuickActionMediaToggles_cameraWinsWhenBothEnabled() {
        val normalized = normalizedQuickActionMediaToggles(
            takeScreenshot = true,
            takePhoto = true
        )

        assertFalse(normalized.takeScreenshot)
        assertTrue(normalized.takePhoto)
    }

    @Test
    fun normalizedQuickActionMediaToggles_allowsBothOff() {
        val normalized = normalizedQuickActionMediaToggles(
            takeScreenshot = false,
            takePhoto = false
        )

        assertFalse(normalized.takeScreenshot)
        assertFalse(normalized.takePhoto)
    }
}
