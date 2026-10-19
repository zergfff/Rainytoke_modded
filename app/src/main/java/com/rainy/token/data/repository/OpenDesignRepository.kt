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

        // 官方桌面客户端走的是 control key（~/.amr/config.json → profiles.<profile>.controlKey），
        // 比浏览器会话 Cookie 稳定得多，且不受第三方 Cookie / 登录过期影响。
        // 有 control key 时优先用它；失败再退回 Cookie。
        val controlKey = credential.token?.trim().orEmpty()
        val cookie = buildCookieHeader(credential)

        if (controlKey.isNotBlank()) {
            val viaControlKey = fetchWithControlKey(controlKey, cookie)
            if (viaControlKey.isSuccess) {
                val balance = viaControlKey.getOrThrow()
                balanceCache.put(ServiceType.OPEN_DESIGN, balance)
                credentialRepository.save(
                    credential.copy(lastVerifiedAt = System.currentTimeMillis())
                )
                return@withContext Result.success(balance)
            }
            if (cookie.isBlank()) {
                return@withContext viaControlKey
            }
            DebugLog.e(TAG, "control key 失败，回退 Cookie: ${viaControlKey.exceptionOrNull()?.message}")
        }

        if (cookie.isBlank()) {
            return@withContext Result.failure(
                RepositoryError.InvalidCredential(
                    "缺少凭据。推荐填 Control Key（桌面客户端 ~/.amr/config.json 里的 controlKey）；" +
                        "也可以粘贴 open-design.ai / amr-api.open-design.ai 的登录 Cookie"
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

    /** 用桌面客户端的 control key 调 `/v1/me`（官方客户端同款鉴权），失败再由上层退回 Cookie。 */
    private fun fetchWithControlKey(
        controlKey: String,
        cookie: String
    ): Result<ServiceBalance> {
        val body = getJson(
            url = "$API_BASE/v1/me",
            cookie = cookie,
            workspaceId = null,
            bearer = controlKey
        ).getOrElse { return Result.failure(it) }

        val account = parseControlKeyMe(body)
            ?: return Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.MALFORMED_RESPONSE,
                    "Open Design /v1/me 响应缺少余额字段"
                )
            )

        // 部分账号 /v1/me 不带 balanceUsd，改问钱包余额接口。
        val balanceUsd = account.balanceUsd ?: run {
            val walletBody = getJson(
                url = "$API_BASE/v1/wallet/balance",
                cookie = cookie,
                workspaceId = null,
                bearer = controlKey
            ).getOrNull() ?: return Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NO_WINDOWS,
                    "无法读取 Open Design 钱包余额"
                )
            )
            parseWalletBalance(walletBody) ?: return Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.MALFORMED_RESPONSE,
                    "Open Design /v1/wallet/balance 响应缺少 balanceUsd"
                )
            )
        }

        val plan = account.plan?.takeIf { it.isNotBlank() }?.let { planDisplayName(it) }
        val extras = buildMap {
            plan?.let {
                put("plan", it)
                put("planName", it)
            }
            put("monthlyRemaining", balanceUsd.toString())
            put("authMode", "control_key")
            account.email?.takeIf { it.isNotBlank() }?.let { put("account", it) }
        }

        return Result.success(
            ServiceBalance(
                service = ServiceType.OPEN_DESIGN,
                amount = balanceUsd,
                unit = ServiceConfigProvider.get(ServiceType.OPEN_DESIGN).displayUnit,
                isAvailable = true,
                extras = extras
            )
        )
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

    private fun getJson(
        url: String,
        cookie: String?,
        workspaceId: String?,
        bearer: String? = null
    ): Result<String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .header("Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/cloud/dashboard")
            .apply {
                cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                bearer?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                workspaceId?.takeIf { it.isNotBlank() }?.let { header("x-vela-workspace-id", it) }
            }
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
                                if (bearer != null) {
                                    "Open Design Control Key 无效或已过期 (HTTP ${response.code})"
                                } else {
                                    "Open Design 登录已过期 (HTTP ${response.code})，请重新登录后粘贴新的 Cookie"
                                }
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

/** `/v1/me`（control key 鉴权）里我们关心的账号字段。 */
internal data class ParsedAccount(
    val balanceUsd: Double?,
    val plan: String?,
    val email: String?
)

/**
 * 解析 `GET /api/v1/me`（`Authorization: Bearer <controlKey>`）。
 *
 * 官方桌面客户端把它当 live account：`user.plan` + `user.balanceUsd`（USD 字符串）。
 * 兼容 `{user:{...}}` / `{data:{...}}` / 扁平三种包装。
 */
internal fun parseControlKeyMe(body: String): ParsedAccount? = runCatching {
    if (body.isBlank()) return@runCatching null
    val root = openDesignJson.parseToJsonElement(body).jsonObject
    val user = (root["user"] as? JsonObject)
        ?: (root["data"] as? JsonObject)
        ?: (root["account"] as? JsonObject)
        ?: root
    val balance = (user["balanceUsd"] as? JsonPrimitive)?.doubleOrNull
    val plan = (user["plan"] as? JsonPrimitive)?.contentOrNull
    val email = (user["email"] as? JsonPrimitive)?.contentOrNull
    if (balance == null && plan == null && email == null) return@runCatching null
    ParsedAccount(balanceUsd = balance, plan = plan, email = email)
}.getOrNull()

/** 解析 `GET /api/v1/wallet/balance` → `{"balanceUsd":"12.34","updatedAt":"..."}`。 */
internal fun parseWalletBalance(body: String): Double? = runCatching {
    if (body.isBlank()) return@runCatching null
    val root = openDesignJson.parseToJsonElement(body).jsonObject
    val wallet = (root["wallet"] as? JsonObject) ?: (root["data"] as? JsonObject) ?: root
    (wallet["balanceUsd"] as? JsonPrimitive)?.doubleOrNull
}.getOrNull()



