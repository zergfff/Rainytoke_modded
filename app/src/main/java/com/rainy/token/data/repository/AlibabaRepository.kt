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
 * 阿里云百炼 Coding Plan 余量仓库。
 *
 * 移植自 CodexBar（docs/alibaba-coding-plan.md + Providers/Alibaba）：
 *   POST https://bailian.console.aliyun.com/data/api.json
 *        ?action=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2
 *        &product=broadscope-bailian&api=queryCodingPlanInstanceInfoV2
 *   （国际版 https://modelstudio.console.alibabacloud.com）
 *
 * 请求头（三选一都带上，CodexBar 文档确认）：
 *   Authorization: Bearer *** API Key>
 *   x-api-key: *** API Key>
 *   X-DashScope-API-Key: *** API Key>
 *   Content-Type: application/json
 *
 * 配额字段（codingPlanQuotaInfo）：
 *   per5HourUsedQuota / per5HourTotalQuota / per5HourQuotaNextRefreshTime
 *   perWeekUsedQuota / perWeekTotalQuota / perWeekQuotaNextRefreshTime
 *   perBillMonthUsedQuota / perBillMonthTotalQuota / perBillMonthQuotaNextRefreshTime
 *
 * 已知限制：部分国内账号即使配了 API Key，接口仍返回 ConsoleNeedLogin，
 * 此时需要改用 Cookie（浏览器会话）模式。
 */
private val ALI_QUOTA_JSON = """
{
  "codingPlanInstanceInfoRequest": {}
}
""".trimIndent()

