package com.mad.screenagent

import com.mad.screenagent.data.model.AgentConfig
import com.mad.screenagent.shared.streaming.AiProvider
import com.mad.screenagent.shared.streaming.AttachmentTurnMemory
import com.mad.screenagent.shared.streaming.ChatMessage
import com.mad.screenagent.shared.streaming.ImageAttachment
import com.mad.screenagent.shared.streaming.MultiImageConversationRuntime
import com.mad.screenagent.shared.streaming.ProviderFailureKind
import com.mad.screenagent.shared.streaming.StreamChunk
import com.mad.screenagent.shared.streaming.classifyProviderFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiImageConversationRuntimeTest {

    @Test
    fun classifyProviderFailure_detectsMultiImageLimit() {
        val kind = classifyProviderFailure(
            Exception("This model only supports one image per request.")
        )

        assertEquals(ProviderFailureKind.MULTI_IMAGE_LIMIT, kind)
    }

    @Test
    fun classifyProviderFailure_detectsVisionUnsupported() {
        val kind = classifyProviderFailure(
            Exception("Image input is not supported by this model.")
        )

        assertEquals(ProviderFailureKind.VISION_UNSUPPORTED, kind)
    }

    @Test
    fun runtime_fallsBackToTextualizedImageTurnOnMultiImageRefusal() = runBlocking {
        val directAttempts = mutableListOf<List<ChatMessage>>()
        val summaryRequests = mutableListOf<List<ChatMessage>>()
        var persistedMessageId: String? = null
        var persistedMemory: AttachmentTurnMemory? = null

        val runtime = MultiImageConversationRuntime(
            providerFactory = {
                object : AiProvider {
                    override fun streamResponse(
                        messages: List<ChatMessage>,
                        config: AgentConfig
                    ): Flow<StreamChunk> = flow {
                        summaryRequests += messages
                        emit(StreamChunk.Token("summary ${summaryRequests.size}"))
                        emit(StreamChunk.Usage(3, 5))
                        emit(StreamChunk.Done)
                    }
                }
            }
        )

        val emitted = runtime.streamResponse(
            messages = listOf(
                ChatMessage(
                    messageId = "user-1",
                    role = "user",
                    content = "Compare these images",
                    images = listOf(ImageAttachment("a"), ImageAttachment("b"))
                )
            ),
            config = AgentConfig(),
            directStreamFactory = { messages, _ ->
                directAttempts += messages
                flow {
                    if (directAttempts.size == 1) {
                        emit(StreamChunk.Error(Exception("This provider only supports one image per request.")))
                    } else {
                        assertTrue(messages.single().images.isEmpty())
                        assertTrue(messages.single().content.contains("Use the image summaries below"))
                        assertTrue(messages.single().content.contains("summary 1"))
                        assertTrue(messages.single().content.contains("summary 2"))
                        emit(StreamChunk.Token("final answer"))
                        emit(StreamChunk.Done)
                    }
                }
            },
            onAttachmentMemoryGenerated = { messageId, memory ->
                persistedMessageId = messageId
                persistedMemory = memory
            }
        ).toList()

        assertEquals(2, directAttempts.size)
        assertEquals(2, summaryRequests.size)
        assertEquals("user-1", persistedMessageId)
        assertNotNull(persistedMemory)
        assertEquals(2, persistedMemory!!.images.size)
        assertEquals(
            listOf("final answer"),
            emitted.filterIsInstance<StreamChunk.Token>().map { it.text }
        )
        assertEquals(2, emitted.filterIsInstance<StreamChunk.Usage>().size)
    }
}
