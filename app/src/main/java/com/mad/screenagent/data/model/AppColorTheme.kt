package com.mad.screenagent.data.model

enum class AppColorTheme(
    val displayName: String,
    val previewColorArgb: Long
) {
    TERRACOTTA("Terracotta", 0xFFB5533C),
    OCEAN("Ocean",           0xFF1765A8),
    FOREST("Forest",         0xFF2D6A30),
    LAVENDER("Lavender",     0xFF6750A4),
    SUNSET("Sunset",         0xFFC44B00),
    MIDNIGHT("Midnight",     0xFF3849AB);

    companion object {
        val DEFAULT = MIDNIGHT

        fun fromKey(key: String): AppColorTheme =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
