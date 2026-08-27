package com.rainy.token.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")


/**
 * 全局应用设置存储：主题、字体缩放、Widget 配置、天气配置。
 *
 * 使用 DataStore Preferences，通过 Context.appSettingsStore 扩展获取实例。
 */
@Singleton
class AppSettingsStore @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {

    private val ds get() = context.dataStore

    // ─── Keys ───
    private object Keys {
        val themeKey = stringPreferencesKey("theme_key")
        val fontScale = floatPreferencesKey("font_scale")
        val widgetRefreshIntervalMin = intPreferencesKey("widget_refresh_interval_min")
        val widgetSelectedServices = stringSetPreferencesKey("widget_selected_services")
        val weatherEnabled = booleanPreferencesKey("weather_enabled")
        val qweatherKey = stringPreferencesKey("qweather_key")
        val weatherCity = stringPreferencesKey("weather_city")
        val weatherLatitude = floatPreferencesKey("weather_latitude")
        val weatherLongitude = floatPreferencesKey("weather_longitude")
        val lastWeatherFetchAt = longPreferencesKey("last_weather_fetch_at")
    }

    // ─── Flows ───
    val themeKey: Flow<String> = ds.data.map { it[Keys.themeKey] ?: "strawberry" }
    val fontScale: Flow<Float> = ds.data.map { it[Keys.fontScale] ?: 1.0f }
    val widgetRefreshIntervalMin: Flow<Int> = ds.data.map { it[Keys.widgetRefreshIntervalMin] ?: 15 }
    val widgetSelectedServices: Flow<Set<String>> = ds.data.map {
        it[Keys.widgetSelectedServices] ?: setOf(
            "opencode_go", "commandcode_go", "codex", "ollama"
        )
    }
    val weatherEnabled: Flow<Boolean> = ds.data.map { it[Keys.weatherEnabled] ?: false }
    val qweatherKey: Flow<String> = ds.data.map { it[Keys.qweatherKey] ?: "" }
    val weatherCity: Flow<String> = ds.data.map { it[Keys.weatherCity] ?: "" }
    val weatherLatitude: Flow<Float> = ds.data.map { it[Keys.weatherLatitude] ?: 0f }
    val weatherLongitude: Flow<Float> = ds.data.map { it[Keys.weatherLongitude] ?: 0f }
    val lastWeatherFetchAt: Flow<Long> = ds.data.map { it[Keys.lastWeatherFetchAt] ?: 0L }

    // ─── Suspend setters（不能放在 companion object，因为 ds 是实例字段） ───
    suspend fun setThemeKey(value: String) {
        ds.edit { it[Keys.themeKey] = value }
    }

    suspend fun setFontScale(value: Float) {
        ds.edit { it[Keys.fontScale] = value }
    }

    suspend fun setWidgetRefreshIntervalMin(value: Int) {
        ds.edit { it[Keys.widgetRefreshIntervalMin] = value }
    }

    suspend fun setWidgetSelectedServices(value: Set<String>) {
        ds.edit { it[Keys.widgetSelectedServices] = value }
    }

    suspend fun setWeatherEnabled(value: Boolean) {
        ds.edit { it[Keys.weatherEnabled] = value }
    }

    suspend fun setQweatherKey(value: String) {
        ds.edit { it[Keys.qweatherKey] = value }
    }

    suspend fun setWeatherCity(value: String) {
        ds.edit { it[Keys.weatherCity] = value }
    }

    suspend fun setWeatherLocation(lat: Float, lon: Float) {
        ds.edit {
            it[Keys.weatherLatitude] = lat
            it[Keys.weatherLongitude] = lon
        }
    }

    suspend fun setLastWeatherFetchAt(value: Long) {
        ds.edit { it[Keys.lastWeatherFetchAt] = value }
    }
}

/** 顶层扩展，方便任何 Context 获取 AppSettingsStore 实例 */
val Context.appSettingsStore: AppSettingsStore
    get() = AppSettingsStore(this)
