package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open Design Cloud 余额仓库。
 *
 * 控制台（SPA）与 API 分属两个域：
 *  - 页面：https://open-design.ai/cloud/dashboard?workspaceId=...
 *  - 接口：https://amr-api.open-design.ai/api
 *
 * 鉴权只有 better-auth 的会话 Cookie（`credentials: "include"`），没有对外开放的
 * 余额 API Key。因此本仓库读取 [Credential.SessionCredential] 里的 Cookie，
 * 直接调用控制台自己用的
 * ```
 * GET /api/v1/workspaces/{workspaceId}/credits/me
 * ```
 * 响应（控制台 `credit` 对象，金额单位 = 1/10000 USD）：
 * ```json
 * {
 *   "workspaceMemberId": "...",
 *   "totalBalance": 123400,
 *   "totalAllocated": 1200000,
 *   "totalRecharged": 0,
 *   "remainingBySource": { "subscription": 123400, "recharge": 0 },
 *   "lowBalanceState": "ok",
 *   "periodEndAt": "2026-10-26T08:00:00.000Z"
 * }
 * ```
 * 金额换算常量取自控制台 bundle（`vc = 1e4`，`Lr`），`displayUsd = totalBalance / 1e4`。
 *
 * workspaceId 未填写时自动调 `GET /api/v1/workspaces` 取当前工作区。
 */
