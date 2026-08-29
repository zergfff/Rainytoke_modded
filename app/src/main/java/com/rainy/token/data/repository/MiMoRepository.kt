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
 * 小米 MiMo Token Plan 余量仓库。
 *
 * 移植自 CodexBar（Sources/CodexBarCore/Providers/MiMo）：
 *   GET https://platform.xiaomimimo.com/api/v1/balance
 *   GET https://platform.xiaomimimo.com/api/v1/tokenPlan/detail
 *   GET https://platform.xiaomimimo.com/api/v1/tokenPlan/usage
 *   Cookie: api-platform_serviceToken=...; userId=...
 *   Origin / Referer 必须带上，否则会被判为跨站
 *
 * MiMo 只支持 Cookie 鉴权（必须含 `api-platform_serviceToken` 与 `userId`），
 * 所以沿用 [Credential.SessionCredential]，与 CommandCode Go 同一套路。
 */
@Singleton
class MiMoRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.MIMO)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        if (credential !is Credential.SessionCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        val cookie = resolveCookie(credential)
        if (cookie.isBlank()) {
            return@withContext Result.failure(
                RepositoryError.InvalidCredential("未找到 MiMo 会话 Cookie")
            )
        }
        if (!cookie.contains("api-platform_serviceToken") || !cookie.contains("userId")) {
            return@withContext Result.failure(
                RepositoryError.InvalidCredential(
                    "MiMo Cookie 必须包含 api-platform_serviceToken 和 userId"
                )
            )
        }

        // balance 是主数据源；tokenPlan 两个端点失败不致命（用 try? 的语义）
        val balanceBody = try {
            get("$API_BASE/balance", cookie)
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: Exception) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        val tokenDetail = runCatching { get("$API_BASE/tokenPlan/detail", cookie) }.getOrNull()
        val tokenUsage = runCatching { get("$API_BASE/tokenPlan/usage", cookie) }.getOrNull()

        val parsed = parse(balanceBody, tokenDetail, tokenUsage)
            ?: return@withContext Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NO_WINDOWS,
                    "MiMo 响应中没有可用余额字段"
                )
            )

        val config = ServiceConfigProvider.get(ServiceType.MIMO)
        val extras = buildMap {
            parsed.tokenPercent?.let {
                put("fiveHour.used", it.toInt().toString())
                put("fiveHour.cap", "100")
            }
            parsed.planPeriodEnd?.let { put("weekly.resetInSec", remainingSec(it).toString()) }
            parsed.currency?.let { put("currency", it) }
            parsed.cashBalance?.let { put("cashBalance", it.toString()) }
            parsed.giftBalance?.let { put("giftBalance", it.toString()) }
            put("plan", parsed.planCode ?: "MiMo Token Plan")
        }

        val usedPct = (parsed.tokenPercent ?: 0.0).toInt().coerceIn(0, 100)
        val balance = ServiceBalance(
            service = ServiceType.MIMO,
            amount = (100 - usedPct).toDouble(),
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = usedPct.toDouble(),
            totalQuota = 100.0,
            nextResetAt = parsed.planPeriodEnd,
            extras = extras
        )

        balanceCache.put(ServiceType.MIMO, balance)
        credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))
        Result.success(balance)
    }

    /**
     * 取出 Cookie：优先 authCookie，其次 cookies 列表里的 serviceToken+userId，
     * 最后兜底 token 字段（用户直接粘整条 Cookie 头的情况）。
     */
    private fun resolveCookie(credential: Credential.SessionCredential): String {
        credential.authCookie?.takeIf { it.isNotBlank() }?.let { return it.trim() }

        val fromList = credential.cookies
            .filter { it.value.isNotBlank() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        if (fromList.isNotBlank() &&
            fromList.contains("api-platform_serviceToken") &&
            fromList.contains("userId")
        ) {
            return fromList
        }

        credential.token?.takeIf { it.isNotBlank() }?.let { return it.trim() }

        // 只有 authCookie 为空、但 cookies 里有部分值时，也返回拼好的串，让上面校验去报错
        return fromList
    }

    private fun get(url: String, cookie: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("x-timeZone", "Asia/Shanghai")
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .header("User-Agent", BROWSER_UA)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> Unit
                in 300..399, 401, 403 -> throw IOException("MiMo 会话已失效 (HTTP ${response.code})")
                else -> throw IOException("MiMo HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("空响应体")
        }
    }

    private fun remainingSec(millis: Long): Long =
        ((millis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

    private fun parse(
        balanceBody: String,
        tokenDetail: String?,
        tokenUsage: String?
    ): Parsed? = runCatching {
        val balance = JSONObject(balanceBody).optJSONObject("data")
            ?: JSONObject(balanceBody)

        val amount = balance.optDouble("balance")
            .takeIf { !it.isNaN() }
            ?: balance.optDouble("totalBalance").takeIf { !it.isNaN() }

        // tokenPlan/detail: 计划信息与周期结束时间
        val detail = tokenDetail?.let {
            JSONObject(it).optJSONObject("data") ?: JSONObject(it)
        }
        val planCode = detail?.optString("planCode")?.takeIf { it.isNotBlank() }
        val periodEndRaw = detail?.optLong("planPeriodEnd")?.takeIf { it > 0 }
            ?: detail?.optString("planPeriodEnd")?.toLongOrNull()?.takeIf { it > 0 }
        val periodEnd = periodEndRaw?.let {
            if (it < 100_000_000_000L) it * 1000 else it
        }

        // tokenPlan/usage: 已用 / 总量 → 百分比
        val usage = tokenUsage?.let {
            JSONObject(it).optJSONObject("data") ?: JSONObject(it)
        }
        val tokenUsed = usage?.optDouble("tokenUsed") ?: usage?.optDouble("used")
        val tokenLimit = usage?.optDouble("tokenLimit") ?: usage?.optDouble("limit")
        val tokenPercent = when {
            usage?.optDouble("tokenPercent")?.takeIf { !it.isNaN() } != null ->
                usage.optDouble("tokenPercent")
            tokenUsed != null && tokenLimit != null && tokenLimit > 0 ->
                (tokenUsed / tokenLimit) * 100.0
            else -> null
        }

        if (amount == null && tokenPercent == null) return@runCatching null

        Parsed(
            balance = amount,
            currency = balance.optString("currency").takeIf { it.isNotBlank() },
            cashBalance = balance.optDouble("cashBalance").takeIf { !it.isNaN() },
            giftBalance = balance.optDouble("giftBalance").takeIf { !it.isNaN() },
            planCode = planCode,
            planPeriodEnd = periodEnd,
            tokenPercent = tokenPercent
        )
    }.getOrNull()

    private data class Parsed(
        val balance: Double?,
        val currency: String?,
        val cashBalance: Double?,
        val giftBalance: Double?,
        val planCode: String?,
        val planPeriodEnd: Long?,
        val tokenPercent: Double?
    )

    private companion object {
        const val API_BASE = "https://platform.xiaomimimo.com/api/v1"
        const val ORIGIN = "https://platform.xiaomimimo.com"
        const val REFERER = "https://platform.xiaomimimo.com/#/console/balance"
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    }
}
