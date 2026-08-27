package com.rainy.token

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.ui.RainyTokenNavHost
import com.rainy.token.ui.components.RainyBackground
import com.rainy.token.ui.theme.RainyTokenTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appSettingsStore = applicationContext.appSettingsStore

        setContent {
            val themeKey by appSettingsStore.themeKey.collectAsState(initial = "strawberry")
            val fontScale by appSettingsStore.fontScale.collectAsState(initial = 1.0f)

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
}
