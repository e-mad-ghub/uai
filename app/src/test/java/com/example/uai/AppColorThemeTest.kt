package com.example.uai

import com.example.uai.data.model.AppColorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [AppColorTheme].
 *
 * Feature: settings
 * Risk: If a theme is accidentally renamed or removed, persisted user preferences
 * (stored by enum name in DataStore) will silently fall back to DEFAULT without
 * surfacing any compile-time error.
 */
class AppColorThemeTest {

    @Test
    fun fromKey_returnsMatchingThemeForKnownKey() {
        for (theme in AppColorTheme.entries) {
            assertEquals(
                "fromKey(\"${theme.name}\") should return $theme",
                theme,
                AppColorTheme.fromKey(theme.name)
            )
        }
    }

    @Test
    fun fromKey_returnsDefaultForUnknownKey() {
        assertEquals(AppColorTheme.DEFAULT, AppColorTheme.fromKey("completely-unknown"))
        assertEquals(AppColorTheme.DEFAULT, AppColorTheme.fromKey(""))
    }

    @Test
    fun fromKey_isCaseSensitive() {
        // Stored keys come from enum.name which is uppercase; lowercase must not match
        val result = AppColorTheme.fromKey(AppColorTheme.MIDNIGHT.name.lowercase())
        assertEquals(AppColorTheme.DEFAULT, result)
    }

    @Test
    fun allThemesHaveUniqueNames() {
        val names = AppColorTheme.entries.map { it.name }
        assertEquals("All theme names must be unique", names.size, names.toSet().size)
    }

    @Test
    fun allThemesHaveUniquePreviewColors() {
        val colors = AppColorTheme.entries.map { it.previewColorArgb }
        assertEquals("All theme preview colors must be unique", colors.size, colors.toSet().size)
    }

    @Test
    fun defaultConstantIsOneOfTheKnownThemes() {
        assertTrue(AppColorTheme.entries.contains(AppColorTheme.DEFAULT))
    }

    @Test
    fun allThemesHaveNonEmptyDisplayNames() {
        for (theme in AppColorTheme.entries) {
            assertTrue("Theme $theme must have a non-blank displayName", theme.displayName.isNotBlank())
        }
    }
}
