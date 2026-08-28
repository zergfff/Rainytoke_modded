package com.rainy.token.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
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
 * 使用 **实时天气 v7**（`weather/now`）+ 开发者专属 API Host。
 * 认证：`key=<API KEY>` 查询参数（API KEY 签名方式已废弃）。
 *
 * 定位优先使用上次成功定位的经纬度（由 WidgetPeriodicWorker 按刷新间隔更新），
 * 无缓存时才回退到系统 LocationManager 粗略定位。
 *
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
     *
     * @param apiKey 和风天气 API KEY
     * @param apiHost 开发者专属 API Host，如 abc123.def.qweatherapi.com
     *                留空则回退到旧共享域名（2026 起逐步停用，仅兜底）
     * @param latLon 已知经纬度（来自设置或上次定位），为 null 时自动定位
     * @param context 应用上下文，用于回退定位
     */
    suspend fun fetchNow(
        apiKey: String,
        apiHost: String = "",
        latLon: Pair<Double, Double>? = null,
        context: Context? = null
    ): Snapshot? {
        if (apiKey.isBlank()) return null

        return withContext(Dispatchers.IO) {
            try {
                // 1. 确定经纬度：优先入参，其次系统定位
                val (lat, lon) = latLon ?: (context?.let { getCoarseLocation(it) }?.let {
                    it.latitude to it.longitude
                } ?: return@withContext null)

                val host = apiHost.trim().ifBlank { FALLBACK_HOST }
                    .removePrefix("https://").removePrefix("http://")
                    .trimEnd('/')

                // 2. Geo lookup（v2 city/lookup，按经纬度反查城市）
                val geoUrl = "https://$host/geo/v2/city/lookup" +
                    "?location=${"%.2f".format(lon)},${"%.2f".format(lat)}&key=$apiKey"
                val geoBody = httpGet(geoUrl) ?: return@withContext null
                val geoJson = JSONObject(geoBody)
                if (geoJson.optString("code") != "200") return@withContext null

                val locations = geoJson.optJSONArray("location")
                    ?: return@withContext null
                if (locations.length() == 0) return@withContext null
                val cityId = locations.getJSONObject(0).optString("id", "")
                val cityName = locations.getJSONObject(0).optString("name", "")
                if (cityId.isBlank()) return@withContext null

                // 3. 实时天气 v7
                val weatherUrl = "https://$host/v7/weather/now" +
                    "?location=$cityId&key=$apiKey&lang=zh"
                val weatherBody = httpGet(weatherUrl) ?: return@withContext null
                val weatherJson = JSONObject(weatherBody)
                if (weatherJson.optString("code") != "200") return@withContext null

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

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val response = okHttpClient.newCall(request).execute()
        return try {
            if (!response.isSuccessful) null else response.body?.string()
        } finally {
            response.close()
        }
    }

    @SuppressLint("MissingPermission")
    fun getCoarseLocation(context: Context): android.location.Location? {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        return try {
            if (BuildCompat.isEnabled(lm, LocationManager.NETWORK_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private object BuildCompat {
        fun isEnabled(lm: LocationManager, provider: String): Boolean =
            try {
                lm.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
    }

    companion object {
        /** 旧共享域名兜底（官方公告 2026 起逐步停用，仅作兼容） */
        private const val FALLBACK_HOST = "devapi.qweather.com"

        /**
         * 按和风天气图标码返回本地 vector drawable 资源名。
         * 图标来自 https://icons.qweather.com （qwd/Icons）。
         * 无对应资源时回退到 qw_999（未知）。
         */
        fun iconDrawableName(iconCode: String): String {
            val code = iconCode.trim()
            if (code.isEmpty()) return "qw_999"
            // 资源名前缀 qw_ + 代码（如 qw_100）
            return "qw_$code"
        }

        /** 已知存在的图标码集合，用于校验避免 Resources.NotFoundException */
        private val VALID_ICONS = setOf(
            "100", "101", "102", "103", "104", "150", "151", "152", "153",
            "300", "301", "302", "303", "304", "305", "306", "307", "308",
            "309", "310", "311", "312", "313", "314", "315", "316", "317",
            "318", "350", "351", "399", "400", "401", "402", "403", "404",
            "405", "406", "407", "408", "409", "410", "456", "457", "499",
            "500", "501", "502", "503", "504", "507", "508", "509", "510",
            "511", "512", "513", "514", "515", "800", "801", "802", "803",
            "804", "805", "806", "807", "900", "901", "999"
        )

        fun hasIcon(iconCode: String): Boolean = iconCode.trim() in VALID_ICONS
    }
}
