package com.rainy.token.data.repository

import com.rainy.token.data.cache.BalanceCache
import com.rainy.token.data.debug.DebugLog
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.ServiceBalance
import com.rainy.token.domain.model.TriggerSummary
import com.rainy.token.domain.service.ServiceConfigProvider
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Singleton

/**
 * OpenCode Go 配额仓库。
 *
 * 2026-09 OpenCode 改版：控制台从 SolidStart SSR 站迁移为独立的 tRPC SPA（`/console`），
 * 旧的「抓 HTML 里 `rollingUsage/weeklyUsage/monthlyUsage` hydration 字段」的做法彻底失效
 * —— SSR HTML 里只剩一个空的 `<div id="app">`，没有任何用量数据。
 *
 * 新实现改为调官方 JSON 端点：
 * ```
 * GET https://opencode.ai/console/api/go/status
 * ```
 * 响应（节选）：
 * ```json
 * {
 *   "subscriberUserId": "usr_...",
 *   "product": "go",
 *   "cancelAtPeriodEnd": false,
 *   "renewalPending": false,
 *   "access": {
 *     "startsAt": 1790675391700,
 *     "endsAt": 1793267391700,
 *     "meters": {
 *       "fiveHour": { "startsAt":..., "resetsAt":..., "limitMicroCents":"1200000000", "usedMicroCents":"..." },
 *       "week":     { "startsAt":..., "resetsAt":..., "limitMicroCents":"3000000000", "usedMicroCents":"..." },
 *       "month":    {                "resetsAt":..., "limitMicroCents":"6000000000", "usedMicroCents":"..." }
 *     }
 *   }
 * }
 * ```
 * 金额单位是 **microCents**（1 USD = 100_000_000 microCents），与旧版 `usagePercent` 语义不同，
 * 所以这里按 used/limit 现算百分比，extras 的 key 名保持不变，UI 侧无需改动。
 *
 * 鉴权用登录后的会话 Cookie：生产环境名 `__Host-console_session`（开发环境 `console_session`），
 * 值就是 DevTools 里那串以 `Fe26.2` 开头的长字符。
 *
 * 不在类上加 @Inject constructor —— 在 [com.rainy.token.di.NetworkModule] 里 @Provides 显式提供。
 * 规避 KSP 2.x 多文件 @Inject 跨依赖的"could not be resolved"误报。
 */
