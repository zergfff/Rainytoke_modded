package com.rainy.token.ui.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 接收系统 ACTION_TIME_TICK（每分钟触发）广播，向 OpenCodeGoWidgetProvider
 * 发送 clock-only 更新请求，只更新时钟/日期/星期/天气，不触发余额刷新。
 */
class WidgetTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_TICK) return

        val appContext = context.applicationContext
        val updateIntent = Intent(appContext, OpenCodeGoWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(OpenCodeGoWidgetProvider.EXTRA_CLOCK_ONLY, true)
            // 发给所有 widget 实例
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, OpenCodeGoWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        appContext.sendBroadcast(updateIntent)
    }
}
