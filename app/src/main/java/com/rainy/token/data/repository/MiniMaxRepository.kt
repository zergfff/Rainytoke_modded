package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.local.AppSettingsStore
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MiniMax Coding Plan 余量仓库。
 *
 * 移植自 CodexBar（Sources/CodexBarCore/Providers/MiniMax）：
 *   GET/POST https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains
 *   （国际版 https://api.minimax.io）
 *   Authorization: Bearer <API Token>
 *
 * 响应字段（MiniMaxModelRemains，snake_case）：
 *   model_name, current_interval_total_count, current_interval_usage_count,
 *   remains_time, current_interval_remaining_percent,
 *   current_weekly_total_count, current_weekly_usage_count,
 *   weekly_remains_time, current_weekly_remaining_percent
 *
 * 优先用 API Token（用户填 API Key）；没有 token 时退回 Cookie 模式。
 */
@Singleton
class MiniMaxRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val appSettingsStore: AppSettingsStore,
    private val balanceCache: BalanceCache
) {

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.MINIMAX)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        val apiToken = (credential as? Credential.ApiKeyCredential)?.key?.trim()
        val cookie = (credential as? Credential.SessionCredential)?.let { resolveCookie(it) }

        val authMode = MiniMaxAuthMode.resolve(apiToken, cookie)
        if (authMode == MiniMaxAuthMode.NONE) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val apiBase = appSettingsStore.minimaxApiBaseUrl()

        val body = try {
            val requestBuilder = Request.Builder().url("$apiBase/v1/api/openplatform/coding_plan/remains")
            when (authMode) {
                MiniMaxAuthMode.API_TOKEN -> requestBuilder.header("Authorization", "Bearer $apiToken")
                MiniMaxAuthMode.COOKIE -> requestBuilder
                    .header("Cookie", cookie ?: "")
                    .header("Origin", "https://platform.minimaxi.com")
                    .header("Referer", "https://platform.minimaxi.com/user-center/payment/coding-plan")
                else -> throw IOException("MiniMax 无可用鉴权方式")
            }
            requestBuilder.header("Accept", "application/json")

            okHttpClient.newCall(requestBuilder.get().build()).execute().use { response ->
                handleResponse(response.code, response.body?.string())
            }
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: RepositoryError) {
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        val parsed = parse(body)
            ?: return@withContext Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NO_WINDOWS,
                    "MiniMax 响应中没有可用配额字段"
                )
            )

        val config = ServiceConfigProvider.get(ServiceType.MINIMAX)
        val extras = buildMap {
            parsed.intervalRemainingPercent?.let {
                put("fiveHour.used", (100.0 - it).toInt().toString())
                put("fiveHour.cap", "100")
            }
            parsed.intervalRemainsSec?.let { put("fiveHour.resetInSec", it.toString()) }
            parsed.weeklyRemainingPercent?.let {
                put("weekly.used", (100.0 - it).toInt().toString())
                put("weekly.cap", "100")
            }
            parsed.weeklyRemainsSec?.let { put("weekly.resetInSec", it.toString()) }
            put("plan", parsed.planName ?: "MiniMax Coding Plan")
        }

        // 主百分比取"剩余"最小者（最紧张的那个窗口）
        val primaryRemaining = listOfNotNull(
            parsed.intervalRemainingPercent,
            parsed.weeklyRemainingPercent
        ).minOrNull()

        val balance = ServiceBalance(
            service = ServiceType.MINIMAX,
            amount = primaryRemaining ?: 0.0,
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = parsed.weeklyRemainingPercent?.let { 100.0 - it },
            totalQuota = 100.0,
            nextResetAt = parsed.weeklyEndMillis ?: parsed.intervalEndMillis,
            extras = extras
        )

        balanceCache.put(ServiceType.MINIMAX, balance)
        when (credential) {
            is Credential.ApiKeyCredential ->
                credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))
            is Credential.SessionCredential ->
                credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))
            else -> Unit
        }
        Result.success(balance)
    }

    private fun handleResponse(code: Int, body: String?): String {
        if (code == 401 || code == 403) {
            throw RepositoryError.InvalidCredential("MiniMax 凭据无效或已过期 (HTTP $code)")
        }
        if (code !in 200..299) {
            throw IOException("MiniMax HTTP $code")
        }
        return body ?: throw IOException("空响应体")
    }

    private fun parse(body: String): Parsed? = runCatching {
        val root = JSONObject(body)
        // 数据可能在 data / model_remains 下；找不到就把根当数据源
        val data = root.optJSONObject("data") ?: root
        val arr = data.optJSONArray("model_remains")
            ?: data.optJSONArray("remains")
            ?: data.optJSONArray("models")
        val first = arr?.optJSONObject(0) ?: data

        fun d(vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { first.optDouble(it).takeIf { v -> !v.isNaN() } }

        fun l(vararg keys: String): Long? =
            keys.firstNotNullOfOrNull { first.optLong(it).takeIf { v -> v > 0 } }

        val intervalTotal = d("current_interval_total_count")
        val intervalUsed = d("current_interval_usage_count")
        val intervalRemainingPct = d("current_interval_remaining_percent")
            ?: remainingFromCounts(intervalUsed, intervalTotal)

        val weeklyTotal = d("current_weekly_total_count")
        val weeklyUsed = d("current_weekly_usage_count")
        val weeklyRemainingPct = d("current_weekly_remaining_percent")
            ?: remainingFromCounts(weeklyUsed, weeklyTotal)

        val intervalEnd = l("end_time")
        val weeklyEnd = l("weekly_end_time")

        if (intervalRemainingPct == null && weeklyRemainingPct == null) return@runCatching null

        Parsed(
            intervalRemainingPercent = intervalRemainingPct,
            weeklyRemainingPercent = weeklyRemainingPct,
            intervalRemainsSec = l("remains_time"),
            weeklyRemainsSec = l("weekly_remains_time"),
            intervalEndMillis = intervalEnd?.let { secToMillis(it) },
            weeklyEndMillis = weeklyEnd?.let { secToMillis(it) },
            planName = first.optString("model_name").takeIf { it.isNotBlank() }
        )
    }.getOrNull()

    private fun remainingFromCounts(used: Double?, total: Double?): Double? {
        if (used == null || total == null || total <= 0) return null
        return ((total - used) / total * 100.0).coerceIn(0.0, 100.0)
    }

    private fun secToMillis(sec: Long): Long =
        if (sec < 100_000_000_000L) sec * 1000 else sec

    private fun resolveCookie(credential: Credential.SessionCredential): String? {
        credential.authCookie?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val joined = credential.cookies
            .filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        if (joined.isNotBlank()) return joined
        return credential.token?.takeIf { it.isNotBlank() }?.trim()
    }

    private data class Parsed(
        val intervalRemainingPercent: Double?,
        val weeklyRemainingPercent: Double?,
        val intervalRemainsSec: Long?,
        val weeklyRemainsSec: Long?,
        val intervalEndMillis: Long?,
        val weeklyEndMillis: Long?,
        val planName: String?
    )

    private enum class MiniMaxAuthMode {
        API_TOKEN, COOKIE, NONE;

        companion object {
            fun resolve(token: String?, cookie: String?): MiniMaxAuthMode =
                when {
                    !token.isNullOrBlank() -> API_TOKEN
                    !cookie.isNullOrBlank() -> COOKIE
                    else -> NONE
                }
        }
    }
}
