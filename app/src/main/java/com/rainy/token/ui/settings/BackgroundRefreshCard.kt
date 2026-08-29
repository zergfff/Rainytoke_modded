package com.rainy.token.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.ui.widget.WidgetPeriodicWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台刷新状态卡片。
 *
 * 未加入电池优化白名单时，系统会限制后台任务，WorkManager 的周期性刷新
 * 会被无限推迟——表现为"小组件数据不自动更新，打开 App 才变"。
 * 这在 HyperOS / MIUI 上尤其常见，因此显式提示并给出去设置的入口。
 */
@Composable
fun BackgroundRefreshCard() {
    val context = LocalContext.current
    // 从系统设置返回后需要重新检测，用 produceState 在每次可见时重取
    val exempt by produceState(initialValue = isBatteryExempt(context)) {
        value = isBatteryExempt(context)
    }
    val scope = rememberCoroutineScope()
    var lastRefresh by remember { mutableLongStateOf(0L) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lastRefresh = readLastRefresh(context)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "后台自动刷新",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (exempt) {
                    "已允许后台运行，小组件会按设定间隔自动刷新。"
                } else {
                    "系统正在限制本应用的后台活动，小组件可能不会按时自动刷新，" +
                    "需要打开 App 才更新。建议将本应用加入电池优化白名单。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (exempt) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
            )
            if (!exempt) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { openBatterySettings(context) }) {
                        Text("去设置")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 上次刷新时间：判断后台刷新是否真的在跑的直接依据
            Text(
                text = "上次刷新：" + formatLastRefresh(lastRefresh),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    refreshing = true
                    WidgetPeriodicWorker.requestImmediate(context)
                    // 后台任务有延迟，稍后再读一次时间
                    scope.launch {
                        kotlinx.coroutines.delay(4000)
                        lastRefresh = readLastRefresh(context)
                        refreshing = false
                    }
                },
                enabled = !refreshing
            ) {
                Text(if (refreshing) "刷新中…" else "立即刷新")
            }
        }
    }
}

private suspend fun readLastRefresh(context: Context): Long =
    try { context.appSettingsStore.lastWidgetRefreshAt.first() } catch (_: Exception) { 0L }

private fun formatLastRefresh(epochMillis: Long): String {
    if (epochMillis <= 0L) return "从未"
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
    val ago = System.currentTimeMillis() - epochMillis
    val agoText = when {
        ago < 60_000 -> "刚刚"
        ago < 3_600_000 -> "${ago / 60_000} 分钟前"
        ago < 86_400_000 -> "${ago / 3_600_000} 小时前"
        else -> "${ago / 86_400_000} 天前"
    }
    return "${sdf.format(java.util.Date(epochMillis))}（$agoText）"
}

private fun isBatteryExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

private fun openBatterySettings(context: Context) {
    val pkgUri = Uri.parse("package:${context.packageName}")
    // 标准弹窗部分 ROM（含 HyperOS）不支持，逐级回退
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(pkgUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(pkgUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
