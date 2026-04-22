package com.mad.screenagent

import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.FileAttachmentContext
import com.mad.screenagent.shared.streaming.ImageAttachment
import com.mad.screenagent.shared.streaming.contentWithFileContext
import com.mad.screenagent.data.db.MessageEntity
import com.mad.screenagent.data.db.imageAttachmentsJsonOrNull
import com.mad.screenagent.data.db.toChatMessage
import com.mad.screenagent.shared.chatui.buildCopyableMessageText
import com.mad.screenagent.shared.chatui.buildQuotedReplyContext
import com.mad.screenagent.shared.chatui.parseAttachedFileDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAttachmentDisplayTest {

    @Test
    fun structuredAttachmentDisplaysFileChipWithoutRawPromptMarkup() {
        val message = MessageEntity(
            id = "m1",
            conversationId = "c1",
            role = "user",
            content = "Summarize this.",
            createdAt = 1L,
            attachedFileName = "notes.pdf",
            attachedFileText = "Page 1 text"
        )

        val display = parseAttachedFileDisplay(message)

        assertEquals(listOf("notes.pdf"), display.fileNames)
        assertEquals("Summarize this.", display.visibleText)
        assertEquals("[Attached file: notes.pdf]\n\nSummarize this.", buildCopyableMessageText(message))
    }

    @Test
    fun legacyInlineAttachmentMarkupStillParsesForOlderMessages() {
        val message = MessageEntity(
            id = "m2",
            conversationId = "c1",
            role = "user",
            content = "<attached_file name=\"draft.docx\">\nhello\n</attached_file>\n\nExplain this",
            createdAt = 2L
        )

        val display = parseAttachedFileDisplay(message)

        assertEquals(listOf("draft.docx"), display.fileNames)
        assertEquals("Explain this", display.visibleText)
        assertTrue(buildQuotedReplyContext(message).startsWith("> [Attached file: draft.docx]"))
    }

    @Test
    fun chatMessageBuildsProviderContextFromStructuredAttachment() {
        val message = ChatMessage(
            role = "user",
            content = "What matters here?",
            fileAttachment = FileAttachmentContext(
                displayName = "report.txt",
                extractedText = "alpha beta"
            )
        )

        val providerContent = message.contentWithFileContext()

        assertTrue(providerContent.contains("<attached_file name=\"report.txt\">"))
        assertTrue(providerContent.contains("alpha beta"))
        assertTrue(providerContent.endsWith("What matters here?"))
    }

    @Test
    fun imageAttachmentsJsonOrNull_serializesMultipleImagesForMessageHistory() {
        val images = listOf(
            ImageAttachment(base64 = "image-a", mimeType = "image/jpeg"),
            ImageAttachment(base64 = "image-b", mimeType = "image/png")
        )
        val json = imageAttachmentsJsonOrNull(images)
        val message = MessageEntity(
            id = "m3",
            conversationId = "c1",
            role = "user",
            content = "Compare these.",
            createdAt = 3L,
            imagesJson = json
        )

        assertEquals(images, message.toChatMessage().images)
    }

    @Test
    fun imageAttachmentsJsonOrNull_returnsNullForEmptyImageList() {
        assertNull(imageAttachmentsJsonOrNull(emptyList()))
    }
}