@Singleton
class OpenCodeGoRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository,
    private val balanceCache: BalanceCache
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchBalance(): Result<ServiceBalance> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.OPENCODE_GO)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("未找到 OpenCode Go 凭据"))

        if (credential !is Credential.SessionCredential) {
            return@withContext Result.failure(RepositoryError.InvalidCredential("凭据类型不匹配"))
        }

        // 新版 Console 不再需要 workspaceId 定位，workspaceId 只用于展示；
        // 鉴权统一走会话 Cookie。
        val session = resolveSessionCookie(credential)
        if (session.isNullOrBlank()) {
            return@withContext Result.failure(
                RepositoryError.InvalidCredential(
                    "缺少会话 Cookie。请在浏览器登录 opencode.ai 后，从 DevTools → Application → Cookies " +
                    "复制 __Host-console_session 的值（即那串以 Fe26.2 开头的长字符）"
                )
            )
        }

        val request = Request.Builder()
            .url(GO_STATUS_API)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .header("Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/console")
            .header("Cookie", buildCookieHeader(credential, session))
            .get()
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            return@withContext Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            return@withContext Result.failure(RepositoryError.Unknown(e))
        }

        response.use { resp ->
            val body = resp.body?.string().orEmpty()

            if (!resp.isSuccessful) {
                DebugLog.e(TAG, "go/status: HTTP ${resp.code} body=${body.take(300)}")
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext Result.failure(
                        RepositoryError.InvalidCredential(
                            "会话已失效 (HTTP ${resp.code})。请重新登录 opencode.ai 并复制最新的 " +
                            "__Host-console_session Cookie"
                        )
                    )
                }
                return@withContext Result.failure(RepositoryError.ServerError(resp.code))
            }

            // tRPC 用 _tag 区分结果：失败时返回 {"_tag":"Unauthorized"}，成功时是数据对象本身
            if (body.contains("\"_tag\":\"Unauthorized\"") || body.contains("\"_tag\": \"Unauthorized\"")) {
                return@withContext Result.failure(
                    RepositoryError.InvalidCredential("会话无效或已过期，请重新登录 opencode.ai")
                )
            }

            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                return@withContext Result.failure(
                    RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.NOT_JSON_OBJECT,
                        "响应不是合法 JSON：${body.take(160)}"
                    )
                )
            }

            val access = root["access"]?.jsonObject
            if (access == null) {
                // access 为 null 表示当前没有生效的 Go 订阅
                val renewalPending = root["renewalPending"]?.asBoolean() == true
                return@withContext Result.failure(
                    RepositoryError.Unknown(
                        IllegalStateException(
                            if (renewalPending) "Go 订阅正在续费中，尚未生效，请稍后重试"
                            else "未找到有效的 OpenCode Go 订阅（access 为空）"
                        )
                    )
                )
            }

            val meters = access["meters"]?.jsonObject
            if (meters == null) {
                return@withContext Result.failure(
                    RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.NO_WINDOWS,
                        "响应缺少 access.meters，无法解析配额窗口"
                    )
                )
            }

            val fiveHour = meters["fiveHour"]?.jsonObject?.toGoWindow()
            val week = meters["week"]?.jsonObject?.toGoWindow()
            val month = meters["month"]?.jsonObject?.toGoWindow()

            if (fiveHour == null && week == null && month == null) {
                return@withContext Result.failure(
                    RepositoryError.ParseError(
                        RepositoryError.ParseErrorReason.NO_WINDOWS,
                        "三个配额窗口（fiveHour/week/month）均为空"
                    )
                )
            }

            // 主体数据用 5 小时滚动窗口，这是用户最关心的"实时配额"
            val primary = fiveHour ?: week ?: month!!
            val config = ServiceConfigProvider.get(ServiceType.OPENCODE_GO)

            val product = root["product"]?.jsonPrimitive?.contentOrNull ?: "go"
            val planName = if (product == "go-plus") "Go Plus" else "Go"

            // 把 3 个窗口的用量百分比 + 重置时间全部塞进 extras（key 名与旧版保持一致，UI 无需改动）
            val extras = buildMap {
                fiveHour?.let { w ->
                    put("rolling.pct", w.usagePercent().toString())
                    put("rolling.resetInSec", w.resetInSec().toString())
                    w.usedUsd?.let { put("rolling.usage", it.toString()) }
                    w.limitUsd?.let { put("rolling.limit", it.toString()) }
                }
                week?.let { w ->
                    put("weekly.pct", w.usagePercent().toString())
                    put("weekly.resetInSec", w.resetInSec().toString())
                    w.usedUsd?.let { put("weekly.usage", it.toString()) }
                    w.limitUsd?.let { put("weekly.limit", it.toString()) }
                }
                month?.let { w ->
                    put("monthly.pct", w.usagePercent().toString())
                    put("monthly.resetInSec", w.resetInSec().toString())
                    w.usedUsd?.let { put("monthly.usage", it.toString()) }
                    w.limitUsd?.let { put("monthly.limit", it.toString()) }
                }
                put("product", product)
                put("planName", planName)
                root["cancelAtPeriodEnd"]?.jsonPrimitive?.let { put("cancelAtPeriodEnd", it.content) }
                root["useBalance"]?.jsonPrimitive?.let { put("useBalance", it.content) }
                access["endsAt"]?.asLongOrNull()?.let { put("periodEnd", it.toString()) }
            }

            val balance = ServiceBalance(
                service = ServiceType.OPENCODE_GO,
                amount = primary.usagePercent().toDouble(),
                unit = "%",
                isAvailable = true,
                monthlySpent = month?.usedUsd,
                totalQuota = month?.limitUsd,
                nextResetAt = primary.resetsAt ?: month?.resetsAt,
                extras = extras
            )

            balanceCache.put(ServiceType.OPENCODE_GO, balance)
            credentialRepository.save(credential.copy(lastVerifiedAt = System.currentTimeMillis()))

            DebugLog.i(
                TAG,
                "go/status ok: 5h=${primary.usagePercent()}% plan=$planName " +
                    "month=${month?.usedUsd ?: "-"}/${month?.limitUsd ?: "-"}"
            )
            Result.success(balance)
        }
    }

    /**
     * 解析会话 Cookie 值。
     *
     * 新版 Console 的会话 cookie 名是 `__Host-console_session`（生产）/ `console_session`（开发）。
     * 兼容三种录入方式：
     *  1. [Credential.SessionCredential.cookies] 里匹配到 session 名的项
     *  2. [Credential.SessionCredential.authCookie]（用户在设置页粘贴的那一整串）
     *  3. [Credential.SessionCredential.token]（早期字段）
     *
     * 用户也可能把整串 `name=value; name2=value2` 粘进 authCookie，这里顺带解析出主 cookie。
     */
    private fun resolveSessionCookie(credential: Credential.SessionCredential): String? {
        // 先尝试从整串 cookie 里摘出 console_session
        val raw = credential.authCookie
        if (!raw.isNullOrBlank() && raw.contains('=')) {
            val entry = raw.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("console_session=") || it.startsWith("__Host-console_session=") }
            if (entry != null) {
                return decodeToken(entry.substringAfter('='))
            }
        }

        val candidates = listOfNotNull(
            credential.cookies.firstOrNull { c ->
                c.value.isNotBlank() && (
                    c.name.contains("console_session", ignoreCase = true) ||
                    c.name.contains("session", ignoreCase = true)
                )
            }?.value,
            credential.authCookie?.takeIf { it.isNotBlank() && !it.contains('=') },
            credential.token?.takeIf { it.isNotBlank() }
        )
        return candidates.firstOrNull { it.length >= 32 } ?: candidates.firstOrNull()
    }

    /** 组装 Cookie 头：主会话 cookie + 凭据中其余辅助 cookie（如 st_* 会话追踪 cookie） */
    private fun buildCookieHeader(credential: Credential.SessionCredential, session: String): String {
        val extras = credential.cookies
            .filter { it.value.isNotBlank() && it.value != session }
            .joinToString("; ") { "${it.name}=${it.value}" }
        val main = "$SESSION_COOKIE_NAME=$session"
        return if (extras.isBlank()) main else "$main; $extras"
    }

    /**
     * 从 models.dev/api.json 获取 OpenCode Go 可用模型列表（provider key = "opencode-go"）。
     * 该 API 不需要认证。
     */
    suspend fun fetchModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(MODELS_API)
                .header("Accept", "application/json")
                .header("User-Agent", "rainy-token/0.1")
                .get().build()
            val models = okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    DebugLog.e(TAG, "fetchModels: HTTP ${resp.code}")
                    return@withContext Result.failure(RepositoryError.ServerError(resp.code))
                }
                val root = json.parseToJsonElement(resp.body?.string() ?: throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.EMPTY_BODY, "响应体为空")) as? JsonObject
                    ?: throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.NOT_JSON_OBJECT, "响应根节点不是 JSON 对象")
                val provider = root["opencode-go"] as? JsonObject
                val modelsObj = provider?.get("models") as? JsonObject
                modelsObj?.keys?.toList()?.sorted()
                    ?: throw RepositoryError.ParseError(RepositoryError.ParseErrorReason.NO_MODELS, "未找到 OpenCode Go 模型列表")
            }
            if (models.isEmpty()) {
                return@withContext Result.failure(RepositoryError.ParseError(RepositoryError.ParseErrorReason.MODELS_EMPTY, "模型列表为空"))
            }
            DebugLog.i(TAG, "fetchModels: 获取到 ${models.size} 个模型")
            Result.success(models)
        } catch (e: IOException) {
            DebugLog.e(TAG, "fetchModels 网络异常: ${e.message}")
            Result.failure(RepositoryError.Network(e))
        } catch (e: RepositoryError) {
            Result.failure(e)
        } catch (e: Throwable) {
            DebugLog.e(TAG, "fetchModels 异常: ${e::class.simpleName}: ${e.message}")
            Result.failure(RepositoryError.Unknown(e))
        }
    }

    /**
     * 一键激活用量：用 API Key 向 OpenCode chat completions API 发送简短请求。
     * @param model 用户选择的模型 slug
     * @return Result.success(响应摘要文本) — 供 UI 展示
     */
    suspend fun triggerUsage(model: String): Result<TriggerSummary> = withContext(Dispatchers.IO) {
        val credential = credentialRepository.get(ServiceType.OPENCODE_GO)
            ?: return@withContext Result.failure(RepositoryError.InvalidCredential("未找到 OpenCode Go 凭据"))
        if (credential !is Credential.SessionCredential)
            return@withContext Result.failure(RepositoryError.InvalidCredential("凭据类型不匹配"))

        val apiKey = credential.apiKey
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(RepositoryError.InvalidCredential("未配置 API Key，请在设置中填写"))
        }

        DebugLog.i(TAG, "triggerUsage: model=$model")

        val requestBody = """{"model":"$model","messages":[{"role":"user","content":"hello"}],"max_tokens":50}"""
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(CHAT_API)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", "rainy-token/0.1")
            .post(requestBody).build()

        try {
            okHttpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                DebugLog.i(TAG, "triggerUsage: HTTP ${resp.code}, body=${bodyStr.take(500)}")
                if (!resp.isSuccessful) {
                    return@withContext Result.failure<TriggerSummary>(
                        TriggerError("HTTP ${resp.code}", bodyStr.ifBlank { "" })
                    )
                }
                DebugLog.i(TAG, "triggerUsage: 请求成功，模型=$model")
                Result.success(parseChatResponse(bodyStr, model))
            }
        } catch (e: IOException) {
            DebugLog.e(TAG, "triggerUsage 网络异常: ${e.message}")
            Result.failure(RepositoryError.Network(e))
        } catch (e: Throwable) {
            DebugLog.e(TAG, "triggerUsage 异常: ${e::class.simpleName}: ${e.message}")
            Result.failure(RepositoryError.Unknown(e))
        }
    }

    companion object {
        private const val TAG = "OCGO"
        private const val WEB_ORIGIN = "https://opencode.ai"

        /** 新版 Console 的 Go 配额端点（tRPC，JSON） */
        private const val GO_STATUS_API = "$WEB_ORIGIN/console/api/go/status"
        private const val MODELS_API = "https://models.dev/api.json"
        private const val CHAT_API = "https://opencode.ai/zen/go/v1/chat/completions"

        /** 生产环境会话 cookie 名（开发环境为 console_session） */
        private const val SESSION_COOKIE_NAME = "__Host-console_session"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    /** 单个配额窗口（microCents 原始值 + 换算后的 USD） */
    internal data class GoWindow(
        val startsAt: Long?,
        val resetsAt: Long?,
        val usedMicroCents: Long?,
        val limitMicroCents: Long?
    ) {
        val usedUsd: Double? = usedMicroCents?.let { it / MICRO_CENTS_PER_USD }
        val limitUsd: Double? = limitMicroCents?.let { it / MICRO_CENTS_PER_USD }

        /** 已用百分比；缺 limit 或 limit<=0 时返回 0 */
        fun usagePercent(): Double {
            val u = usedUsd ?: return 0.0
            val l = limitUsd ?: return 0.0
            if (l <= 0.0) return 0.0
            return (u / l * 100.0).coerceIn(0.0, 100.0)
        }

        /** 距下次重置的秒数；缺 resetsAt 时返回 0 */
        fun resetInSec(): Long {
            val r = resetsAt ?: return 0L
            return maxOf(0L, (r - System.currentTimeMillis()) / 1000)
        }
    }

    /**
     * 浏览器 DevTools 复制来的 cookie 值常带 URL 编码（如 `%2F` `%2B` `%3D`）。
     * 原样发送时部分服务端会解不出 token，这里做一次解码，确保拿到原始值。
     */
    private fun decodeToken(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains('%')) return trimmed
        return try {
            java.net.URLDecoder.decode(trimmed, "UTF-8")
        } catch (_: Exception) {
            trimmed
        }
    }
}

