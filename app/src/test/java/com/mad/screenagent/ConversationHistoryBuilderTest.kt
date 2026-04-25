package com.mad.screenagent

import com.mad.screenagent.data.db.MessageEntity
import com.mad.screenagent.data.db.attachmentMemoryJsonOrNull
import com.mad.screenagent.shared.streaming.AttachmentImageMemory
import com.mad.screenagent.shared.streaming.AttachmentTurnMemory
import com.mad.screenagent.shared.streaming.ImageAttachment
import com.mad.screenagent.shared.streaming.buildConversationHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryBuilderTest {

    @Test
    fun buildConversationHistory_keepsRecentRawImageTurnForVisionCapableAssistants() {
        val messages = listOf(
            MessageEntity(
                id = "user-1",
                conversationId = "conv-1",
                role = "user",
                content = "Compare the images",
                createdAt = 1L,
                imagesJson = """[{"base64":"a","mimeType":"image/jpeg"},{"base64":"b","mimeType":"image/jpeg"}]""",
                attachmentMemoryJson = attachmentMemoryJsonOrNull(
                    AttachmentTurnMemory(
                        images = listOf(
                            AttachmentImageMemory(1, "Image 1", "First summary"),
                            AttachmentImageMemory(2, "Image 2", "Second summary")
                        )
                    )
                )
            ),
            MessageEntity(
                id = "assistant-1",
                conversationId = "conv-1",
                role = "assistant",
                content = "I compared them.",
                createdAt = 2L
            ),
            MessageEntity(
                id = "user-2",
                conversationId = "conv-1",
                role = "user",
                content = "Which one is brighter?",
                createdAt = 3L
            )
        )

        val history = buildConversationHistory(
            messages = messages,
            keepMostRecentRawImageTurn = true
        )

        assertEquals(2, history[0].images.size)
        assertEquals("Compare the images", history[0].content)
        assertTrue(history[2].images.isEmpty())
    }

    @Test
    fun buildConversationHistory_replacesRawImageTurnWithMemoryForTextOnlyAssistants() {
        val message = MessageEntity(
            id = "user-1",
            conversationId = "conv-1",
            role = "user",
            content = "Review the images",
            createdAt = 1L,
            imagesJson = attachmentImagesJson(
                listOf(
                    ImageAttachment("a"),
                    ImageAttachment("b")
                )
            ),
            attachmentMemoryJson = attachmentMemoryJsonOrNull(
                AttachmentTurnMemory(
                    images = listOf(
                        AttachmentImageMemory(1, "Image 1", "First summary"),
                        AttachmentImageMemory(2, "Image 2", "Second summary")
                    )
                )
            )
        )

        val history = buildConversationHistory(
            messages = listOf(message),
            keepMostRecentRawImageTurn = false
        )

        assertTrue(history.single().images.isEmpty())
        assertTrue(history.single().content.contains("attached_image_turn_memory"))
        assertTrue(history.single().content.contains("First summary"))
    }

    private fun attachmentImagesJson(images: List<ImageAttachment>): String =
        images.joinToString(prefix = "[", postfix = "]") { image ->
            """{"base64":"${image.base64}","mimeType":"${image.mimeType}"}"""
        }
}
