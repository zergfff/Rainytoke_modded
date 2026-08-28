package com.rainy.token

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.ui.RainyTokenNavHost
import com.rainy.token.ui.components.RainyBackground
import com.rainy.token.ui.theme.RainyTokenTheme
import com.rainy.token.ui.widget.WidgetPeriodicWorker
import dagger.hilt.android.AndroidEntryPoint

/**
 * APP 入口。
 *
 * 布局策略：
 *  - 外层只套 RainyTokenTheme（统一品牌色 + 字体）
 *  - 再套 RainyBackground（跟随主题渐变背景，全局共享）
 *  - NavHost 在背景之上，每个页面自己用 Scaffold 处理 TopAppBar 和 padding
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.rainy.token.util.LocaleManager.wrapContext(newBase))
    }

    /**
     * 定位权限请求（ACCESS_COARSE_LOCATION）。
     * 仅用于和风天气定位，用户关闭天气功能时不会请求。
     */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 授权后立即触发一次刷新，尽快拿到天气
            WidgetPeriodicWorker.requestImmediate(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appSettingsStore = applicationContext.appSettingsStore

        setContent {
            val themeKey by appSettingsStore.themeKey.collectAsState(initial = "strawberry")
            val fontScale by appSettingsStore.appFontScale.collectAsState(initial = 1.0f)
            val weatherEnabled by appSettingsStore.weatherEnabled.collectAsState(initial = false)

            // 开启天气功能且未授权定位时，请求粗略定位权限
            LaunchedEffect(weatherEnabled) {
                if (weatherEnabled && !hasLocationPermission()) {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }

            RainyTokenTheme(
                themeKey = themeKey,
                fontScale = fontScale
            ) {
                RainyBackground {
                    RainyTokenNavHost()
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
