package com.rainy.token.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/**
 * 雨晴风格全局背景。
 *
 * 使用 MaterialTheme.colorScheme 的 background / surface 做渐变，
 * 跟随主题配色，不再硬编码粉色调。
 */
@Composable
fun RainyBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val brush = Brush.verticalGradient(
        colors = listOf(colorScheme.background, colorScheme.surface)
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(brush)
    ) {
        content()
    }
}
