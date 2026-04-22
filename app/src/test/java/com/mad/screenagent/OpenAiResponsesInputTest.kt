package com.mad.screenagent

import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.FileAttachmentContext
import com.mad.screenagent.shared.streaming.ImageAttachment
import com.mad.screenagent.shared.streaming.responsesApiInputContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesInputTest {

    @Test
    fun responsesApiInputContent_preservesImagesForVisionTurns() {
        val content = responsesApiInputContent(
            ChatMessage(
                role = "user",
                content = "What is in this screenshot?",
                images = listOf(ImageAttachment(base64 = "abc123", mimeType = "image/png"))
            )
        )
        assertTrue(content is List<*>)
        val parts = content as List<*>
        val imagePart = parts[0] as Map<*, *>
        val textPart = parts[1] as Map<*, *>

        assertEquals("input_image", imagePart["type"])
        assertEquals("data:image/png;base64,abc123", imagePart["image_url"])
        assertEquals("input_text", textPart["type"])
        assertEquals("What is in this screenshot?", textPart["text"])
    }

    @Test
    fun responsesApiInputContent_keepsAttachedFileContextInTextBlock() {
        val content = responsesApiInputContent(
            ChatMessage(
                role = "user",
                content = "Summarize this",
                images = listOf(ImageAttachment(base64 = "xyz")),
                fileAttachment = FileAttachmentContext(
                    displayName = "notes.txt",
                    extractedText = "Hello from the file"
                )
            )
        )
        assertTrue(content is List<*>)
        val parts = content as List<*>
        val textPart = parts.last() as Map<*, *>

        val text = textPart["text"] as String
        assertTrue(text.contains("<attached_file name=\"notes.txt\">"))
        assertTrue(text.contains("Hello from the file"))
    }
}
