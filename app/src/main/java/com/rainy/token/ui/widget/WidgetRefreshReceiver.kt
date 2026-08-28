package com.rainy.token.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        if (isRefreshing) return
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
                    // API Key 存在加密 SecureStorage 中
                    val qweatherKey = secureStorage.getQWeatherKey().orEmpty()
                    val qweatherHost = store.qweatherHost.first()
                    if (weatherEnabled && qweatherKey.isNotBlank()) {
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

                        val snapshot = weatherRepository.fetchNow(
                            apiKey = qweatherKey,
                            apiHost = qweatherHost,
                            latLon = latLon,
                            context = appContext
                        )
                        if (snapshot != null) {
                            store.setWeatherSnapshot(
                                city = snapshot.city,
                                text = snapshot.text,
                                icon = snapshot.icon,
                                temp = snapshot.tempC
                            )
                        }
                    }
                } catch (_: Exception) {
                    // 静默失败
                }

                if (refreshed) {
                    OpenCodeGoWidgetProvider.notifyDataChanged(appContext)
                }
            } catch (_: Exception) {
                // 静默，Widget 保留旧数据
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
