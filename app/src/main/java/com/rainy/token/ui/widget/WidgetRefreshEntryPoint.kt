package com.rainy.token.ui.widget

import com.rainy.token.data.local.AppSettingsStore
import com.rainy.token.data.local.SecureStorage
import com.rainy.token.data.repository.WeatherRepository
import com.rainy.token.domain.usecase.RefreshBalanceUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint：让非 Hilt 组件（普通 BroadcastReceiver）获取依赖。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetRefreshEntryPoint {
    fun refreshBalanceUseCase(): RefreshBalanceUseCase
    fun appSettingsStore(): AppSettingsStore
    fun secureStorage(): SecureStorage
    fun weatherRepository(): WeatherRepository
}
