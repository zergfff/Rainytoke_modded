package com.rainy.token

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.ui.widget.WidgetPeriodicWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * RainyToken 入口 Application。
 *
 * @HiltAndroidApp 触发 Hilt 组件树的生成（SingletonComponent 等），
 * 整个 APP 的所有 @Inject 依赖都依赖它。
 *
 * 实现 WorkManagerConfiguration.Provider 以支持 @HiltWorker。
 */
@HiltAndroidApp
class RainyTokenApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() {
            val injected = ::workerFactory.isInitialized
            android.util.Log.d("RainyApp", "workManagerConfiguration: workerFactory注入=$injected")
            return Configuration.Builder()
                .apply { if (injected) setWorkerFactory(workerFactory) }
                .build()
        }

    /**
     * 小组件 provider 组件自愈。
     *
     * MIUI 在「最后一个小组件被移除」后可能把 receiver 组件标记为 disabled，
     * 之后小部件中心里就再也找不到这个 APP。这里每次启动检查一次并重新启用，
     * 保证用户总能重新添加小组件（失败也不影响 APP 启动）。
     */
    private fun ensureWidgetProviderEnabled() {
        try {
            val component = android.content.ComponentName(
                this,
                com.rainy.token.ui.widget.OpenCodeGoWidgetProvider::class.java
            )
            val state = packageManager.getComponentEnabledSetting(component)
            val disabled = state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            android.util.Log.d("RainyApp", "widget provider enabled state=$state disabled=$disabled")
            if (disabled) {
                packageManager.setComponentEnabledSetting(
                    component,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                com.rainy.token.data.debug.FileDebugLog.i(
                    this, "WIDGET_REENABLED",
                    "oldState" to state
                )
            }
        } catch (t: Throwable) {
            android.util.Log.w("RainyApp", "ensureWidgetProviderEnabled 失败", t)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(com.rainy.token.util.LocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // WeatherRepository 需要 application context
        CtxHolder.app = applicationContext

        ensureWidgetProviderEnabled()

        // 调度 WorkManager 周期性刷新。
        // 先用默认间隔同步调度，保证任务一定存在；再异步读取用户设置的
        // 间隔重新调度（DataStore 是异步的，不能阻塞 Application 启动）。
        android.util.Log.d("RainyApp", "onCreate: 调度周期刷新")
        WidgetPeriodicWorker.schedule(applicationContext)
        kotlinx.coroutines.MainScope().launch {
            runCatching {
                val interval = applicationContext.appSettingsStore.widgetRefreshIntervalMin.first()
                android.util.Log.d("RainyApp", "读到用户间隔: ${interval}min")
                WidgetPeriodicWorker.schedule(applicationContext, interval)
            }.onFailure {
                android.util.Log.w("RainyApp", "读取刷新间隔失败，沿用默认", it)
            }
        }
    }

    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }
}

/** 全局应用上下文持有者，供非 Hilt 组件（如 WeatherRepository）访问 Context */
object CtxHolder {
    lateinit var app: Context
        internal set
}
