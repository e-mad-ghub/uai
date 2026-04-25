package com.mad.screenagent

import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.FileAttachmentContext
import com.mad.screenagent.shared.streaming.ImageAttachment
import com.mad.screenagent.shared.streaming.compressHistory
import com.mad.screenagent.shared.streaming.retainCurrentTurnAttachmentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCompressorTest {

    @Test
    fun retainCurrentTurnAttachmentsOnly_keepsMostRecentAttachmentTurnAvailableOnFollowUps() {
        val messages = listOf(
            ChatMessage(
                role = "user",
                content = "Analyze this file",
                fileAttachment = FileAttachmentContext(
                    displayName = "notes.txt",
                    extractedText = "Very long file contents"
                )
            ),
            ChatMessage(role = "assistant", content = "Here is the summary"),
            ChatMessage(role = "user", content = "Give me the short version")
        )

        val normalized = retainCurrentTurnAttachmentsOnly(messages)

        assertNotNull(normalized[0].fileAttachment)
        assertNull(normalized[1].fileAttachment)
        assertNull(normalized[2].fileAttachment)
    }

    @Test
    fun compressHistory_keepsAttachmentOnlyOnCurrentUserTurn() {
        val messages = listOf(
            ChatMessage(
                role = "user",
                content = "Older image turn",
                fileAttachment = FileAttachmentContext(
                    displayName = "spec.pdf",
                    extractedText = "Older attachment contents"
                )
            ),
            ChatMessage(role = "assistant", content = "I reviewed it"),
            ChatMessage(
                role = "user",
                content = "Current file turn",
                fileAttachment = FileAttachmentContext(
                    displayName = "latest.txt",
                    extractedText = "Current attachment contents"
                )
            )
        )

        val compressed = compressHistory(messages)

        assertEquals(3, compressed.size)
        assertNull(compressed[0].fileAttachment)
        assertNull(compressed[1].fileAttachment)
        assertNotNull(compressed[2].fileAttachment)
        assertEquals("latest.txt", compressed[2].fileAttachment?.displayName)
    }

    @Test
    fun compressHistory_canStripAllRawAttachmentsWhenDisabled() {
        val messages = listOf(
            ChatMessage(
                role = "user",
                content = "Image turn",
                images = listOf(ImageAttachment("img-base64"))
            ),
            ChatMessage(role = "assistant", content = "Seen"),
            ChatMessage(role = "user", content = "Follow up")
        )

        val compressed = compressHistory(
            messages = messages,
            keepMostRecentRawAttachments = false
        )

        assertNull(compressed[0].fileAttachment)
        assertNull(compressed[1].fileAttachment)
        assertNull(compressed[2].fileAttachment)
        assertTrue(compressed[0].images.isEmpty())
    }
}
