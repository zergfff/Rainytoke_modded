package com.rainy.token.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 和风天气（QWeather）API 封装。
 *
 * 流程：粗略定位 → geo lookup → weather now
 * 返回 Snapshot(city, tempC, text, icon) 或 null。
 */
@Singleton
class WeatherRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    data class Snapshot(
        val city: String,
        val tempC: String,
        val text: String,
        val icon: String
    )

    /**
     * 获取当前天气快照。
     * @param apiKey 和风天气 API key
     * @param context 应用上下文，用于获取位置
     */
    suspend fun fetchNow(apiKey: String, context: Context): Snapshot? {
        if (apiKey.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                // 1. 获取粗略位置
                val location = getCoarseLocation(context) ?: return@withContext null
                val lat = String.format("%.2f", location.latitude)
                val lon = String.format("%.2f", location.longitude)

                // 2. Geo lookup
                val geoUrl = "https://geoapi.qweather.com/v2/city/lookup?location=$lon,$lat&key=$apiKey"
                val geoRequest = Request.Builder().url(geoUrl).build()
                val geoResponse = okHttpClient.newCall(geoRequest).execute()
                val geoBody = geoResponse.body?.string() ?: return@withContext null
                geoResponse.close()

                val geoJson = JSONObject(geoBody)
                val geoCode = geoJson.optString("code", "")
                if (geoCode != "200") return@withContext null

                val locations = geoJson.optJSONArray("location")
                if (locations == null || locations.length() == 0) return@withContext null
                val cityId = locations.getJSONObject(0).optString("id", "")
                val cityName = locations.getJSONObject(0).optString("name", "")

                if (cityId.isBlank()) return@withContext null

                // 3. Weather now
                val weatherUrl = "https://devapi.qweather.com/v7/weather/now?location=$cityId&key=$apiKey"
                val weatherRequest = Request.Builder().url(weatherUrl).build()
                val weatherResponse = okHttpClient.newCall(weatherRequest).execute()
                val weatherBody = weatherResponse.body?.string() ?: return@withContext null
                weatherResponse.close()

                val weatherJson = JSONObject(weatherBody)
                val weatherCode = weatherJson.optString("code", "")
                if (weatherCode != "200") return@withContext null

                val now = weatherJson.optJSONObject("now") ?: return@withContext null
                Snapshot(
                    city = cityName,
                    tempC = now.optString("temp", "--"),
                    text = now.optString("text", ""),
                    icon = now.optString("icon", "")
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getCoarseLocation(context: Context): android.location.Location? {
        // 检查权限
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        // 尝试网络提供者（粗略定位）
        return try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
    }
}