/** microCents → USD：1 USD = 100_000_000 microCents */
private const val MICRO_CENTS_PER_USD = 100_000_000.0

/**
 * 解析一个配额窗口对象 `{startsAt?, resetsAt, limitMicroCents, usedMicroCents}`。
 *
 * microCents 字段在响应里可能是**字符串**（BigInt 序列化，如 `"1200000000"`）
 * 也可能是数字，两种都要认；三个字段全缺时返回 null，表示该窗口不可用。
 */
internal fun JsonObject.toGoWindow(): OpenCodeGoRepository.GoWindow? {
    val resetsAt = this["resetsAt"]?.asLongOrNull()
    val used = this["usedMicroCents"]?.asLongOrNull()
    val limit = this["limitMicroCents"]?.asLongOrNull()
    if (resetsAt == null && used == null && limit == null) return null
    return OpenCodeGoRepository.GoWindow(
        startsAt = this["startsAt"]?.asLongOrNull(),
        resetsAt = resetsAt,
        usedMicroCents = used,
        limitMicroCents = limit
    )
}

/** JsonElement → Long，兼容字符串与数字两种表示 */
internal fun JsonElement.asLongOrNull(): Long? = when (this) {
    is JsonPrimitive -> content.trim().toLongOrNull()
    else -> null
}

