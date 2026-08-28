package com.rainy.token.ui.widget

/**
 * 小组件元素的自定义样式。
 *
 * @param sizeSp 字号（sp）；null 表示跟随"小组件字体大小"的整体缩放
 * @param colorArgb 颜色（ARGB int）；null 表示使用主题默认色
 * @param textStyle 字体样式 key：normal / bold / italic
 *
 * 关于字族：Android 的 RemoteViews **没有任何公开 API 可以设置 Typeface**
 * （只有 setTextViewTextSize / setTextColor）。系统内置字族（sans/serif/
 * monospace）也无法通过 RemoteViews 指定——TypefaceSpan Parcelable 跨进程
 * 传递到 launcher 后会失效。因此这里提供的是粗体/斜体（StyleSpan），
 * 它是可靠的 Parcelable，能在小组件上正常生效。
 */
data class WidgetElementStyle(
    val sizeSp: Float? = null,
    val colorArgb: Int? = null,
    val textStyle: String? = null
) {
    /** 未自定义时回落到元素的默认值（时间默认粗体） */
    fun styleOrDefault(element: WidgetElement): String =
        textStyle ?: element.defaultStyle
}

/**
 * 小组件可自定义样式的元素。
 *
 * key 同时作为 DataStore 的存储后缀，改动 key 会让已保存的用户配置失效，
 * 因此不要随意重命名。
 */
enum class WidgetElement(
    val key: String,
    /** 基准字号（sp）。实际显示 = 基准 × 设置里的"小组件字体大小"倍率 */
    val defaultSizeSp: Float,
    /** 默认字体样式。时间为粗体，与上一版外观保持一致 */
    val defaultStyle: String = WidgetStyleDefaults.STYLE_NORMAL
) {
    /** 时钟 HH:mm */
    TIME("time", 42f, WidgetStyleDefaults.STYLE_BOLD),

    /** 星期，如 周五 */
    WEEKDAY("weekday", 15f),

    /** 日期，如 08/28 */
    DATE("date", 15f),

    /** 天气温度文字 */
    WEATHER("weather", 25f),

    /** 服务标题，如 "OpenCode Go 配额" */
    TITLE("title", 14f),

    /** 三行左侧标签：5h / 本周 / 本月 */
    ROW_LABEL("row_label", 14f),

    /** 三行百分比数字 */
    PERCENT("percent", 15f),

    /** 三行右侧剩余时间 */
    RESET("reset", 13f)
}

/** 背景可单独设置颜色与透明度 */
object WidgetStyleDefaults {
    const val STYLE_NORMAL = "normal"
    const val STYLE_BOLD = "bold"
    const val STYLE_ITALIC = "italic"

    val STYLE_OPTIONS = listOf(STYLE_NORMAL, STYLE_BOLD, STYLE_ITALIC)

    /** 背景默认透明度（0~255） */
    const val BG_ALPHA_DEFAULT = 255

    /** 把样式 key 转成 android.graphics.Typeface 的 style 常量 */
    fun textStyleInt(key: String): Int = when (key) {
        STYLE_BOLD -> android.graphics.Typeface.BOLD
        STYLE_ITALIC -> android.graphics.Typeface.ITALIC
        else -> android.graphics.Typeface.NORMAL
    }

    /**
     * 给文本套上样式（粗体/斜体）。
     *
     * RemoteViews 没有 setTypeface，唯一可靠途径是 SpannableString + StyleSpan
     * 通过 setTextViewText(CharSequence) 传递。StyleSpan 是 Parcelable，
     * 跨进程送到 launcher 后仍然有效；TypefaceSpan 则不行。
     */
    fun styledText(text: CharSequence, styleKey: String): CharSequence {
        val styleInt = textStyleInt(styleKey)
        if (styleInt == android.graphics.Typeface.NORMAL || text.isEmpty()) return text
        return android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.StyleSpan(styleInt),
                0,
                text.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
