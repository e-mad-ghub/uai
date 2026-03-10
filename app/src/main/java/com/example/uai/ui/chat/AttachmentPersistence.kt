package com.example.uai.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID

fun persistImageAttachment(
    context: Context,
    imageBase64: String
): String? = runCatching {
    val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
    val dir = File(context.filesDir, "message_attachments").apply { mkdirs() }
    val file = File(dir, "image_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    file.writeBytes(bytes)
    Uri.fromFile(file).toString()
}.getOrNull()
