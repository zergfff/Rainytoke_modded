package com.rainy.token.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.rainy.token.data.debug.FileDebugLog
import com.rainy.token.data.weather.QWeatherCredential
import com.rainy.token.data.weather.QWeatherJwt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 和风天气（QWeather）API 封装。
 *
 * 鉴权两代并存：
 * - **JWT（推荐）**：`Authorization: Bearer <token>`，token 由 [QWeatherJwt] 用控制台
 *   私钥在本机签发（EdDSA/Ed25519），15 分钟一换、自动续签。
 * - **API KEY（旧）**：`?key=` 查询参数，和风天气 2027-01 停用，仅作兼容。
 *
 * 请求链：GeoAPI 反查城市（`/geo/v2/city/lookup`）→ 实时天气（`/v7/weather/now`）。
 * 两个请求都必须打到**账号自己的 API Host**；公共域名（api./devapi.qweather.com）
 * 自 2026 年起返回 `403 Invalid Host`。
 *
 * 失败一律返回 [Outcome.Fail] 且带人能看懂的原因（含 HTTP 状态/和风返回码），
 * 这样设置页的「测试连接」和文件日志都能直接看出是 kid/iss/sub、host、额度还是网络的问题。
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

    sealed interface Outcome {
        data class Ok(val snapshot: Snapshot) : Outcome
        data class Fail(val reason: String) : Outcome
    }

    /**
     * 拉取当前天气。
     *
     * @param credential 和风天气凭证（JWT 私钥或旧版 API Key）
     * @param latLon 已知经纬度；为空时尝试系统粗定位（需定位权限）
     * @param context 应用上下文，用于兜底定位与文件日志，可为空
     */
    suspend fun fetchNow(
        credential: QWeatherCredential,
        latLon: Pair<Double, Double>? = null,
        context: Context? = null
    ): Outcome = withContext(Dispatchers.IO) {
        if (!credential.hasAuth) return@withContext Outcome.Fail(REASON_NO_CREDENTIAL)

        val host = credential.normalizedHost()
        if (host.isBlank()) return@withContext Outcome.Fail(REASON_NO_HOST)

        try {
            val coordinates = latLon
                ?: context?.let { getCoarseLocation(it) }?.let { it.latitude to it.longitude }
                ?: return@withContext Outcome.Fail(REASON_NO_LOCATION)
            val (lat, lon) = coordinates

            // 鉴权头：本机签发 JWT > 粘贴的现成 JWT > 旧版 ?key=
            val bearer: String?
            val keyQuery: String
            when {
                credential.usesJwt -> {
                    bearer = try {
                        QWeatherJwt.token(credential)
                    } catch (t: Throwable) {
                        log(context, "WEATHER_JWT_FAIL", "err" to describe(t))
                        return@withContext Outcome.Fail("私钥签名失败：" + describe(t))
                    }
                    keyQuery = ""
                }
                credential.pastedToken.isNotEmpty() -> {
                    bearer = credential.pastedToken
                    keyQuery = ""
                }
                else -> {
                    bearer = null
                    keyQuery = "&key=" + credential.apiKey.trim()
                }
            }

            // 1. GeoAPI：经纬度 → 城市 ID/名称
            val geoUrl = "https://$host/geo/v2/city/lookup" +
                "?location=" + String.format(Locale.US, "%.2f", lon) +
                "," + String.format(Locale.US, "%.2f", lat) + keyQuery
            val (geoStatus, geoBody) = httpGet(geoUrl, bearer)
            if (geoStatus != HTTP_OK || geoBody == null) {
                return@withContext fail(context, "GEO", geoStatus, geoBody)
            }
            val locations = JSONObject(geoBody).optJSONArray("location")
                ?: return@withContext fail(context, "GEO", geoStatus, geoBody)
            if (locations.length() == 0) {
                return@withContext Outcome.Fail("城市反查无结果（经纬度 $lat,$lon）")
            }
            val first = locations.getJSONObject(0)
            val cityId = first.optString("id", "")
            val cityName = first.optString("name", "")
            if (cityId.isBlank()) return@withContext fail(context, "GEO", geoStatus, geoBody)

            // 2. 实时天气 v7
            val weatherUrl = "https://$host/v7/weather/now?location=$cityId&lang=zh$keyQuery"
            val (weatherStatus, weatherBody) = httpGet(weatherUrl, bearer)
            if (weatherStatus != HTTP_OK || weatherBody == null) {
                return@withContext fail(context, "NOW", weatherStatus, weatherBody)
            }
            val weatherJson = JSONObject(weatherBody)
            if (weatherJson.optString("code") != "200") {
                return@withContext fail(context, "NOW", weatherStatus, weatherBody)
            }
            val now = weatherJson.optJSONObject("now")
                ?: return@withContext Outcome.Fail("天气响应缺少 now 字段")

            log(
                context, "WEATHER_OK",
                "city" to cityName,
                "temp" to now.optString("temp", ""),
                "text" to now.optString("text", ""),
                "auth" to when {
                    credential.usesJwt -> "jwt"
                    credential.pastedToken.isNotEmpty() -> "jwt-pasted"
                    else -> "key"
                }
            )
            Outcome.Ok(
                Snapshot(
                    city = cityName,
                    tempC = now.optString("temp", "--"),
                    text = now.optString("text", ""),
                    icon = now.optString("icon", "")
                )
            )
        } catch (t: Throwable) {
            log(context, "WEATHER_FAIL", "err" to describe(t))
            Outcome.Fail(describe(t))
        }
    }

    /** 返回 (HTTP 状态码, 响应体)。失败也把 body 带回来，里面是和风的错误码。 */
    private fun httpGet(url: String, bearer: String?): Pair<Int, String?> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .get()
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            response.code to response.body?.string()
        }
    }

    /** 统一失败出口：记日志 + 给人话原因 */
    private fun fail(context: Context?, stage: String, http: Int, body: String?): Outcome.Fail {
        val apiCode = body?.let { raw ->
            runCatching { JSONObject(raw).optString("code") }.getOrNull()
        }.orEmpty()
        val reason = describe(http, apiCode)
        log(
            context, "WEATHER_FAIL",
            "stage" to stage,
            "http" to http,
            "api" to apiCode,
            "body" to (body?.take(200) ?: "")
        )
        return Outcome.Fail(reason)
    }

    private fun describe(http: Int, apiCode: String = ""): String {
        val code = apiCode.trim()
        if (code.isNotEmpty() && code != "200") return "和风返回码 $code"
        return when (http) {
            HTTP_OK -> "响应解析失败"
            400 -> "HTTP 400 参数不合法（经纬度或城市 ID）"
            401 -> "HTTP 401 鉴权失败：凭据 ID / 开发者 ID / 项目 ID 或私钥不匹配，或 token 过期"
            403 -> "HTTP 403 被拒绝：API Host 不对（公共域名已停用），或额度/权限受限"
            404 -> "HTTP 404 路径不存在：检查 API Host 是否填成了别的地址"
            429 -> "HTTP 429 请求过于频繁（QPM 超限，稍后再试）"
            else -> "HTTP $http"
        }
    }

    private fun describe(t: Throwable): String =
        (t.javaClass.simpleName + ": " + (t.message ?: "")).trim()

    private fun log(context: Context?, event: String, vararg fields: Pair<String, Any?>) {
        val ctx = context ?: return
        FileDebugLog.i(ctx, event, *fields)
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
        private const val TAG = "WeatherRepository"
        private const val HTTP_OK = 200

        const val REASON_NO_CREDENTIAL = "未配置和风天气凭据"
        const val REASON_NO_HOST = "未配置 API Host（公共域名已停用，请填控制台里的专属 Host）"
        const val REASON_NO_LOCATION = "没有可用经纬度：定位权限未授予，也没有缓存坐标"

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
