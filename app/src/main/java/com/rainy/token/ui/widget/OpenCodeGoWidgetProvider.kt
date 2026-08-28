package com.rainy.token.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.RemoteViews
import com.rainy.token.MainActivity
import com.rainy.token.R
import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.cache.balanceCacheDataStore
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.ui.components.normalizeWindowLabel
import com.rainy.token.ui.theme.ThemePresets
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.data.repository.WeatherRepository
import com.rainy.token.util.LocaleManager
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * OpenCode Go 桌面小组件（MIUI Widget）。
 *
 * 改版功能：
 * - 多服务选择（从 AppSettingsStore 读取）
 * - 顶部时钟/日期/星期/天气行
 * - 三区点击跳转（WidgetIntentHelper）
 * - 主题强调色
 * - 可调刷新冷却
 * - clock-only 更新路径（EXTRA_CLOCK_ONLY，只更新时钟不刷新余额）
 */
class OpenCodeGoWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SWITCH_SERVICE -> {
                switchDisplayService(context)
                notifyDataChanged(context)
            }
            EXTRA_CLOCK_ONLY -> {
                // Clock-only 更新：只更新时钟/日期/天气，不触发余额刷新
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val component = ComponentName(context, OpenCodeGoWidgetProvider::class.java)
                val ids = appWidgetManager.getAppWidgetIds(component)
                if (ids.isNotEmpty()) {
                    onUpdate(context, appWidgetManager, ids, clockOnly = true)
                }
            }
            "miui.appwidget.action.APPWIDGET_UPDATE" -> {
                val appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                if (appWidgetIds != null) {
                    onUpdate(context, AppWidgetManager.getInstance(context), appWidgetIds)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        onUpdate(context, appWidgetManager, appWidgetIds, clockOnly = false)
    }

    private fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        clockOnly: Boolean = false
    ) {
        var selectedHasCachedData = false
        val localized = LocaleManager.wrapContext(context)

        // 读取主题色
        val themeKey = runBlocking {
            try { context.applicationContext.appSettingsStore.themeKey.first() } catch (_: Exception) { "strawberry" }
        }
        val accentColor = ThemePresets.primaryColor(themeKey).toArgb()

        // 读取字体缩放（设置页 fontScale：0.85 / 1.0 / 1.15 / 1.3）
        val fontScale = runBlocking {
            try { context.applicationContext.appSettingsStore.widgetFontScale.first() } catch (_: Exception) { 1.0f }
        }.coerceIn(0.5f, 2.5f)

        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_opencode_go)

            // ─── 时钟/日期/星期/天气行 ───
            updateClockRow(views, localized)

            // 应用字体缩放（跟随设置页 fontScale）
            applyFontScale(views, localized, fontScale)

            // 时钟行点击 → 打开时钟
            val clockPi = PendingIntent.getActivity(
                context, 10, WidgetIntentHelper.clockIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 时钟行点击 → 打开时钟
            views.setOnClickPendingIntent(R.id.widget_time, clockPi)

            // 星期、日期 → 打开日历
            val calendarPi = PendingIntent.getActivity(
                context, 12, WidgetIntentHelper.calendarIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_weekday, calendarPi)
            views.setOnClickPendingIntent(R.id.widget_date, calendarPi)

            // 天气行点击 → 打开天气
            views.setOnClickPendingIntent(R.id.widget_weather, PendingIntent.getActivity(
                context, 11, WidgetIntentHelper.weatherIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

            // 刷新入口：刷新按钮已从布局移除，改为点击 logo 触发刷新
            views.setOnClickPendingIntent(R.id.widget_logo, PendingIntent.getBroadcast(
                context, 1, WidgetRefreshReceiver.createIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

            // 整卡点击 → 切换服务（选中 >1 服务时）
            val switchPi = PendingIntent.getBroadcast(
                context, 2, Intent(context, OpenCodeGoWidgetProvider::class.java).apply {
                    action = ACTION_SWITCH_SERVICE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_content, switchPi)

            // 读缓存并填充数据
            runBlocking {
                try {
                    val dataStore = context.applicationContext.balanceCacheDataStore
                    val cache = BalanceCache(dataStore)
                    val selectedService = currentDisplayService(context)
                    views.setTextViewText(
                        R.id.widget_service_title,
                        localized.getString(R.string.widget_service_quota, selectedService.displayName)
                    )
                    views.setImageViewResource(R.id.widget_logo, widgetLogo(selectedService))
                    // 恢复 logo 不透明度：showRefreshing() 会把它调暗，
                    // RemoteViews 的 setInt 不会随 setImageViewResource 自动重置。
                    views.setInt(R.id.widget_logo, "setImageAlpha", 255)
                    if (selectedService == ServiceType.OLLAMA || selectedService == ServiceType.COMMANDCODE_GO) {
                        views.setViewLayoutWidth(R.id.widget_logo, 14f, TypedValue.COMPLEX_UNIT_DIP)
                        views.setViewLayoutHeight(R.id.widget_logo, 14f, TypedValue.COMPLEX_UNIT_DIP)
                    } else {
                        views.setViewLayoutWidth(R.id.widget_logo, 22f, TypedValue.COMPLEX_UNIT_DIP)
                        views.setViewLayoutHeight(R.id.widget_logo, 12f, TypedValue.COMPLEX_UNIT_DIP)
                    }
                    val cached = cache.get(selectedService)
                    if (cached != null) {
                        selectedHasCachedData = true
                        populateServiceRows(views, localized, selectedService, cached.balance)
                    } else {
                        setEmptyState(views, localized, selectedService)
                        views.setTextViewText(
                            R.id.widget_service_title,
                            localized.getString(R.string.widget_service_quota, selectedService.displayName)
                        )
                    }

                } catch (_: Exception) {
                    setEmptyState(views, localized, currentDisplayService(context))
                }
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        // 自动刷新（非 clock-only 模式）
        if (!clockOnly && (!selectedHasCachedData || shouldAutoRefresh(context))) {
            markAutoRefreshTime(context)
            context.sendBroadcast(WidgetRefreshReceiver.createIntent(context))
        }
    }

    /** 更新时钟行：时间、星期、日期、天气 */
    private fun updateClockRow(views: RemoteViews, context: Context) {
        // 显式使用系统默认时区，保证与手机当前时间/时区一致
        val tz = java.util.TimeZone.getDefault()
        val cal = Calendar.getInstance(tz)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = tz }
        val dateFmt = SimpleDateFormat("MM/dd", Locale.getDefault()).apply { timeZone = tz }
        val weekdayFmt = SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = tz }

        views.setTextViewText(R.id.widget_time, timeFmt.format(cal.time))
        views.setTextViewText(R.id.widget_date, dateFmt.format(cal.time))

        // 星期（跟随系统语言，中文输出"周三"）
        views.setTextViewText(R.id.widget_weekday, weekdayFmt.format(cal.time))

        // 天气（只显示图标 + 温度，不显示城市/定位名；整组右对齐）
        runBlocking {
            try {
                val store = context.applicationContext.appSettingsStore
                val text = store.weatherText.first()
                val icon = store.weatherIcon.first()
                val temp = store.weatherTemp.first()

                val tempLabel = if (temp.isNotBlank()) "${temp.trim()}°" else ""

                if (tempLabel.isNotEmpty()) {
                    // 有缓存天气数据 → 显示真实图标
                    val iconRes = if (WeatherRepository.hasIcon(icon)) {
                        context.resources.getIdentifier(
                            WeatherRepository.iconDrawableName(icon),
                            "drawable", context.packageName
                        ).takeIf { it != 0 }
                    } else null

                    if (iconRes != null) {
                        views.setImageViewResource(R.id.widget_weather_icon, iconRes)
                    } else {
                        views.setViewVisibility(R.id.widget_weather_icon, android.view.View.GONE)
                    }

                    // 温度始终显示；没图标时只显示温度
                    views.setTextViewText(R.id.widget_weather, tempLabel)
                    views.setViewVisibility(R.id.widget_weather_group, android.view.View.VISIBLE)
                } else {
                    // 无天气数据：整组隐藏，不占位
                    views.setViewVisibility(R.id.widget_weather_group, android.view.View.GONE)
                }
            } catch (_: Exception) {
                views.setViewVisibility(R.id.widget_weather_group, android.view.View.GONE)
            }
        }
    }

    /**
 * 按 fontScale 缩放小组件内所有文字。
 * 左右两侧文字用 wrap_content，进度条 weight=1 自动吃掉剩余空间，
 * 因此字体变大 → 两侧变宽 → 进度条按比例自动缩短。
 */
private fun applyFontScale(views: RemoteViews, context: Context, scale: Float) {
    // (viewId, XML 里的基准 sp)
    val targets = listOf(
        // 时间 42sp，星期/日期/天气 21sp
        R.id.widget_time to 42f,
        R.id.widget_weekday to 21f,
        R.id.widget_date to 21f,
        R.id.widget_weather to 21f,
        R.id.widget_service_title to 14f,
        R.id.widget_ds_label to 14f,
        R.id.widget_ds_amount to 15f,
        R.id.row1_label to 14f,
        R.id.row1_pct to 15f,
        R.id.row1_reset to 13f,
        R.id.row2_label to 14f,
        R.id.row2_pct to 15f,
        R.id.row2_reset to 13f,
        R.id.row3_label to 14f,
        R.id.row3_pct to 15f,
        R.id.row3_reset to 13f
    )
    for ((id, baseSp) in targets) {
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, baseSp * scale)
    }

    // 统一三行左侧列宽，保证三个进度条左端纵向对齐
    //（label/pct 若用 wrap_content，"5h" 比"本周/本月"窄，进度条左端会错开）
    //
    // 宽度必须按"最长可能内容 × 当前字号"来算，不能写死 dp：
    // 重置时间最长形如 "19d19h"（6 字符），百分比最长 "100%"（4 字符，加粗更宽）。
    // 之前 pct=38dp / reset=48dp 是固定值，字号一大或内容一长就装不下，
    // TextView 换行把进度条挤到下一行。
    // 这里按 字符数 × 字号 × 字符宽度系数 估算，留 25% 余量：
    //   最宽内容固定为 "100%"(4 字符) 和 "XXdXXh"(6 字符)，不会有更长的。
    // 旧的写死值（label 64dp / pct 38dp / reset 48dp）两头不讨好：
    // label 过宽挤占进度条，pct/reset 又窄到装不下导致换行。
    // label 实际内容是 "5h" / "本周" / "本月"。中文字形加字距比理论
    // 字宽（1.0em）更宽，按 2 字算（35dp）会触发 ellipsize 变成"本..."。
    // 这里按 3 个中文字的容量给，留足余量。
    val labelW = (3f * 14f * 1.00f * 1.15f) * scale
    val pctW = (4f * 15f * 0.66f * 1.25f) * scale      // "100%" @15sp bold
    val resetW = (6f * 13f * 0.58f * 1.25f) * scale    // "XXdXXh" @13sp

    for (id in listOf(R.id.row1_label, R.id.row2_label, R.id.row3_label)) {
        views.setViewLayoutWidth(id, labelW, TypedValue.COMPLEX_UNIT_DIP)
    }
    for (id in listOf(R.id.row1_pct, R.id.row2_pct, R.id.row3_pct)) {
        views.setViewLayoutWidth(id, pctW, TypedValue.COMPLEX_UNIT_DIP)
    }
    for (id in listOf(R.id.row1_reset, R.id.row2_reset, R.id.row3_reset)) {
        views.setViewLayoutWidth(id, resetW, TypedValue.COMPLEX_UNIT_DIP)
    }
}

private fun populateServiceRows(
        views: RemoteViews,
        context: Context,
        service: ServiceType,
        balance: com.rainy.token.domain.model.ServiceBalance
    ) {
        // 默认：显示三个进度条行，隐藏 DeepSeek 行
        for (rowId in listOf(R.id.row1_row, R.id.row2_row, R.id.row3_row)) {
            views.setViewVisibility(rowId, android.view.View.VISIBLE)
        }
        views.setViewVisibility(R.id.widget_ds_row, android.view.View.GONE)
        val extras = balance.extras

        // 标题无条件设置：showRefreshing() 会把标题临时改成"刷新中…"，
        // 若只在个别分支里重设，其他服务刷新结束后标题就永久残留"刷新中…"。
        val titlePlan = extras["plan"].orEmpty()
        views.setTextViewText(
            R.id.widget_service_title,
            if (titlePlan.isNotEmpty()) {
                context.getString(R.string.widget_quota_with_plan, service.displayName, titlePlan)
            } else {
                context.getString(R.string.widget_service_quota, service.displayName)
            }
        )

        when (service) {
            ServiceType.OPENCODE_GO -> {
                setRowLabel(views, context.getString(R.string.window_5h_short), context.getString(R.string.window_weekly), context.getString(R.string.window_monthly))
                populateRow(views, R.id.row1_pct, R.id.row1_bar, R.id.row1_reset,
                    pct = extras["rolling.pct"]?.toFloatOrNull()?.roundToInt(),
                    resetSec = extras["rolling.resetInSec"]?.toLongOrNull())
                populateRow(views, R.id.row2_pct, R.id.row2_bar, R.id.row2_reset,
                    pct = extras["weekly.pct"]?.toFloatOrNull()?.roundToInt(),
                    resetSec = extras["weekly.resetInSec"]?.toLongOrNull())
                populateRow(views, R.id.row3_pct, R.id.row3_bar, R.id.row3_reset,
                    pct = extras["monthly.pct"]?.toFloatOrNull()?.roundToInt(),
                    resetSec = extras["monthly.resetInSec"]?.toLongOrNull())
            }
            ServiceType.COMMANDCODE_GO -> {
                fun calcPct(used: Double?, cap: Double?): Int? {
                    if (used == null || cap == null || cap <= 0) return null
                    return ((used / cap) * 100).toInt().coerceIn(0, 100)
                }
                setRowLabel(views, context.getString(R.string.window_5h_short), context.getString(R.string.window_weekly), context.getString(R.string.window_monthly))
                populateRow(views, R.id.row1_pct, R.id.row1_bar, R.id.row1_reset,
                    pct = calcPct(extras["fiveHour.used"]?.toDoubleOrNull(), extras["fiveHour.cap"]?.toDoubleOrNull()),
                    resetSec = extras["fiveHour.resetInSec"]?.toLongOrNull())
                populateRow(views, R.id.row2_pct, R.id.row2_bar, R.id.row2_reset,
                    pct = calcPct(extras["weekly.used"]?.toDoubleOrNull(), extras["weekly.cap"]?.toDoubleOrNull()),
                    resetSec = extras["weekly.resetInSec"]?.toLongOrNull())
                populateRow(views, R.id.row3_pct, R.id.row3_bar, R.id.row3_reset,
                    pct = calcPct(balance.monthlySpent, balance.totalQuota),
                    resetSec = extras["monthly.resetInSec"]?.toLongOrNull())
            }
            ServiceType.CODEX -> {
                val windowCount = extras.keys
                    .mapNotNull { key -> key.removePrefix("window_").substringBefore('.').toIntOrNull() }
                    .distinct().maxOrNull()?.plus(1) ?: 0
                val weeklyLabel = context.getString(R.string.window_every_week)
                val monthlyLabel = context.getString(R.string.window_every_month)
                val usageLabel = context.getString(R.string.window_usage)
                val normal = mutableListOf<Triple<String, Int?, Long?>>()
                for (index in 0 until windowCount) {
                    if (extras["window_$index.group"] == "SPARK") continue
                    val rawLabel = extras["window_$index.label"] ?: "usage"
                    val label = normalizeWindowLabel(
                        rawLabel,
                        weeklyLabel = weeklyLabel,
                        monthlyLabel = monthlyLabel,
                        usageLabel = usageLabel
                    )
                    val remaining = extras["window_$index.remainingPct"]?.toIntOrNull()
                    val resetAt = extras["window_$index.resetAt"]?.toLongOrNull()?.takeIf { it > 0 }
                    normal.add(Triple(label, remaining?.let { (100 - it).coerceIn(0, 100) }, resetAt?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 }))
                }
                val has5h = normal.any { it.first.contains("5") }
                val row1Idx = if (has5h) (normal.indexOfFirst { it.first.contains("5") }.takeIf { it >= 0 } ?: 0) else -1
                val row1 = if (row1Idx >= 0) normal[row1Idx] else Triple("5h", null, null)
                val otherWindows = normal.filterIndexed { idx, _ -> idx != row1Idx }
                val row2 = otherWindows.getOrNull(0) ?: Triple(weeklyLabel, null, null)
                val row3 = otherWindows.getOrNull(1) ?: Triple(monthlyLabel, null, null)
                setRowLabel(views, row1.first, row2.first, row3.first)
                listOf(
                    Triple(R.id.row1_pct, R.id.row1_bar, R.id.row1_reset),
                    Triple(R.id.row2_pct, R.id.row2_bar, R.id.row2_reset),
                    Triple(R.id.row3_pct, R.id.row3_bar, R.id.row3_reset)
                ).forEachIndexed { index, ids ->
                    val window = when (index) { 0 -> row1; 1 -> row2; else -> row3 }
                    populateRow(views, ids.first, ids.second, ids.third, pct = window.second, resetSec = window.third)
                }
            }
            ServiceType.DEEPSEEK -> {
                // DeepSeek 独立页面：无进度条，仅一行余额文字
                // 隐藏三个进度条行
                for (id in listOf(R.id.row1_label, R.id.row1_pct, R.id.row1_reset,
                                  R.id.row2_label, R.id.row2_pct, R.id.row2_reset,
                                  R.id.row3_label, R.id.row3_pct, R.id.row3_reset)) {
                    views.setTextViewText(id, "")
                }
                for (barId in listOf(R.id.row1_bar, R.id.row2_bar, R.id.row3_bar)) {
                    views.setViewVisibility(barId, android.view.View.GONE)
                }
                for (rowId in listOf(R.id.row1_row, R.id.row2_row, R.id.row3_row)) {
                    views.setViewVisibility(rowId, android.view.View.GONE)
                }
                // 显示 DeepSeek 专属行（单行余额）
                views.setViewVisibility(R.id.widget_ds_row, android.view.View.VISIBLE)
                val amount = balance.amount
                val dsText = if (amount > 0) balance.unit + String.format("%.2f", amount) else "—"
                views.setTextViewText(R.id.widget_ds_amount, dsText)
                views.setTextViewText(R.id.widget_ds_label, context.getString(R.string.widget_deepseek_balance))
            }
            ServiceType.OLLAMA -> {
                setRowLabel(views, context.getString(R.string.window_5h_short), context.getString(R.string.window_every_week), "")
                populateRow(views, R.id.row1_pct, R.id.row1_bar, R.id.row1_reset,
                    pct = extras["session.pct"]?.toFloatOrNull()?.toInt(),
                    resetSec = extras["session.resetAt"]?.toLongOrNull()?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 })
                populateRow(views, R.id.row2_pct, R.id.row2_bar, R.id.row2_reset,
                    pct = extras["weekly.pct"]?.toFloatOrNull()?.toInt(),
                    resetSec = extras["weekly.resetAt"]?.toLongOrNull()?.let { (it - System.currentTimeMillis()) / 1000 }?.takeIf { it > 0 })
                views.setTextViewText(R.id.row3_label, "")
                views.setTextViewText(R.id.row3_pct, "")
                views.setProgressBar(R.id.row3_bar, 100, 0, false)
                views.setTextViewText(R.id.row3_reset, "")
                val plan = extras["plan"] ?: ""
                val titleText = if (plan.isNotEmpty()) {
                    context.getString(R.string.widget_quota_with_plan, service.displayName, plan)
                } else {
                    context.getString(R.string.widget_service_quota, service.displayName)
                }
                views.setTextViewText(R.id.widget_service_title, titleText)
            }
        }
    }

    private fun setRowLabel(views: RemoteViews, first: String, second: String, third: String) {
        views.setTextViewText(R.id.row1_label, first)
        views.setTextViewText(R.id.row2_label, second)
        views.setTextViewText(R.id.row3_label, third)
    }

    private fun populateRow(
        views: RemoteViews,
        pctViewId: Int,
        barViewId: Int,
        resetViewId: Int,
        pct: Int?,
        resetSec: Long?
    ) {
        if (pct != null) {
            views.setTextViewText(pctViewId, "${pct}%")
            views.setProgressBar(barViewId, 100, pct.coerceIn(0, 100), false)
            setProgressColor(views, barViewId, pct)
        } else {
            views.setTextViewText(pctViewId, "—")
            views.setProgressBar(barViewId, 100, 0, false)
        }
        if (resetSec != null && resetSec > 0) {
            views.setTextViewText(resetViewId, formatReset(resetSec))
        } else {
            views.setTextViewText(resetViewId, "")
        }
    }

    private fun setProgressColor(views: RemoteViews, barViewId: Int, pct: Int) {
        val color = when {
            pct >= 80 -> 0xFFE91E63   // 紧张：红粉
            pct >= 50 -> 0xFFFFA726   // 过半：橙
            else -> 0xFF4CAF50        // < 50%：绿
        }
        views.setColorStateList(barViewId, "setProgressTintList", ColorStateList.valueOf(color.toInt()))
    }

    private fun setEmptyState(views: RemoteViews, context: Context, service: ServiceType = ServiceType.OPENCODE_GO) {
        // 空状态同样要恢复服务标题：否则 showRefreshing() 留下的"刷新中…"
        // 在没有任何缓存数据的服务上会一直显示。
        views.setTextViewText(
            R.id.widget_service_title,
            context.getString(R.string.widget_service_quota, service.displayName)
        )
        // 空状态也设置行标签（跟随 app 语言，中文显示 本周/本月）
        setRowLabel(
            views,
            context.getString(R.string.window_5h_short),
            context.getString(R.string.window_weekly),
            context.getString(R.string.window_monthly)
        )
        for (pctId in listOf(R.id.row1_pct, R.id.row2_pct, R.id.row3_pct)) {
            views.setTextViewText(pctId, "—")
        }
        for (barId in listOf(R.id.row1_bar, R.id.row2_bar, R.id.row3_bar)) {
            views.setProgressBar(barId, 100, 0, false)
        }
        for (resetId in listOf(R.id.row1_reset, R.id.row2_reset, R.id.row3_reset)) {
            views.setTextViewText(resetId, "")
        }
        views.setTextViewText(R.id.widget_ds_amount, "—")

        // 页面布局完全由当前服务决定（不因缓存缺失而回退）
        if (service == ServiceType.DEEPSEEK) {
            // DeepSeek 页：隐藏三个进度条行，只显示余额行
            for (rowId in listOf(R.id.row1_row, R.id.row2_row, R.id.row3_row)) {
                views.setViewVisibility(rowId, android.view.View.GONE)
            }
            views.setViewVisibility(R.id.widget_ds_row, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_ds_row, android.view.View.GONE)
            for (rowId in listOf(R.id.row1_row, R.id.row2_row, R.id.row3_row)) {
                views.setViewVisibility(rowId, android.view.View.VISIBLE)
            }
        }
    }

    companion object {
        const val EXTRA_CLOCK_ONLY = "com.rainy.token.action.CLOCK_ONLY_UPDATE"

        /** 两次自动刷新的最小间隔（默认 5 分钟），防止频繁网络请求 */
        private const val AUTO_REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
        private const val PREFS_NAME = "widget_auto_refresh"
        private const val KEY_LAST_AUTO_REFRESH = "last_auto_refresh"
        private const val KEY_DISPLAY_SERVICE = "display_service"
        private const val ACTION_SWITCH_SERVICE = "com.rainy.token.action.WIDGET_SWITCH_SERVICE"
        private val DISPLAY_SERVICES = listOf(ServiceType.OPENCODE_GO, ServiceType.COMMANDCODE_GO, ServiceType.CODEX, ServiceType.OLLAMA, ServiceType.DEEPSEEK)

        private fun autoRefreshPrefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun shouldAutoRefresh(context: Context): Boolean {
            val lastRefresh = autoRefreshPrefs(context).getLong(KEY_LAST_AUTO_REFRESH, 0L)
            return System.currentTimeMillis() - lastRefresh > AUTO_REFRESH_COOLDOWN_MS
        }

        private fun markAutoRefreshTime(context: Context) {
            autoRefreshPrefs(context).edit()
                .putLong(KEY_LAST_AUTO_REFRESH, System.currentTimeMillis())
                .apply()
        }

        /**
         * 小组件当前要显示的服务。
         *
         * 以设置里勾选的服务集合（DataStore widgetSelectedServices）为准：
         * 只在该集合内轮转，取消勾选的服务不会再出现在小组件里。
         * 集合为空时回退到 OpenCode Go，避免出现无服务可显示的空白。
         */
        fun currentDisplayService(context: Context): ServiceType {
            val selected = runBlocking {
                try {
                    context.applicationContext.appSettingsStore.widgetSelectedServices.first()
                } catch (_: Exception) {
                    null
                }
            }.orEmpty()

            val ordered = DISPLAY_SERVICES.filter { it.storageKey in selected }
            val candidates = ordered.ifEmpty { listOf(ServiceType.OPENCODE_GO) }

            val key = autoRefreshPrefs(context).getString(KEY_DISPLAY_SERVICE, null)
            // 当前项若已被取消勾选，则切到集合中的第一个
            return candidates.firstOrNull { it.storageKey == key }
                ?: candidates.first()
        }

        private fun switchDisplayService(context: Context) {
            val selected = runBlocking {
                try {
                    context.applicationContext.appSettingsStore.widgetSelectedServices.first()
                } catch (_: Exception) {
                    null
                }
            }.orEmpty()

            val ordered = DISPLAY_SERVICES.filter { it.storageKey in selected }
            val candidates = ordered.ifEmpty { listOf(ServiceType.OPENCODE_GO) }

            val current = currentDisplayService(context)
            val idx = candidates.indexOf(current).coerceAtLeast(0)
            val next = candidates[(idx + 1) % candidates.size]
            autoRefreshPrefs(context).edit().putString(KEY_DISPLAY_SERVICE, next.storageKey).apply()
        }

        private fun shortName(service: ServiceType): String = when (service) {
            ServiceType.OPENCODE_GO -> "OCGO"
            ServiceType.COMMANDCODE_GO -> "CCGO"
            ServiceType.CODEX -> "Codex"
            ServiceType.DEEPSEEK -> "DS"
            ServiceType.OLLAMA -> "Ollama"
        }

        private fun widgetLogo(service: ServiceType): Int = when (service) {
            ServiceType.OPENCODE_GO -> R.drawable.ic_opencode_go_logo_widget
            ServiceType.COMMANDCODE_GO -> R.drawable.ic_commandcode_logo_widget
            ServiceType.CODEX -> R.drawable.ic_codex_logo_widget
            ServiceType.DEEPSEEK -> R.drawable.ic_deepseek_logo
            ServiceType.OLLAMA -> R.drawable.ic_ollama_logo_widget
        }

        fun notifyDataChanged(context: Context) {
            markAutoRefreshTime(context)
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, OpenCodeGoWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val intent = Intent(context, OpenCodeGoWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        /**
         * 刷新中的视觉反馈。
         *
         * 不再改写服务标题：标题是服务的唯一标识（如 "OpenCode Go 配额"），
         * 而 partiallyUpdateAppWidget 只提交增量，刷新结束若走空状态分支
         * （无缓存/未配置）就不会重设标题，导致"刷新中…"永久残留。
         *
         * 改为让 logo 半透明，刷新结束后 updateAppWidget 会自然恢复，
         * 不会留下任何需要回滚的状态。
         *
         * 注意：这里只改透明度，绝不 setImageViewResource——
         * partiallyUpdateAppWidget 是增量提交，一旦写死某个 drawable，
         * 所有服务（CommandCode/Codex/DeepSeek…）的 logo 都会被顶成那一个。
         */
        fun showRefreshing(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, OpenCodeGoWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(component)
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_opencode_go)
                views.setInt(R.id.widget_logo, "setImageAlpha", 100)
                appWidgetManager.partiallyUpdateAppWidget(id, views)
            }
        }

        private fun formatReset(sec: Long): String {
            if (sec <= 0) return ""
            val days = sec / 86400
            val hours = (sec % 86400) / 3600
            val minutes = (sec % 3600) / 60
            return when {
                days > 0 -> "${days}d${if (hours > 0) "${hours}h" else ""}"
                hours > 0 -> "${hours}h${if (minutes > 0) "${minutes}m" else ""}"
                minutes > 0 -> "${minutes}m"
                else -> "<1m"
            }
        }
    }
}
