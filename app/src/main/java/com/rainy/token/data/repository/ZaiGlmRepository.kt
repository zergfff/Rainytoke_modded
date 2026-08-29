package com.rainy.token.data.repository

import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.data.local.AppSettingsStore
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.data.cache.BalanceCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智谱 GLM Coding Plan 余量仓库。
 *
 * 端点与鉴权方式移植自 CodexBar（Sources/CodexBarCore/Providers/Zai）：
 *   GET https://api.z.ai/api/monitor/usage/quota/limit
 *   GET https://open.bigmodel.cn/api/monitor/usage/quota/limit   （国内版）
 *   Authorization: Bearer <API Key>
 *
 * 响应结构（实测样例）：
 *   { "code": 200, "data": { "limits": [
 *       { "type": "TOKENS_LIMIT", "unit": 3, "number": 5,
 *         "percentage": 25, "usage": 1000, "remaining": 776,
 *         "nextResetTime": 1785816000000 } ]}}
 *
 * 只用 API Key，不需要浏览器 Cookie，是最容易接入的一个。
 */
@Singleton
class ZaiGlmRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val appSettingsStore: AppSettingsStore,
    private val balanceCache: BalanceCache
) {

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.ZAI_GLM)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        if (credential !is Credential.ApiKeyCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val apiKey = credential.key.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val baseUrl = appSettingsStore.zaiBaseUrl()

        val body = try {
            val request = Request.Builder()
                .url("$baseUrl/api/monitor/usage/quota/limit")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    return@withContext Result.failure(
                        RepositoryError.InvalidCredential(
                            "GLM API Key 无效或已过期 (HTTP ${response.code})"
                        )
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RepositoryError.Network(IOException("GLM HTTP ${response.code}"))
                    )
                }
                response.body?.string() ?: throw IOException("空响应体")
            }
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: Exception) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        val limits = parseLimits(body)
        if (limits.isEmpty()) {
            return@withContext Result.failure(
                RepositoryError.Unknown(IllegalStateException("GLM 未返回任何配额窗口（API Key 可能无 Coding Plan 权限）"))
            )
        }

        // 三行映射：优先取 5小时 / 月度MCP / 其余第一个
        val fiveHour = limits.firstOrNull { it.windowMinutes in 60..<1440 }        // unit=3, number=5
        val monthly = limits.firstOrNull { it.windowMinutes >= 43200 }            // unit=1/5 的月窗口
        val weekly = limits.firstOrNull { it !in listOfNotNull(fiveHour, monthly) }

        val extras = buildMap {
            fiveHour?.let {
                put("fiveHour.used", it.percentage.toString())
                put("fiveHour.cap", "100")
                it.nextResetTime?.let { t -> put("fiveHour.resetInSec", remainingSec(t).toString()) }
            }
            weekly?.let {
                put("weekly.used", it.percentage.toString())
                put("weekly.cap", "100")
                it.nextResetTime?.let { t -> put("weekly.resetInSec", remainingSec(t).toString()) }
            }
            monthly?.let {
                put("monthly.used", it.percentage.toString())
                put("monthly.cap", "100")
                it.nextResetTime?.let { t -> put("monthly.resetInSec", remainingSec(t).toString()) }
            }
            put("plan", limits.firstOrNull { it.planName != null }?.planName ?: "GLM Coding Plan")
        }

        val config = ServiceConfigProvider.get(ServiceType.ZAI_GLM)
        // amount 用剩余百分比：100 - 最大窗口已用百分比
        val primaryUsed = listOfNotNull(monthly, weekly, fiveHour).firstOrNull()?.percentage ?: 0
        val remainingPct = (100 - primaryUsed).coerceIn(0, 100).toDouble()

        val balance = ServiceBalance(
            service = ServiceType.ZAI_GLM,
            amount = remainingPct,
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = monthly?.percentage?.toDouble(),
            totalQuota = 100.0,
            nextResetAt = monthly?.nextResetTime,
            extras = extras
        )

        balanceCache.put(ServiceType.ZAI_GLM, balance)
        credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))
        Result.success(balance)
    }

    private fun remainingSec(nextResetTime: Long): Long =
        ((nextResetTime - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    private fun parseLimits(body: String): List<LimitWindow> {
        return runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return emptyList()
            val arr: JSONArray = data.optJSONArray("limits") ?: return emptyList()

            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val unit = o.optInt("unit", -1)
                val number = o.optInt("number", 0)
                val percentage = o.optInt("percentage", 0)

                // unit: 1=天, 3=小时, 5=月(MCP), 6=未知(按天算)
                val windowMinutes = when (unit) {
                    1 -> number * 24 * 60          // 天
                    3 -> number * 60               // 小时（number=5 → 5h 窗口）
                    5 -> MONTHLY_MINUTES           // 月度 MCP
                    6 -> number * 24 * 60
                    else -> number * 60            // 兜底按小时
                }

                val rawReset = o.optLong("nextResetTime", 0L)
                // 秒级时间戳转成毫秒
                val reset = if (rawReset > 0) {
                    if (rawReset < 100_000_000_000L) rawReset * 1000 else rawReset
                } else null

                LimitWindow(
                    windowMinutes = windowMinutes,
                    percentage = percentage,
                    nextResetTime = reset,
                    planName = o.optString("type", null)
                )
            }
        }.getOrDefault(emptyList())
    }

    private data class LimitWindow(
        val windowMinutes: Int,
        val percentage: Int,
        val nextResetTime: Long?,
        val planName: String?
    )

    private companion object {
        const val MONTHLY_MINUTES = 30 * 24 * 60
    }

}
