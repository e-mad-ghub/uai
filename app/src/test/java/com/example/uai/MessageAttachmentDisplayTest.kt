package com.example.uai

import com.example.uai.ai.ChatMessage
import com.example.uai.ai.FileAttachmentContext
import com.example.uai.ai.contentWithFileContext
import com.example.uai.data.db.MessageEntity
import com.example.uai.ui.chat.buildCopyableMessageText
import com.example.uai.ui.chat.buildQuotedReplyContext
import com.example.uai.ui.chat.parseAttachedFileDisplay
import org.junit.Assert.assertEquals
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
}
