package com.rainy.token.data.repository

import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import com.rainy.token.data.cache.BalanceCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moonshot Kimi Code 余量仓库。
 *
 * 移植自 CodexBar（Sources/CodexBarCore/Providers/Kimi）：
 *   GET https://api.kimi.com/coding/v1/usages
 *   Authorization: Bearer <API Key>
 *
 * 响应结构（CodexBar 的 KimiCodeAPIUsageResponse）：
 *   { "usage":  { "limit": "...", "used": "...", "remaining": "...", "resetTime": "..." },
 *     "limits": [ { "ratio": 0.25, "resetTime": "..." } ] }
 *
 * 只实现 API Key 模式（CodexBar 文档里的 Method 1，推荐方式），
 * 不实现 Kimi Code CLI / OAuth —— 那两套要处理 token 刷新与设备头，成本高。
 */
@Singleton
class KimiRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.KIMI)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        if (credential !is Credential.ApiKeyCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val apiKey = credential.key.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val body = try {
            val request = Request.Builder()
                .url(USAGES_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    return@withContext Result.failure(
                        RepositoryError.InvalidCredential(
                            "Kimi API Key 无效或已过期 (HTTP ${response.code})"
                        )
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RepositoryError.Network(IOException("Kimi HTTP ${response.code}"))
                    )
                }
                response.body?.string() ?: throw IOException("空响应体")
            }
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: Exception) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        val parsed = parse(body)
            ?: return@withContext Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NO_WINDOWS,
                    "Kimi 响应中没有可用用量字段"
                )
            )

        val config = ServiceConfigProvider.get(ServiceType.KIMI)
        val usedPct = ((parsed.usedRatio ?: parsed.limitRatio ?: 0.0) * 100).toInt().coerceIn(0, 100)
        val limitPct = ((parsed.limitRatio ?: 0.0) * 100).toInt().coerceIn(0, 100)

        val extras: Map<String, String> = buildMap {
            put("fiveHour.used", usedPct.toString())
            put("fiveHour.cap", "100")
            if (parsed.limitRatio != null) {
                put("weekly.used", limitPct.toString())
                put("weekly.cap", "100")
            }
            val resetText = parsed.resetTimeText
            if (resetText != null) {
                put("weekly.resetInSec", remainingSec(resetText).toString())
            }
            put("plan", "Kimi Code")
        }

        val balance = ServiceBalance(
            service = ServiceType.KIMI,
            amount = (100 - usedPct).toDouble(),
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = usedPct.toDouble(),
            totalQuota = 100.0,
            nextResetAt = parsed.resetTimeText?.let { parseToMillis(it) },
            extras = extras
        )

        balanceCache.put(ServiceType.KIMI, balance)
        credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))
        Result.success(balance)
    }

    private fun remainingSec(resetTimeText: String): Long {
        val millis = parseToMillis(resetTimeText) ?: return 0L
        return ((millis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }

    /** resetTime 可能是 ISO 字符串，也可能是秒级/毫秒级时间戳 */
    private fun parseToMillis(raw: String): Long? {
        val asLong = raw.toLongOrNull()
        if (asLong != null) {
            return if (asLong < 100_000_000_000L) asLong * 1000 else asLong
        }
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private fun parse(body: String): Parsed? = runCatching {
        val root = JSONObject(body)

        // usage: { limit / used / remaining }
        val usage = root.optJSONObject("usage")
        val usedRatio = usage?.optString("used")?.toDoubleOrNull()
            ?: usage?.optString("remaining")?.toDoubleOrNull()?.let { 1.0 - it }

        // limits: [ { ratio, resetTime } ]
        val limits = root.optJSONArray("limits")
        val first = limits?.optJSONObject(0)
        val limitRatio = first?.optDouble("ratio")?.takeIf { !it.isNaN() }
        val resetTime = first?.optString("resetTime")
            ?: usage?.optString("resetTime")

        if (usedRatio == null && limitRatio == null) return@runCatching null

        Parsed(usedRatio = usedRatio, limitRatio = limitRatio, resetTimeText = resetTime)
    }.getOrNull()

    private data class Parsed(
        val usedRatio: Double?,
        val limitRatio: Double?,
        val resetTimeText: String?
    )

    private companion object {
        const val USAGES_URL = "https://api.kimi.com/coding/v1/usages"
    }
}
