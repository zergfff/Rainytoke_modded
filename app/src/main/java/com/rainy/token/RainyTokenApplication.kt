package com.rainy.token

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.rainy.token.ui.widget.WidgetPeriodicWorker
import dagger.hilt.android.HiltAndroidApp
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
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(com.rainy.token.util.LocaleManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // WeatherRepository 需要 application context
        CtxHolder.app = applicationContext

        // 调度 WorkManager 周期性刷新
        WidgetPeriodicWorker.schedule(applicationContext)
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
