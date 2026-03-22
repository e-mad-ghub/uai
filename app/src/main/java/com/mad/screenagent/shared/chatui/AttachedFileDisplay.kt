package com.mad.screenagent.shared.chatui

import com.mad.screenagent.data.db.MessageEntity

private val attachedFileRegex =
    Regex("""(?s)<attached_file name="([^"]+)">.*?</attached_file>\s*""")

data class AttachedFileDisplay(
    val fileNames: List<String>,
    val visibleText: String
)

private fun parseLegacyAttachedFileDisplay(content: String): AttachedFileDisplay {
    val fileNames = attachedFileRegex.findAll(content)
        .mapNotNull { match -> match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } }
        .toList()

    val visibleText = attachedFileRegex.replace(content, "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    return AttachedFileDisplay(
        fileNames = fileNames,
        visibleText = visibleText
    )
}

fun parseAttachedFileDisplay(message: MessageEntity): AttachedFileDisplay {
    val fileName = message.attachedFileName?.takeIf { it.isNotBlank() }
    if (fileName != null) {
        return AttachedFileDisplay(
            fileNames = listOf(fileName),
            visibleText = message.content.trim()
        )
    }
    return parseLegacyAttachedFileDisplay(message.content)
}

fun buildCopyableMessageText(message: MessageEntity): String {
    val display = parseAttachedFileDisplay(message)
    val attachmentSummary = display.fileNames
        .joinToString(separator = "\n") { "[Attached file: $it]" }

    return listOf(attachmentSummary.takeIf { it.isNotBlank() }, display.visibleText.takeIf { it.isNotBlank() })
        .filterNotNull()
        .joinToString(separator = "\n\n")
}

fun buildReplyPreviewText(message: MessageEntity, maxChars: Int): String {
    val source = buildCopyableMessageText(message)
        .replace("\n", " ")
        .trim()
    if (source.isBlank()) return ""
    return if (source.length > maxChars) "${source.take(maxChars)}…" else source
}

fun buildQuotedReplyContext(message: MessageEntity, maxChars: Int = 200): String {
    val preview = buildReplyPreviewText(message, maxChars)
    return if (preview.isBlank()) "" else "> $preview\n\n"
}
