package com.rainy.token.ui.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
    private val weatherRepository: WeatherRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // 刷新余额
        val selectedService = OpenCodeGoWidgetProvider.currentDisplayService(ctx)
        try {
            refreshBalanceUseCase(selectedService)
            refreshBalanceUseCase(ServiceType.DEEPSEEK)
        } catch (_: Exception) {
            // 静默失败
        }

        // 天气刷新
        try {
            val store = ctx.appSettingsStore
            val weatherEnabled = store.weatherEnabled.first()
            val qweatherKey = store.qweatherKey.first()
            if (weatherEnabled && qweatherKey.isNotBlank()) {
                val snapshot = weatherRepository.fetchNow(qweatherKey, ctx)
                if (snapshot != null) {
                    store.setWeatherCity(snapshot.city)
                    store.setLastWeatherFetchAt(System.currentTimeMillis())
                }
            }
        } catch (_: Exception) {
            // 静默失败
        }

        // 通知 Widget 更新
        OpenCodeGoWidgetProvider.notifyDataChanged(ctx)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget_periodic_refresh"

        /** 调度周期性刷新（≥15min），使用 KEEP 策略避免重复 */
        fun schedule(context: Context, intervalMin: Int = 15) {
            val effectiveInterval = intervalMin.coerceAtLeast(15)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<WidgetPeriodicWorker>(
                effectiveInterval.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** 取消周期性刷新 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