/** JsonElement → Boolean，兼容字符串与数字两种表示 */
internal fun JsonElement.asBoolean(): Boolean? = when (this) {
    is JsonPrimitive -> when (content.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
    else -> null
}

/**
 * 解析 OpenAI 兼容的 chat completions 响应，提取回复文本和用量统计。
 */
internal fun parseChatResponse(responseBody: String, model: String): TriggerSummary {
    val json = Json { ignoreUnknownKeys = true }
    return try {
        val root = json.parseToJsonElement(responseBody) as? JsonObject
        val choices = root?.get("choices") as? kotlinx.serialization.json.JsonArray
        val firstChoice = choices?.firstOrNull() as? JsonObject
        val message = firstChoice?.get("message") as? JsonObject
        val content = (message?.get("content") as? JsonPrimitive)?.contentOrNull
        val usage = root?.get("usage") as? JsonObject
        val promptTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.contentOrNull
        val completionTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.contentOrNull
        val totalTokens = (usage?.get("total_tokens") as? JsonPrimitive)?.contentOrNull

        TriggerSummary(
            model = model,
            reply = content,
            inputTokens = promptTokens,
            outputTokens = completionTokens,
            totalTokens = totalTokens
        )
    } catch (_: Exception) {
        TriggerSummary(model = model, reply = null, parseFailed = true)
    }
}