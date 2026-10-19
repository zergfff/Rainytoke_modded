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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
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

        // 某些账号（例如 Go 个人订阅）在 /credits/me 上返回 403 workspace_forbidden，
        // 因此它只作为附加信息，不能作为余额主来源。
        val parsed = getJson(
            url = "$API_BASE/v1/workspaces/$workspaceId/credits/me",
            cookie = cookie,
            workspaceId = workspaceId
        ).getOrNull()?.let { parseCredits(it) }

        val summary = fetchBillingSummary(cookie, bearer = null)
        val planName = summary?.plan?.takeIf { it.isNotBlank() }?.let { planDisplayName(it) }
            ?: fetchPlanName(cookie, workspaceId)
        val windows = fetchCodingPlanWindows(cookie, workspaceId)
        val config = ServiceConfigProvider.get(ServiceType.OPEN_DESIGN)

        val allocatedUsd = parsed?.allocatedUsd
        val spentUsd = allocatedUsd
            ?.takeIf { it >= (parsed?.balanceUsd ?: 0.0) }
            ?.minus(parsed?.balanceUsd ?: 0.0)
        val amountUsd = summary?.availableUsd
            ?: parsed?.balanceUsd
            ?: fetchWalletBalance(cookie)
            ?: return@withContext Result.failure(
                RepositoryError.ParseError(
                    RepositoryError.ParseErrorReason.NO_WINDOWS,
                    "无法读取 Open Design 余额（billing summary / wallet / credits 均不可用）"
                )
            )

        val extras = buildMap {
            planName?.let {
                put("plan", it)
                put("planName", it)
            }
            put("monthlyRemaining", amountUsd.toString())
            allocatedUsd?.takeIf { it > 0 }?.let {
                put("monthlyTotal", it.toString())
            }
            parsed?.rechargedUsd?.let { put("rechargedUsd", it.toString()) }
            parsed?.subscriptionUsd?.let { put("subscriptionUsd", it.toString()) }
            parsed?.lowBalanceState?.let { put("lowBalanceState", it) }
            put("workspaceId", workspaceId)
            summary?.status?.let { put("subscriptionStatus", it) }
            summary?.interval?.let { put("billingInterval", it) }
            windows.forEach { window ->
                val prefix = window.extraPrefix() ?: return@forEach
                put("$prefix.used", window.usedPercent().toString())
                put("$prefix.cap", "100")
                window.resetInSec()?.let { put("$prefix.resetInSec", it.toString()) }
            }
        }

        val balance = ServiceBalance(
            service = ServiceType.OPEN_DESIGN,
            amount = amountUsd,
            unit = config.displayUnit,
            isAvailable = summary?.status?.equals("active", true) ?: true,
            monthlySpent = summary?.consumedUsd ?: spentUsd,
            totalQuota = allocatedUsd,
            nextResetAt = summary?.periodEndMillis,
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

    /** 用桌面客户端的 control key 调官方计费接口（与客户端同款鉴权）。 */
    private fun fetchWithControlKey(
        controlKey: String,
        cookie: String
    ): Result<ServiceBalance> {
        // /v1/me.plan 是历史字段（当前账号返回 "free"），真实套餐在 billing summary 的
        // membershipTier（go/plus/pro/max），周期结束时间也在同一条响应里。
        val summaryBody = getJson(
            url = "$API_BASE/v1/billing/summary?fresh=true",
            cookie = cookie,
            workspaceId = null,
            bearer = controlKey
        ).getOrNull()

        summaryBody?.let { body ->
            val summary = parseBillingSummary(body)
            if (summary != null && summary.plan != null) {
                val plan = planDisplayName(summary.plan)
                val extras = buildMap {
                    put("plan", plan)
                    put("planName", plan)
                    put("monthlyRemaining", summary.availableUsd.toString())
                    put("authMode", "control_key")
                    summary.status?.let { put("subscriptionStatus", it) }
                    summary.interval?.let { put("billingInterval", it) }
                }
                return Result.success(
                    ServiceBalance(
                        service = ServiceType.OPEN_DESIGN,
                        amount = summary.availableUsd,
                        unit = ServiceConfigProvider.get(ServiceType.OPEN_DESIGN).displayUnit,
                        isAvailable = summary.status?.equals("active", true) ?: true,
                        monthlySpent = summary.consumedUsd,
                        nextResetAt = summary.periodEndMillis,
                        extras = extras
                    )
                )
            }
        }

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
                        ?: extractWorkspaceId(root.optJSONArray("items"))
                        ?: root.optJSONObject("workspace")?.optString("workspaceId")
                            ?.takeIf { it.isNotBlank() }
                        ?: extractWorkspaceId(root.optJSONObject("data")?.optJSONArray("items"))
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

    /** 个人订阅摘要：真实套餐、可用额度、周期结束时间。 */
    private fun fetchBillingSummary(cookie: String, bearer: String?): ParsedBillingSummary? =
        getJson(
            url = "$API_BASE/v1/billing/summary?fresh=true",
            cookie = cookie,
            workspaceId = null,
            bearer = bearer
        ).getOrNull()?.let { parseBillingSummary(it) }

    /** Coding Plan 5h / 7d 窗口（官方控制台同款接口，需要会话 Cookie）。 */
    private fun fetchCodingPlanWindows(cookie: String, workspaceId: String): List<CodingPlanWindow> =
        getJson(
            url = "$API_BASE/v1/workspaces/$workspaceId/billing/coding-plan-usage",
            cookie = cookie,
            workspaceId = workspaceId
        ).getOrNull()?.let { parseCodingPlanUsage(it) }.orEmpty()

    /** 钱包余额（Cookie 或 Control Key 均可）。 */
    private fun fetchWalletBalance(cookie: String): Double? =
        getJson(
            url = "$API_BASE/v1/wallet/balance",
            cookie = cookie,
            workspaceId = null
        ).getOrNull()?.let { parseWalletBalance(it) }

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

/** `/v1/billing/summary`（control key 鉴权）里的套餐与额度字段。 */
internal data class ParsedBillingSummary(
    val plan: String?,
    val availableUsd: Double,
    val consumedUsd: Double?,
    val status: String?,
    val interval: String?,
    val periodEndMillis: Long?
)

/**
 * 解析 `GET /api/v1/billing/summary`。
 *
 * 关键字段：`membershipTier`（真实套餐）、`balances.totalAvailableCreditsUsd`、
 * `totalConsumedUsd`、`subscriptionCurrentPeriodEnd`。`/v1/me.plan` 不可用于套餐判断。
 */
internal fun parseBillingSummary(body: String): ParsedBillingSummary? = runCatching {
    if (body.isBlank()) return@runCatching null
    val root = openDesignJson.parseToJsonElement(body).jsonObject
    fun num(key: String): Double? = (root[key] as? JsonPrimitive)?.doubleOrNull
    fun text(key: String): String? = (root[key] as? JsonPrimitive)?.contentOrNull

    val plan = text("membershipTier")?.takeIf { it.isNotBlank() }
    val available = num("totalAvailableCreditsUsd")
        ?: num("balanceUsd")
        ?: return@runCatching null

    ParsedBillingSummary(
        plan = plan,
        availableUsd = available,
        consumedUsd = num("totalConsumedUsd"),
        status = text("subscriptionStatus")?.takeIf { it.isNotBlank() },
        interval = text("billingInterval")?.takeIf { it.isNotBlank() },
        periodEndMillis = text("subscriptionCurrentPeriodEnd")
            ?.let { raw -> runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull() }
    )
}.getOrNull()

/**
 * Coding Plan 的一个用量窗口（5 小时 / 7 天）。
 *
 * 响应形状（控制台 bundle 的消费方确认）：
 * `windows: [{ policyId, durationSeconds, remainingCredits, limitCredits, resetsAt }]`
 */
internal data class CodingPlanWindow(
    val durationSeconds: Long?,
    val remaining: Double,
    val limit: Double,
    val resetsAt: String?
) {
    /** 映射到 UI 使用的窗口 key；无法识别时返回 null。 */
    fun extraPrefix(): String? = when {
        durationSeconds == null -> null
        durationSeconds in 17_000L..19_000L -> "fiveHour"
        durationSeconds in 590_000L..620_000L -> "weekly"
        else -> null
    }

    /** 已用百分比（0-100），UI 按 used/cap 绘制进度条。 */
    fun usedPercent(): Double {
        if (limit <= 0) return 0.0
        return (((limit - remaining) / limit) * 100).coerceIn(0.0, 100.0)
    }

    fun resetInSec(): Long? = resetsAt?.let { raw ->
        runCatching {
            (java.time.Instant.parse(raw).toEpochMilli() - System.currentTimeMillis()) / 1000
        }.getOrNull()?.coerceAtLeast(0)
    }
}

internal fun parseCodingPlanUsage(body: String): List<CodingPlanWindow> = runCatching {
    if (body.isBlank()) return@runCatching emptyList()
    val root = openDesignJson.parseToJsonElement(body).jsonObject
    val windows = root["windows"] as? JsonArray ?: return@runCatching emptyList()
    windows.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val remaining = (obj["remainingCredits"] as? JsonPrimitive)?.doubleOrNull
            ?: return@mapNotNull null
        val limit = (obj["limitCredits"] as? JsonPrimitive)?.doubleOrNull
            ?: return@mapNotNull null
        CodingPlanWindow(
            durationSeconds = (obj["durationSeconds"] as? JsonPrimitive)?.longOrNull,
            remaining = remaining,
            limit = limit,
            resetsAt = (obj["resetsAt"] as? JsonPrimitive)?.contentOrNull
        )
    }
}.getOrDefault(emptyList())




