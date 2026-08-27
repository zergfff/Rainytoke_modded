package com.rainy.token.ui.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Widget 三区点击跳转 Intent 工具。
 *
 * HyperOS 3 系统应用包名：
 *  - 时钟：com.android.deskclock
 *  - 日历：com.android.calendar
 *  - 天气：com.miui.weather2
 *
 * 当目标 app 不存在时，兜底为 createChooser。
 */
object WidgetIntentHelper {

    /** 打开时钟应用 */
    fun clockIntent(context: Context): Intent {
        val pkg = "com.android.deskclock"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) return intent
        // Fallback: ACTION_VIEW with clock URI
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.deskclock")
        }.let { Intent.createChooser(it, "Open Clock") }
    }

    /** 打开日历应用 */
    fun calendarIntent(context: Context): Intent {
        val pkg = "com.android.calendar"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) return intent
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.android.calendar")
        }.let { Intent.createChooser(it, "Open Calendar") }
    }

    /** 打开天气应用 */
    fun weatherIntent(context: Context): Intent {
        val pkg = "com.miui.weather2"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) return intent
        // Fallback: search weather in app store or web
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://search?q=weather")
        }.let { Intent.createChooser(it, "Open Weather") }
    }
}
