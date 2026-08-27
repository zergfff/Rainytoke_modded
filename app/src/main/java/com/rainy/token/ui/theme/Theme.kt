package com.rainy.token.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 雨晴风格主题。
 *
 * 支持多套主题配色（strawberry/sky/mint/lilac/material_you）。
 * Material You 在 Android 12+ 使用系统动态配色。
 */
@Composable
fun RainyTokenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeKey: String = "strawberry",
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = ThemePresets.get(themeKey, darkTheme, context)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = buildRainyTypography(fontScale),
        content = content
    )
}

/**
 * 暗色模式自适应的文本颜色。
 * 浅色模式下返回暖黑/暖灰，深色模式下自动切换到浅色。
 */
@Composable
fun inkWarm(): Color = if (isSystemInDarkTheme()) DarkInkWarm else InkWarm

@Composable
fun inkMuted(): Color = if (isSystemInDarkTheme()) DarkInkMuted else InkMuted
