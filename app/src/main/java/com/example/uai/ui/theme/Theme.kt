package com.example.uai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.uai.data.model.AppColorTheme

@Composable
fun UaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorTheme: AppColorTheme = AppColorTheme.TERRACOTTA,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) colorTheme.darkScheme() else colorTheme.lightScheme(),
        typography = Typography,
        content = content
    )
}