@Singleton
class OpenDesignRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.OPEN_DESIGN)
            ?: return@withContext Result.failure(
                RepositoryError.InvalidCredential("未找到 Open Design 凭据")
            )
        if (credential !is Credential.SessionCredential) {
            return@withContext Result.failure(
                RepositoryError.InvalidCredential("凭据类型不匹配")
            )
        }

        val cookie = buildCookieHeader(credential)
        if (cookie.isBlank()) {
            return@withContext Result.failure(
                RepositoryError.InvalidCredential(
                    "缺少登录 Cookie。请先在浏览器登录 open-design.ai，再把 " +
                        "open-design.ai / amr-api.open-design.ai 的 Cookie 粘贴到设置页"
                )
            )
        }

        val workspaceId = credential.workspaceId?.trim().orEmpty().ifBlank {
            discoverWorkspaceId(cookie).getOrElse { error ->
                return@withContext Result.failure(error)
            }
        }

        val body = getJson(
            url = "$API_BASE/v1/workspaces/$workspaceId/credits/me",
            cookie = cookie,
            workspaceId = workspaceId
        ).getOrElse { error ->
            return@withContext Result.failure(error)
        }

        val parsed = parseCredits(body)
            ?: return@withContext Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NO_WINDOWS,
                    "Open Design 响应中没有 totalBalance 字段"
                )
            )

        val planName = fetchPlanName(cookie, workspaceId)
        val config = ServiceConfigProvider.get(ServiceType.OPEN_DESIGN)

        val allocatedUsd = parsed.allocatedUsd
        val spentUsd = allocatedUsd
            ?.takeIf { it >= parsed.balanceUsd }
            ?.minus(parsed.balanceUsd)

        val extras = buildMap {
            planName?.let {
                put("plan", it)
                put("planName", it)
            }
            put("monthlyRemaining", parsed.balanceUsd.toString())
            allocatedUsd?.takeIf { it > 0 }?.let {
                put("monthlyTotal", it.toString())
            }
            parsed.rechargedUsd?.let { put("rechargedUsd", it.toString()) }
            parsed.subscriptionUsd?.let { put("subscriptionUsd", it.toString()) }
            parsed.lowBalanceState?.let { put("lowBalanceState", it) }
            put("workspaceId", workspaceId)
            parsed.periodEndAt?.let { iso ->
                put("billingPeriodEnd", iso)
                parseIsoMillis(iso)?.let { end ->
                    val resetInSec = ((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    put("monthly.resetInSec", resetInSec.toString())
                }
            }
        }

        val balance = ServiceBalance(
            service = ServiceType.OPEN_DESIGN,
            amount = parsed.balanceUsd,
            unit = config.displayUnit,
            isAvailable = true,
            monthlySpent = spentUsd,
            totalQuota = allocatedUsd,
            nextResetAt = parsed.periodEndAt?.let { parseIsoMillis(it) },
            extras = extras
        )

        balanceCache.put(ServiceType.OPEN_DESIGN, balance)
        credentialRepository.save(
            credential.copy(
                workspaceId = workspaceId,
                lastVerifiedAt = System.currentTimeMillis()
            )
        )
        Result.success(balance)
    }

    /** 调 `/v1/workspaces` 取当前工作区 id。 */
    private fun discoverWorkspaceId(cookie: String): Result<String> {
        val body = getJson("$API_BASE/v1/workspaces", cookie, workspaceId = null)
            .getOrElse { return Result.failure(it) }

        val parsed = runCatching {
            when (val root = JSONTokener(body).nextValue()) {
                is JSONArray -> extractWorkspaceId(root)
                is JSONObject -> {
                    root.optString("activeWorkspaceId").takeIf { it.isNotBlank() }
                        ?: extractWorkspaceId(root.optJSONArray("workspaces"))
                        ?: root.optJSONObject("workspace")?.optString("workspaceId")
                            ?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }.getOrNull()

        return parsed?.let { Result.success(it) } ?: Result.failure(
            RepositoryError.ParseError(
                RepositoryError.ParseErrorReason.MALFORMED_RESPONSE,
                "无法自动识别 Open Design workspaceId，请在设置页手动填写"
            )
        )
    }

    private fun extractWorkspaceId(array: JSONArray?): String? {
        if (array == null) return null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            obj.optString("workspaceId").takeIf { it.isNotBlank() }?.let { return it }
            obj.optString("id").takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** 余额接口不带套餐信息，额外查一次 dashboard（失败不影响余额）。 */
    private fun fetchPlanName(cookie: String, workspaceId: String): String? = runCatching {
        val body = getJson(
            url = "$API_BASE/v1/workspaces/$workspaceId/dashboard",
            cookie = cookie,
            workspaceId = workspaceId
        ).getOrNull() ?: return@runCatching null

        val root = JSONObject(body)
        val raw = root.optJSONObject("subscriptionSummary")?.optString("planId")
            ?.takeIf { it.isNotBlank() }
            ?: root.optString("planId").takeIf { it.isNotBlank() }
        raw?.let { planDisplayName(it) }
    }.getOrNull()

    private fun planDisplayName(raw: String): String = when (raw.lowercase()) {
        "free" -> "Free"
        "go" -> "Go"
        "plus" -> "Plus"
        "pro" -> "Pro"
        "max" -> "Max"
        else -> raw
    }

    private fun getJson(url: String, cookie: String, workspaceId: String?): Result<String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .header("Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/cloud/dashboard")
            .header("Cookie", cookie)
            .apply { workspaceId?.takeIf { it.isNotBlank() }?.let { header("x-vela-workspace-id", it) } }
            .get()
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> Result.success(body)
                    response.code == 401 || response.code == 403 ->
                        Result.failure(
                            RepositoryError.InvalidCredential(
                                "Open Design 登录已过期 (HTTP ${response.code})，请重新登录后粘贴新的 Cookie"
                            )
                        )
                    response.code == 429 -> Result.failure(
                        RepositoryError.RateLimited(
                            response.header("Retry-After")?.toLongOrNull()
                        )
                    )
                    response.code in 500..599 -> Result.failure(
                        RepositoryError.ServerError(response.code)
                    )
                    else -> {
                        DebugLog.e(TAG, "HTTP ${response.code} $url body=${body.take(300)}")
                        Result.failure(
                            RepositoryError.InvalidCredential(
                                "Open Design 请求失败 (HTTP ${response.code})"
                            )
                        )
                    }
                }
            }
        } catch (e: IOException) {
            Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            Result.failure(RepositoryError.Unknown(e))
        }
    }

    private fun buildCookieHeader(credential: Credential.SessionCredential): String =
        credential.cookies
            .filter { it.name.isNotBlank() && it.value.isNotBlank() }
            .joinToString("; ") { "${it.name}=${it.value}" }
            .ifBlank { credential.authCookie?.trim().orEmpty() }

    private fun parseIsoMillis(raw: String): Long? = runCatching {
        Instant.parse(raw).toEpochMilli()
    }.getOrNull()

    internal data class ParsedCredit(
        val balanceUsd: Double,
        val allocatedUsd: Double?,
        val rechargedUsd: Double?,
        val subscriptionUsd: Double?,
        val lowBalanceState: String?,
        val periodEndAt: String?
    )

    private companion object {
        const val TAG = "OpenDesign"
        const val API_BASE = "https://amr-api.open-design.ai/api"
        const val WEB_ORIGIN = "https://open-design.ai"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

/** 控制台 bundle 里的 `Lr`（1e4）：账本金额 = USD × 10000。 */
private const val UNITS_PER_USD = 10_000.0

private val openDesignJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 解析 `/v1/workspaces/{id}/credits/me` 响应。
 *
 * 独立成顶层函数，便于纯 JVM 单测（Repository 依赖 Android 的 DataStore / Keystore）。
 */
internal fun parseCredits(body: String): OpenDesignRepository.ParsedCredit? = runCatching {
    if (body.isBlank()) return@runCatching null
    val root = openDesignJson.parseToJsonElement(body).jsonObject
    val credit = (root["credit"] as? JsonObject)
        ?: (root["data"] as? JsonObject)
        ?: root

    fun number(key: String): Double? = (credit[key] as? JsonPrimitive)?.doubleOrNull
    fun text(key: String): String? = (credit[key] as? JsonPrimitive)?.contentOrNull

    val total = number("totalBalance") ?: return@runCatching null
    OpenDesignRepository.ParsedCredit(
        balanceUsd = total / UNITS_PER_USD,
        allocatedUsd = number("totalAllocated")?.div(UNITS_PER_USD),
        rechargedUsd = number("totalRecharged")?.div(UNITS_PER_USD),
        subscriptionUsd = (credit["remainingBySource"] as? JsonObject)
            ?.let { (it["subscription"] as? JsonPrimitive)?.doubleOrNull }
            ?.div(UNITS_PER_USD),
        lowBalanceState = text("lowBalanceState")?.takeIf { it.isNotBlank() },
        periodEndAt = text("periodEndAt")?.takeIf { it.isNotBlank() }
    )
}.getOrNull()


