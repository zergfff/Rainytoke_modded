package com.rainy.token.ui.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rainy.token.data.local.SecureStorage
import com.rainy.token.data.local.appSettingsStore
import com.rainy.token.data.repository.WeatherRepository
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager 周期性刷新 Worker。
 *
 * 最小间隔 15 分钟（WorkManager 强制下限），复用 RefreshBalanceUseCase + WeatherRepository。
 * companion object 提供 schedule / cancel 静态方法。
 */
@HiltWorker
class WidgetPeriodicWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val refreshBalanceUseCase: RefreshBalanceUseCase,
    private val secureStorage: SecureStorage,
    private val weatherRepository: WeatherRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        android.util.Log.d(TAG, "doWork 开始")
        val ctx = applicationContext

        // 刷新余额
        val selectedService = OpenCodeGoWidgetProvider.currentDisplayService(ctx)
        try {
            refreshBalanceUseCase(selectedService)
            refreshBalanceUseCase(ServiceType.DEEPSEEK)
            android.util.Log.d(TAG, "余额刷新成功: $selectedService")
        } catch (e: Exception) {
            // 记录而非静默：后台刷新失效时这是唯一的排查线索
            android.util.Log.w(TAG, "余额刷新失败: ${selectedService}", e)
        }

        // 天气刷新（定位 + 天气，跟随本 Worker 的刷新间隔）
        try {
            val store = ctx.appSettingsStore
            val weatherEnabled = store.weatherEnabled.first()
            // API Key 存在加密 SecureStorage 中，DataStore 里的 qweatherKey 不参与鉴权
            val qweatherKey = secureStorage.getQWeatherKey().orEmpty()
            val qweatherHost = store.qweatherHost.first()
            if (weatherEnabled && qweatherKey.isNotBlank()) {
                // 优先使用系统定位更新经纬度，失败则用上次缓存的坐标
                val loc = weatherRepository.getCoarseLocation(ctx)
                if (loc != null) {
                    store.setWeatherLocation(loc.latitude.toFloat(), loc.longitude.toFloat())
                }
                val cachedLat = store.weatherLatitude.first()
                val cachedLon = store.weatherLongitude.first()
                val latLon = if (loc != null) {
                    loc.latitude to loc.longitude
                } else if (cachedLat != 0f || cachedLon != 0f) {
                    cachedLat.toDouble() to cachedLon.toDouble()
                } else {
                    null
                }

                val snapshot = weatherRepository.fetchNow(
                    apiKey = qweatherKey,
                    apiHost = qweatherHost,
                    latLon = latLon,
                    context = ctx
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

        // 通知 Widget 更新
        OpenCodeGoWidgetProvider.notifyDataChanged(ctx)
        // 记录本次刷新时间，供设置页展示"上次刷新"
        try {
            ctx.appSettingsStore.setLastWidgetRefreshAt(System.currentTimeMillis())
        } catch (_: Exception) { }
        android.util.Log.d(TAG, "doWork 结束，已通知小组件更新")
        return Result.success()
    }

    companion object {
        private const val TAG = "WidgetPeriodicWorker"
        private const val WORK_NAME = "widget_periodic_refresh"
        private const val WORK_NAME_ONCE = "widget_refresh_once"

        /** 调度周期性刷新（≥15min），使用 KEEP 策略避免重复 */
        fun schedule(context: Context, intervalMin: Int = 15) {
            val effectiveInterval = intervalMin.coerceAtLeast(15)
            val wm = WorkManager.getInstance(context)
            // 只用 UPDATE 替换：不要先 cancel 再 enqueue。
            // cancelUniqueWork 是异步的，紧接着 enqueue 会与之竞争，
            // 导致新任务刚入队就被取消（表现为 JobScheduler 里没有任何 job）。
            // 不加网络约束：JobScheduler 的 CONNECTIVITY 要求网络带
            // TRUSTED+VALIDATED 能力，移动数据/VPN 环境下常判定不满足，
            // 任务会无限期挂起（Ready=false, earliest=none）而永不执行。
            // 网络异常由 doWork() 内的 try/catch 兜底。
            val request = PeriodicWorkRequestBuilder<WidgetPeriodicWorker>(
                effectiveInterval.toLong(), TimeUnit.MINUTES
            ).build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** 取消周期性刷新 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * 立即执行一次刷新（天气开关打开、定位授权后等场景）。
         * 使用唯一一次性任务，避免重复排队。
         */
        fun requestImmediate(context: Context) {
            // 同样不带网络约束，理由见 schedule()
            val request = OneTimeWorkRequestBuilder<WidgetPeriodicWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONCE,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
