package com.mad.screenagent.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.mad.screenagent.data.model.AppColorTheme

@Composable
fun UaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorTheme: AppColorTheme = AppColorTheme.DEFAULT,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) colorTheme.darkScheme() else colorTheme.lightScheme(),
        typography = Typography,
        shapes = UaiShapes,
        content = content
    )
}
