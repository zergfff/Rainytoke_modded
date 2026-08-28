package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.double
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Singleton

/**
 * CommandCode Go 余额仓库。
 *
 * 调 JSON API 获取月度配额余额 + 用量窗口信息：
 *   GET https://api.commandcode.ai/alpha/billing/credits
 *   Authorization: Bearer <API Key>
 *
 * 调 subscription 端点获取计划信息（用来算已用/总量百分比）：
 *   GET https://api.commandcode.ai/alpha/billing/subscriptions
 *   Authorization: Bearer <API Key>
 */
@Singleton
class CommandCodeGoRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiBase = "https://api.commandcode.ai"

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.COMMANDCODE_GO)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential())

        if (credential !is Credential.SessionCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential())
        }

        // 会话 Cookie 由 resolveSessionCookie() 负责解析（authCookie / cookies / token）
        // 不再要求 token 字段必填——官方已废弃 API Key，改用会话 Cookie

        // 顺序拉取 credits + subscriptions（credits 是主要数据源，subscription 用于补充计划信息）
        // 两个端点都用会话 Cookie 鉴权（API Key 已被官方废弃）
        val creditsResult = runCatching { fetchCredits(credential) }
        val subResult = runCatching { fetchSubscription(credential) }

        val creditsPayload = creditsResult.getOrElse { e ->
            // 会话失效时给出明确原因，方便用户重新登录
            if (e is RepositoryError.InvalidCredential) {
                return@withContext Result.failure(e)
            }
            return@withContext Result.failure(
                if (e is IOException) RepositoryError.Network(e)
                else RepositoryError.Unknown(e)
            )
        }

        // 从订阅信息拿计划名称，查 plan catalog 拿总量
        val monthlyTotal = subResult.getOrNull()?.let { sub ->
            PLANS[sub.planId.lowercase()]
        }
        val billingPeriodEndMillis = subResult.getOrNull()?.let { parseIsoToEpoch(it.currentPeriodEnd) }

        val config = ServiceConfigProvider.get(ServiceType.COMMANDCODE_GO)

        val used = monthlyTotal?.let { total ->
            maxOf(0.0, total - creditsPayload.monthlyCredits)
        }

        val extras = buildMap {
            put("monthlyRemaining", creditsPayload.monthlyCredits.toString())
            put("purchasedCredits", creditsPayload.purchasedCredits.toString())
            put("freeCredits", creditsPayload.freeCredits.toString())
            monthlyTotal?.let { put("monthlyTotal", it.toString()) }
            used?.let { put("monthlyUsed", it.toString()) }
            creditsPayload.fiveHourUsed?.let { put("fiveHour.used", it.toString()) }
            creditsPayload.fiveHourCap?.let { put("fiveHour.cap", it.toString()) }
            creditsPayload.fiveHourResetAt?.let { put("fiveHour.resetInSec", epochToRemainingSec(it).toString()) }
            creditsPayload.weeklyUsed?.let { put("weekly.used", it.toString()) }
            creditsPayload.weeklyCap?.let { put("weekly.cap", it.toString()) }
            creditsPayload.weeklyResetAt?.let { put("weekly.resetInSec", epochToRemainingSec(it).toString()) }
            billingPeriodEndMillis?.let { 
                put("billingPeriodEnd", it.toString())
                put("monthly.resetInSec", epochToRemainingSec(it).toString())
            }
            subResult.getOrNull()?.planId?.let { put("planId", it) }
            subResult.getOrNull()?.planId?.let { put("planName", planDisplayName(it)) }
        }

        val balance = ServiceBalance(
            service = ServiceType.COMMANDCODE_GO,
            amount = creditsPayload.monthlyCredits,
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = used,
            totalQuota = monthlyTotal,
            nextResetAt = billingPeriodEndMillis,
            extras = extras
        )

        balanceCache.put(ServiceType.COMMANDCODE_GO, balance)
        credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))

        Result.success(balance)
    }

    /**
     * 构造 better-auth 会话 Cookie 头。
     *
     * CommandCode 于 2026 年改版：billing 端点不再接受 API Key，
     * 必须使用浏览器登录后的会话 Cookie（见 CodexBar 文档）。
     *
     * 取值优先级：
     *  1. `authCookie` —— 用户直接粘贴的 session_token 值
     *  2. `cookies` 列表里名为 `*session_token*` 的项（WebView 登录后自动抓取）
     *  3. `token` 字段（兼容早期把 session_token 存在这里的写法）
     */
    private fun resolveSessionCookie(credential: Credential.SessionCredential): String? {
        credential.authCookie?.takeIf { it.isNotBlank() }?.let { return it.trim() }

        credential.cookies.firstOrNull {
            it.name.contains("session_token", ignoreCase = true) && it.value.isNotBlank()
        }?.let { return it.value.trim() }

        return credential.token?.takeIf { it.isNotBlank() }?.trim()
    }

    private fun fetchCredits(credential: Credential.SessionCredential): CreditsPayload {
        val sessionToken = resolveSessionCookie(credential)
            ?: throw RepositoryError.InvalidCredential()

        val request = Request.Builder()
            .url("$apiBase/internal/billing/credits")
            .header("Cookie", "__Secure-commandcode_prod_.session_token=$sessionToken")
            .header("Accept", "application/json")
            .header("Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/")
            .get()
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
        }

        val body = response.body?.string() ?: throw IOException("空响应体")
        val root = json.parseToJsonElement(body).jsonObject
        val credits = root["credits"]?.jsonObject ?: throw IOException("缺少 credits 字段")

        fun getDouble(obj: JsonObject, key: String): Double =
            obj[key]?.jsonPrimitive?.double ?: 0.0

        fun getLong(obj: JsonObject, key: String): Long? =
            obj[key]?.jsonPrimitive?.long?.takeIf { it > 0 }

        val fiveHour = root["windowLimits"]?.jsonObject?.let { limits ->
            if (limits["limited"]?.jsonPrimitive?.content == "true") {
                limits["fiveHour"]?.jsonObject
            } else null
        }
        val weekly = root["windowLimits"]?.jsonObject?.let { limits ->
            if (limits["limited"]?.jsonPrimitive?.content == "true") {
                limits["weekly"]?.jsonObject
            } else null
        }

        return CreditsPayload(
            monthlyCredits = getDouble(credits, "monthlyCredits"),
            purchasedCredits = getDouble(credits, "purchasedCredits"),
            // 新版响应移除了 freeCredits，回退到 premium + opensource 之和
            freeCredits = getDouble(credits, "freeCredits").takeIf { it != 0.0 }
                ?: (getDouble(credits, "premiumMonthlyCredits") +
                    getDouble(credits, "opensourceMonthlyCredits")),
            fiveHourUsed = fiveHour?.let { getDouble(it, "used") },
            fiveHourCap = fiveHour?.let { getDouble(it, "cap") },
            fiveHourResetAt = fiveHour?.let { getLong(it, "resetAt") },
            weeklyUsed = weekly?.let { getDouble(it, "used") },
            weeklyCap = weekly?.let { getDouble(it, "cap") },
            weeklyResetAt = weekly?.let { getLong(it, "resetAt") }
        )
    }

    private fun fetchSubscription(credential: Credential.SessionCredential): SubscriptionPayload? {
        val sessionToken = resolveSessionCookie(credential) ?: return null

        val request = Request.Builder()
            .url("$apiBase/internal/billing/subscriptions")
            .header("Cookie", "__Secure-commandcode_prod_.session_token=$sessionToken")
            .header("Accept", "application/json")
            .header("Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/")
            .get()
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (_: Exception) {
            return null
        }

        if (!response.isSuccessful) return null
        val body = response.body?.string() ?: return null

        return try {
            val root = json.parseToJsonElement(body).jsonObject
            if (root["success"]?.jsonPrimitive?.content != "true") return null
            val data = root["data"]?.jsonObject ?: return null
            SubscriptionPayload(
                planId = data["planId"]?.jsonPrimitive?.content.orEmpty(),
                currentPeriodEnd = data["currentPeriodEnd"]?.jsonPrimitive?.content
            )
        } catch (_: Exception) { null }
    }

    private fun planDisplayName(planId: String): String =
        PLAN_NAMES[planId.lowercase()] ?: planId

    companion object {
        private const val WEB_ORIGIN = "https://commandcode.ai"

        private val PLANS = mapOf(
            "individual-go" to 10.0,
            "individual-goat" to 60.0,
            "individual-pro" to 30.0,
            "individual-max" to 150.0,
            "individual-ultra" to 300.0
        )

        private val PLAN_NAMES = mapOf(
            "individual-go" to "Go",
            "individual-goat" to "GOAT",
            "individual-pro" to "Pro",
            "individual-max" to "Max",
            "individual-ultra" to "Ultra"
        )

        /** API 返回的是 epoch millis，转为距现在的剩余秒数 */
        private fun epochToRemainingSec(epochMillis: Long): Long =
            maxOf(0L, (epochMillis - System.currentTimeMillis()) / 1000)

        private fun parseIsoToEpoch(isoStr: String?): Long? {
            if (isoStr == null) return null
            return try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(isoStr.take(19))?.time
            } catch (_: Exception) { null }
        }
    }

    private data class CreditsPayload(
        val monthlyCredits: Double,
        val purchasedCredits: Double,
        val freeCredits: Double,
        val fiveHourUsed: Double?,
        val fiveHourCap: Double?,
        val fiveHourResetAt: Long?,
        val weeklyUsed: Double?,
        val weeklyCap: Double?,
        val weeklyResetAt: Long?
    )

    private data class SubscriptionPayload(
        val planId: String,
        val currentPeriodEnd: String?
    )
}