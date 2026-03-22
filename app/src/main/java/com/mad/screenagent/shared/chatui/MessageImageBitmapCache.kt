package com.mad.screenagent.shared.chatui

import androidx.compose.ui.graphics.ImageBitmap

object MessageImageBitmapCache {
    private const val MaxEntries = 24
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MaxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MaxEntries
        }
    }

    @Synchronized
    fun get(uri: String): ImageBitmap? = cache[uri]

    @Synchronized
    fun put(uri: String, bitmap: ImageBitmap) {
        cache[uri] = bitmap
    }
}