@Singleton
class AlibabaRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val appSettingsStore: AppSettingsStore,
    private val balanceCache: BalanceCache
) {

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.ALIBABA)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        val apiKey = (credential as? Credential.ApiKeyCredential)?.key?.trim()
        val cookie = (credential as? Credential.SessionCredential)?.let { resolveCookie(it) }

        if (apiKey.isNullOrBlank() && cookie.isNullOrBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val host = appSettingsStore.alibabaHost()

        val body = try {
            val request = Request.Builder()
                .url("$host/data/api.json?action=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2&product=broadscope-bailian&api=queryCodingPlanInstanceInfoV2")
                .post(ALI_QUOTA_JSON.toRequestBody("application/json".toMediaType()))
                .apply {
                    if (!apiKey.isNullOrBlank()) {
                        header("Authorization", "Bearer $apiKey")
                        header("x-api-key", apiKey)
                        header("X-DashScope-API-Key", apiKey)
                    }
                    if (!cookie.isNullOrBlank()) {
                        header("Cookie", cookie)
                        header("Origin", host)
                        header("Referer", "$host/")
                        header("X-Requested-With", "XMLHttpRequest")
                    }
                    header("Accept", "application/json")
                    header("Content-Type", "application/json")
                }
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    throw RepositoryError.InvalidCredential("阿里云凭据无效或已过期 (HTTP ${response.code})")
                }
                if (response.code !in 200..299) {
                    throw IOException("阿里云 HTTP ${response.code}")
                }
                response.body?.string() ?: throw IOException("空响应体")
            }
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: RepositoryError) {
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        // 部分国内账号即使有 API Key 也会要求控制台登录
        if (body.contains("ConsoleNeedLogin")) {
            return@withContext Result.failure(
                RepositoryError.InvalidCredential(
                    "该账号需要用浏览器 Cookie 登录（接口返回 ConsoleNeedLogin）"
                )
            )
        }

        val parsed = parse(body)
            ?: return@withContext Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NO_WINDOWS,
                    "阿里云响应中没有可用配额字段"
                )
            )

        val config = ServiceConfigProvider.get(ServiceType.ALIBABA)
        val extras = buildMap {
            parsed.fiveHourUsed?.let {
                put("fiveHour.used", it.toString())
                put("fiveHour.cap", "100")
            }
            parsed.fiveHourReset?.let { put("fiveHour.resetInSec", it.toString()) }
            parsed.weeklyUsed?.let {
                put("weekly.used", it.toString())
                put("weekly.cap", "100")
            }
            parsed.weeklyReset?.let { put("weekly.resetInSec", it.toString()) }
            parsed.monthlyUsed?.let {
                put("monthly.used", it.toString())
                put("monthly.cap", "100")
            }
            parsed.monthlyReset?.let { put("monthly.resetInSec", it.toString()) }
            put("plan", parsed.planName ?: "阿里云 Coding Plan")
        }

        val primaryUsed = parsed.monthlyUsed ?: parsed.weeklyUsed ?: parsed.fiveHourUsed ?: 0
        val balance = ServiceBalance(
            service = ServiceType.ALIBABA,
            amount = (100 - primaryUsed).coerceIn(0, 100).toDouble(),
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = parsed.monthlyUsed?.toDouble(),
            totalQuota = 100.0,
            nextResetAt = parsed.monthlyResetMillis,
            extras = extras
        )

        balanceCache.put(ServiceType.ALIBABA, balance)
        when (credential) {
            is Credential.ApiKeyCredential ->
                credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))
            is Credential.SessionCredential ->
                credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))
            else -> Unit
        }
        Result.success(balance)
    }

    private fun parse(body: String): Parsed? = runCatching {
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: root

        val infos = data.optJSONArray("codingPlanInstanceInfos")
        val instance = infos?.optJSONObject(0)
        val quota = instance?.optJSONObject("codingPlanQuotaInfo")
            ?: data.optJSONObject("codingPlanQuotaInfo")
            ?: data

        fun window(usedKey: String, totalKey: String): Int? {
            val used = quota.optDouble(usedKey).takeIf { !it.isNaN() } ?: return null
            val total = quota.optDouble(totalKey).takeIf { !it.isNaN() } ?: return null
            if (total <= 0) return null
            return ((used / total) * 100.0).toInt().coerceIn(0, 100)
        }

        fun resetIn(key: String): Long? {
            val raw = quota.optLong(key).takeIf { it > 0 } ?: return null
            val millis = if (raw < 100_000_000_000L) raw * 1000 else raw
            return ((millis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }

        val fiveHour = window("per5HourUsedQuota", "per5HourTotalQuota")
        val weekly = window("perWeekUsedQuota", "perWeekTotalQuota")
        val monthly = window("perBillMonthUsedQuota", "perBillMonthTotalQuota")

        if (fiveHour == null && weekly == null && monthly == null) return@runCatching null

        val monthlyResetMillis = quota.optLong("perBillMonthQuotaNextRefreshTime")
            .takeIf { it > 0 }
            ?.let { if (it < 100_000_000_000L) it * 1000 else it }

        val planName = instance?.optString("planName")
            ?.takeIf { it.isNotBlank() }
            ?: instance?.optString("instanceName")?.takeIf { it.isNotBlank() }
            ?: instance?.optString("packageName")?.takeIf { it.isNotBlank() }

        Parsed(
            fiveHourUsed = fiveHour,
            weeklyUsed = weekly,
            monthlyUsed = monthly,
            fiveHourReset = resetIn("per5HourQuotaNextRefreshTime"),
            weeklyReset = resetIn("perWeekQuotaNextRefreshTime"),
            monthlyReset = resetIn("perBillMonthQuotaNextRefreshTime"),
            monthlyResetMillis = monthlyResetMillis,
            planName = planName
        )
    }.getOrNull()

    private fun resolveCookie(credential: Credential.SessionCredential): String? {
        credential.authCookie?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val joined = credential.cookies
            .filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        if (joined.isNotBlank()) return joined
        return credential.token?.takeIf { it.isNotBlank() }?.trim()
    }

    private data class Parsed(
        val fiveHourUsed: Int?,
        val weeklyUsed: Int?,
        val monthlyUsed: Int?,
        val fiveHourReset: Long?,
        val weeklyReset: Long?,
        val monthlyReset: Long?,
        val monthlyResetMillis: Long?,
        val planName: String?
    )
}
