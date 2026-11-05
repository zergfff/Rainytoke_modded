package com.rainy.token.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rainy.token.data.debug.FileDebugLog
import com.rainy.token.data.repository.WeatherRepository
import com.rainy.token.data.weather.loadQWeatherCredentialWithMigration
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.domain.service.ServiceType
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 接收 Widget 刷新按钮广播，后台静默刷新余额 + 天气。
 * 不依赖 @AndroidEntryPoint，通过 EntryPoints 获取 Hilt 依赖。
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val appContext = context.applicationContext
        FileDebugLog.i(appContext, "REFRESH_START")
        if (isRefreshing) {
            FileDebugLog.w(appContext, "REFRESH_SKIP", "why" to "already-running")
            return
        }
        isRefreshing = true
        val pendingResult = goAsync()
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext, WidgetRefreshEntryPoint::class.java
        )
        val useCase = entryPoint.refreshBalanceUseCase()
        val weatherRepository = entryPoint.weatherRepository()
        val secureStorage = entryPoint.secureStorage()
        OpenCodeGoWidgetProvider.showRefreshing(appContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 刷新余额
                val selectedService = OpenCodeGoWidgetProvider.currentDisplayService(appContext)
                val refreshed = withTimeoutOrNull(25_000L) {
                    val selectedResult = useCase(selectedService)
                    val dsResult = useCase(ServiceType.DEEPSEEK)
                    selectedResult.isSuccess || dsResult.isSuccess
                } == true

                // 天气刷新（与周期 Worker 一致：定位 + 天气）
                try {
                    val store = appContext.appSettingsStore
                    val weatherEnabled = store.weatherEnabled.first()
                    // 凭据存在加密 SecureStorage 中（JWT 私钥或旧版 API Key，旧格式自动迁移）
                    val credential = secureStorage.loadQWeatherCredentialWithMigration(store.qweatherHost.first())
                    if (weatherEnabled && credential.hasAuth) {
                        val loc = weatherRepository.getCoarseLocation(appContext)
                        if (loc != null) {
                            store.setWeatherLocation(loc.latitude.toFloat(), loc.longitude.toFloat())
                        }
                        val cachedLat = store.weatherLatitude.first()
                        val cachedLon = store.weatherLongitude.first()
                        val latLon = if (loc != null) {
                            loc.latitude to loc.longitude
                        } else if (cachedLat != 0f || cachedLon != 0f) {
                            cachedLat.toDouble() to cachedLon.toDouble()
                        } else null

                        when (
                            val outcome = weatherRepository.fetchNow(
                                credential = credential,
                                latLon = latLon,
                                context = appContext
                            )
                        ) {
                            is WeatherRepository.Outcome.Ok -> store.setWeatherSnapshot(
                                city = outcome.snapshot.city,
                                text = outcome.snapshot.text,
                                icon = outcome.snapshot.icon,
                                temp = outcome.snapshot.tempC
                            )
                            is WeatherRepository.Outcome.Fail ->
                                FileDebugLog.w(appContext, "WEATHER_SKIP", "why" to outcome.reason)
                        }
                    }
                } catch (t: Exception) {
                    // 静默失败，但要留痕：天气挂了不影响额度展示
                    FileDebugLog.w(
                        appContext, "WEATHER_FAIL",
                        "err" to (t.message ?: t.javaClass.simpleName)
                    )
                }

                FileDebugLog.i(appContext, "REFRESH_DONE", "ok" to refreshed)
                if (refreshed) {
                    OpenCodeGoWidgetProvider.notifyDataChanged(appContext)
                }
            } catch (t: Exception) {
                // 静默，Widget 保留旧数据；但必须留下痕迹，否则线上无从排查
                FileDebugLog.e(appContext, "REFRESH_FAIL", t)
            } finally {
                isRefreshing = false
                pendingResult.finish()
            }
        }
    }

    companion object {
        @Volatile
        private var isRefreshing = false

        const val ACTION = "com.rainy.token.action.WIDGET_REFRESH"

        fun createIntent(context: Context): Intent =
            Intent(context, WidgetRefreshReceiver::class.java).apply { action = ACTION }
    }
}
