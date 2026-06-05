package com.mad.screenagent

import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.FileAttachmentContext
import com.mad.screenagent.shared.streaming.ImageAttachment
import com.mad.screenagent.shared.streaming.contentWithFileContext
import com.mad.screenagent.data.db.MessageEntity
import com.mad.screenagent.data.db.attachmentMemoryJsonOrNull
import com.mad.screenagent.data.db.attachmentMemoryOrNull
import com.mad.screenagent.data.db.imageAttachmentsJsonOrNull
import com.mad.screenagent.data.db.toChatMessage
import com.mad.screenagent.shared.streaming.AttachmentImageMemory
import com.mad.screenagent.shared.streaming.AttachmentTurnMemory
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
    fun imageAttachmentParsing_skipsMalformedRowsWithoutCrashing() {
        val message = MessageEntity(
            id = "m4",
            conversationId = "c1",
            role = "user",
            content = "Who is this?",
            createdAt = 4L,
            imagesJson = """[{"mimeType":"image/png"},{"base64":"image-a","mimeType":"image/png"}]"""
        )

        assertEquals(
            listOf(ImageAttachment(base64 = "image-a", mimeType = "image/png")),
            message.toChatMessage().images
        )
    }

    @Test
    fun attachmentMemoryParsing_skipsMalformedRowsWithoutCrashing() {
        val message = MessageEntity(
            id = "m5",
            conversationId = "c1",
            role = "user",
            content = "Who is this?",
            createdAt = 5L,
            attachmentMemoryJson = """
                {
                  "generatedAt": 123,
                  "generatedByProvider": "openai",
                  "generatedByModel": "vision-model",
                  "images": [
                    {"index": 0, "label": "Image 1"},
                    {"index": 1, "label": "Image 2", "summary": "A person wearing a dark jacket."}
                  ]
                }
            """.trimIndent()
        )

        assertEquals(
            AttachmentTurnMemory(
                generatedAt = 123,
                generatedByProvider = "openai",
                generatedByModel = "vision-model",
                images = listOf(
                    AttachmentImageMemory(
                        index = 1,
                        label = "Image 2",
                        summary = "A person wearing a dark jacket."
                    )
                )
            ),
            message.attachmentMemoryOrNull()
        )
    }

    @Test
    fun attachmentMemoryJsonOrNull_serializesStableFieldNamesForRelease() {
        val memory = AttachmentTurnMemory(
            generatedAt = 10,
            generatedByProvider = "provider",
            generatedByModel = "model",
            fallbackReason = "fallback",
            images = listOf(
                AttachmentImageMemory(
                    index = 0,
                    label = "Image 1",
                    summary = "A screenshot."
                )
            )
        )

        val json = attachmentMemoryJsonOrNull(memory)

        assertTrue(json!!.contains("\"generatedAt\""))
        assertTrue(json.contains("\"generatedByProvider\""))
        assertTrue(json.contains("\"images\""))
        assertEquals(
            memory,
            MessageEntity(
                id = "m6",
                conversationId = "c1",
                role = "user",
                content = "What is this?",
                createdAt = 6L,
                attachmentMemoryJson = json
            ).attachmentMemoryOrNull()
        )
    }

    @Test
    fun imageAttachmentsJsonOrNull_returnsNullForEmptyImageList() {
        assertNull(imageAttachmentsJsonOrNull(emptyList()))
    }
}
