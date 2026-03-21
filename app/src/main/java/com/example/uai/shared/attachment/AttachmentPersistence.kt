package com.example.uai.shared.attachment

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID

private const val MESSAGE_ATTACHMENTS_DIR = "message_attachments"

fun persistImageAttachment(
    context: Context,
    imageBase64: String
): String? = runCatching {
    val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
    val dir = messageAttachmentsDir(context).apply { mkdirs() }
    val file = File(dir, "image_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    file.writeBytes(bytes)
    Uri.fromFile(file).toString()
}.getOrNull()

fun deletePersistedImageAttachment(
    context: Context,
    imageUri: String?
) {
    if (imageUri.isNullOrBlank()) return

    runCatching {
        val parsed = Uri.parse(imageUri)
        if (!parsed.scheme.equals("file", ignoreCase = true)) return

        val attachmentDir = messageAttachmentsDir(context).canonicalFile
        val target = File(parsed.path ?: return).canonicalFile
        val attachmentRoot = attachmentDir.path + File.separator
        if (target.path.startsWith(attachmentRoot)) {
            target.delete()
        }
    }
}

private fun messageAttachmentsDir(context: Context): File =
    File(context.filesDir, MESSAGE_ATTACHMENTS_DIR)
