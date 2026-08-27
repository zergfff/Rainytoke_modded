package com.rainy.token.ui.theme

/**
 * 主题和字体缩放的常量定义。
 * 所有使用主题/字体 key 的文件应从此处 import。
 */
object ThemeKeys {
    const val STRAWBERRY = "strawberry"
    const val SKY = "sky"
    const val MINT = "mint"
    const val LILAC = "lilac"
    const val MATERIAL_YOU = "material_you"

    val all = listOf(STRAWBERRY, SKY, MINT, LILAC, MATERIAL_YOU)
}

object FontScaleKeys {
    const val SMALL = 0.85f
    const val NORMAL = 1.0f
    const val LARGE = 1.15f
    const val EXTRA_LARGE = 1.3f

    val all = listOf(SMALL, NORMAL, LARGE, EXTRA_LARGE)
}
