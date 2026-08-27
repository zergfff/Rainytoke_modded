package com.rainy.token.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 五套主题配色方案，加上 Material You 动态配色。
 * 调用 get(themeKey, isDark) 获取对应的 ColorScheme。
 */
object ThemePresets {

    // ─── Strawberry Pink (默认) ───
    private val StrawberryPinkLight = lightColorScheme(
        primary = StrawberryPink,
        onPrimary = PureWhite,
        primaryContainer = StrawberryPinkSoft,
        onPrimaryContainer = InkWarm,
        secondary = StrawberryPinkDark,
        onSecondary = PureWhite,
        secondaryContainer = Color(0xFFFFE4EC),
        onSecondaryContainer = InkWarm,
        tertiary = StatusGreen,
        onTertiary = PureWhite,
        background = CherryPinkLight,
        onBackground = InkWarm,
        surface = PureWhite,
        onSurface = InkWarm,
        surfaceVariant = SnowWhite,
        onSurfaceVariant = InkMuted,
        outline = InkOutline,
        outlineVariant = Color(0xFFEFE0E5),
        error = StatusRed,
        onError = PureWhite
    )

    private val StrawberryPinkDarkScheme = darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkBackground,
        primaryContainer = StrawberryPinkDark,
        onPrimaryContainer = PureWhite,
        secondary = StrawberryPink,
        onSecondary = DarkBackground,
        secondaryContainer = Color(0xFF4A2E3A),
        onSecondaryContainer = DarkOnSurface,
        tertiary = StatusGreen,
        onTertiary = DarkBackground,
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = Color(0xFF352329),
        onSurfaceVariant = Color(0xFFC9B8BE),
        outline = Color(0xFF4D3A42),
        outlineVariant = Color(0xFF3A2A30),
        error = Color(0xFFFF6B8E),
        onError = PureWhite
    )

    // ─── Sky Blue ───
    private val SkyBlueLight = lightColorScheme(
        primary = Color(0xFF42A5F5),
        onPrimary = PureWhite,
        primaryContainer = Color(0xFFBBDEFB),
        onPrimaryContainer = Color(0xFF0D47A1),
        secondary = Color(0xFF1E88E5),
        onSecondary = PureWhite,
        secondaryContainer = Color(0xFFE3F2FD),
        onSecondaryContainer = Color(0xFF1565C0),
        tertiary = Color(0xFF66BB6A),
        onTertiary = PureWhite,
        background = Color(0xFFF0F7FF),
        onBackground = Color(0xFF1A2B42),
        surface = PureWhite,
        onSurface = Color(0xFF1A2B42),
        surfaceVariant = Color(0xFFE8F0FE),
        onSurfaceVariant = Color(0xFF5F7389),
        outline = Color(0xFFB0C4D8),
        outlineVariant = Color(0xFFD6E4F0),
        error = StatusRed,
        onError = PureWhite
    )

    private val SkyBlueDark = darkColorScheme(
        primary = Color(0xFF90CAF9),
        onPrimary = Color(0xFF0D47A1),
        primaryContainer = Color(0xFF1E88E5),
        onPrimaryContainer = Color(0xFFE3F2FD),
        secondary = Color(0xFF64B5F6),
        onSecondary = Color(0xFF0D47A1),
        secondaryContainer = Color(0xFF1A3A5C),
        onSecondaryContainer = Color(0xFFBBDEFB),
        tertiary = Color(0xFF81C784),
        onTertiary = Color(0xFF1B5E20),
        background = Color(0xFF0E1A2B),
        onBackground = Color(0xFFD6E4F0),
        surface = Color(0xFF152238),
        onSurface = Color(0xFFD6E4F0),
        surfaceVariant = Color(0xFF1A2D45),
        onSurfaceVariant = Color(0xFF90B0C8),
        outline = Color(0xFF2A4A68),
        outlineVariant = Color(0xFF1E3A55),
        error = Color(0xFFFF8A80),
        onError = PureWhite
    )

    // ─── Mint Green ───
    private val MintGreenLight = lightColorScheme(
        primary = Color(0xFF66BB6A),
        onPrimary = PureWhite,
        primaryContainer = Color(0xFFC8E6C9),
        onPrimaryContainer = Color(0xFF1B5E20),
        secondary = Color(0xFF43A047),
        onSecondary = PureWhite,
        secondaryContainer = Color(0xFFE8F5E9),
        onSecondaryContainer = Color(0xFF2E7D32),
        tertiary = Color(0xFF42A5F5),
        onTertiary = PureWhite,
        background = Color(0xFFF0F9F0),
        onBackground = Color(0xFF1A3C1A),
        surface = PureWhite,
        onSurface = Color(0xFF1A3C1A),
        surfaceVariant = Color(0xFFE8F5E9),
        onSurfaceVariant = Color(0xFF5A7A5A),
        outline = Color(0xFFB0D0B0),
        outlineVariant = Color(0xFFD6EED6),
        error = StatusRed,
        onError = PureWhite
    )

    private val MintGreenDark = darkColorScheme(
        primary = Color(0xFFA5D6A7),
        onPrimary = Color(0xFF1B5E20),
        primaryContainer = Color(0xFF43A047),
        onPrimaryContainer = Color(0xFFE8F5E9),
        secondary = Color(0xFF81C784),
        onSecondary = Color(0xFF1B5E20),
        secondaryContainer = Color(0xFF1A3A20),
        onSecondaryContainer = Color(0xFFC8E6C9),
        tertiary = Color(0xFF90CAF9),
        onTertiary = Color(0xFF0D47A1),
        background = Color(0xFF0E1F0E),
        onBackground = Color(0xFFD6EED6),
        surface = Color(0xFF152A15),
        onSurface = Color(0xFFD6EED6),
        surfaceVariant = Color(0xFF1A3020),
        onSurfaceVariant = Color(0xFF90B890),
        outline = Color(0xFF2A4A2A),
        outlineVariant = Color(0xFF1E381E),
        error = Color(0xFFFF8A80),
        onError = PureWhite
    )

    // ─── Lilac Purple ───
    private val LilacPurpleLight = lightColorScheme(
        primary = Color(0xFFAB47BC),
        onPrimary = PureWhite,
        primaryContainer = Color(0xFFE1BEE7),
        onPrimaryContainer = Color(0xFF4A148C),
        secondary = Color(0xFF8E24AA),
        onSecondary = PureWhite,
        secondaryContainer = Color(0xFFF3E5F5),
        onSecondaryContainer = Color(0xFF6A1B9A),
        tertiary = Color(0xFF66BB6A),
        onTertiary = PureWhite,
        background = Color(0xFFF8F0FA),
        onBackground = Color(0xFF2D1A35),
        surface = PureWhite,
        onSurface = Color(0xFF2D1A35),
        surfaceVariant = Color(0xFFF3E5F5),
        onSurfaceVariant = Color(0xFF7A5A82),
        outline = Color(0xFFD0B8D8),
        outlineVariant = Color(0xFFEDE0F0),
        error = StatusRed,
        onError = PureWhite
    )

    private val LilacPurpleDark = darkColorScheme(
        primary = Color(0xFFCE93D8),
        onPrimary = Color(0xFF4A148C),
        primaryContainer = Color(0xFF8E24AA),
        onPrimaryContainer = Color(0xFFF3E5F5),
        secondary = Color(0xFFBA68C8),
        onSecondary = Color(0xFF4A148C),
        secondaryContainer = Color(0xFF3A1A45),
        onSecondaryContainer = Color(0xFFE1BEE7),
        tertiary = Color(0xFF81C784),
        onTertiary = Color(0xFF1B5E20),
        background = Color(0xFF1A0E20),
        onBackground = Color(0xFFEDE0F0),
        surface = Color(0xFF221430),
        onSurface = Color(0xFFEDE0F0),
        surfaceVariant = Color(0xFF2D1A38),
        onSurfaceVariant = Color(0xFFB898C0),
        outline = Color(0xFF4A2860),
        outlineVariant = Color(0xFF3A1E50),
        error = Color(0xFFFF8A80),
        onError = PureWhite
    )

    /**
     * 根据 themeKey 和暗色模式返回对应的 ColorScheme。
     * material_you 在 Android 12+ 使用系统动态配色。
     */
    @Suppress("NewApi")
    fun get(themeKey: String, isDark: Boolean, context: android.content.Context): ColorScheme {
        return when (themeKey) {
            ThemeKeys.MATERIAL_YOU -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (isDark) dynamicDarkColorScheme(context)
                    else dynamicLightColorScheme(context)
                } else {
                    if (isDark) StrawberryPinkDarkScheme else StrawberryPinkLight
                }
            }
            ThemeKeys.SKY -> if (isDark) SkyBlueDark else SkyBlueLight
            ThemeKeys.MINT -> if (isDark) MintGreenDark else MintGreenLight
            ThemeKeys.LILAC -> if (isDark) LilacPurpleDark else LilacPurpleLight
            else -> if (isDark) StrawberryPinkDarkScheme else StrawberryPinkLight
        }
    }

    /** 根据 themeKey 返回 primary 颜色（供 Widget 远程视图等非 Compose 场景使用） */
    fun primaryColor(themeKey: String): Color = when (themeKey) {
        ThemeKeys.SKY -> Color(0xFF42A5F5)
        ThemeKeys.MINT -> Color(0xFF66BB6A)
        ThemeKeys.LILAC -> Color(0xFFAB47BC)
        else -> StrawberryPink
    }
}
