package com.rainy.token.data.repository

import com.rainy.token.data.local.UsageRecord
import com.rainy.token.domain.model.Credential
import com.rainy.token.domain.model.CookieEntry
import com.rainy.token.domain.service.ServiceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.inject.Singleton

/**
 * CommandCode Go 用量记录仓库。
 *
 * 调 JSON API 分页抓取 usage 记录：
 *   GET https://api.commandcode.ai/internal/usage?limit=100
 *   GET https://api.commandcode.ai/internal/usage?limit=100&cursor=<服务端返回的 nextCursor>
 *
 * 2026-09 接口变更（旧版代码仍按老字段解析，会静默解析出全 0 的记录）：
 *  - 单条记录不再返回 `creditsTotal` / `tokensTotal`，费用改在 `meta.totalCost`（**单位 USD**）
 *  - 响应顶层新增 `nextCursor`（不透明字符串），翻页必须用服务端给的游标，
 *    自己用 `{createdAt,id}` 拼 base64 的做法已失效
 *  - `tokensIn` / `tokensOut` 仍是字符串数字
 *
 * cursor=null 为最新页。返回 (记录列表, 下一页游标)；游标为 null 表示已到底。
 */
@Singleton
class CommandCodeUsageRepository(
    private val okHttpClient: OkHttpClient,
    private val credentialRepository: CredentialRepository
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiBase = "https://api.commandcode.ai"

    companion object {
        const val PAGE_SIZE = 100
        /** cost / DENOM = USD */
        const val COST_DENOM = 100_000_000L
        /** CCGO 用量数据在 UsageCache 中的 workspaceId 区分键 */
        const val CCGO_WORKSPACE_ID = "commandcode"

        /** CommandCode 会话 cookie 名（better-auth） */
        private const val SESSION_COOKIE_NAME = "__Secure-commandcode_prod_.session_token"
    }

    /**
     * 组装 Cookie 头。
     *
     * CommandCode 2026 起改用 better-auth 会话鉴权，cookie 名为
     * `__Secure-commandcode_prod_.session_token`。用户从 DevTools 复制出来的值
     * 常带 URL 编码（`%2F` `%2B` `%3D`），这里统一解码一次再发。
     */
    private suspend fun getCookieHeader(): String {
        val c = credentialRepository.get(ServiceType.COMMANDCODE_GO)
            ?: throw RepositoryError.InvalidCredential("未找到 CommandCode 凭据")
        if (c !is Credential.SessionCredential) {
            throw RepositoryError.InvalidCredential("凭据类型不匹配")
        }

        val entries = c.cookies.filter { it.value.isNotBlank() }
        if (entries.isNotEmpty()) {
            return entries.joinToString("; ") { "${it.name}=${decodeToken(it.value)}" }
        }

        // 旧版只存了 authCookie 的场景：补一个 session_token 名再发
        val raw = c.authCookie?.takeIf { it.isNotBlank() } ?: c.token?.takeIf { it.isNotBlank() }
            ?: throw RepositoryError.InvalidCredential(
                "缺少会话 Cookie。请在浏览器登录 commandcode.ai 并从 DevTools → Application → Cookies " +
                "复制 __Secure-commandcode_prod_.session_token 的值"
            )
        return "$SESSION_COOKIE_NAME=${decodeToken(raw)}"
    }

    /** DevTools 复制的 cookie 值可能带 URL 编码，解码一次确保服务端能解出 token */
    private fun decodeToken(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains('%')) return trimmed
        return try {
            URLDecoder.decode(trimmed, "UTF-8")
        } catch (_: Exception) {
            trimmed
        }
    }

    /**
     * 获取指定游标页的用量记录。
     * cursor=null 为最新页。
     * 返回 (记录列表, 下一页游标)。如果返回的列表长度 < PAGE_SIZE，表示到底。
     */
    suspend fun fetchPage(cursor: String?): Result<Pair<List<UsageRecord>, String?>> =
        withContext(Dispatchers.IO) {
            val cookieHeader = try {
                getCookieHeader()
            } catch (e: RepositoryError) {
                return@withContext Result.failure(e)
            }

            // 用 OkHttp 的 HttpUrl 构造，避免游标里的特殊字符被破坏
            val url = buildString {
                append("$apiBase/internal/usage")
            }.toHttpUrl().newBuilder()
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .apply { if (cursor != null) addQueryParameter("cursor", cursor) }
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookieHeader)
                .header("Accept", "application/json")
                .header("Origin", "https://commandcode.ai")
                .header("Referer", "https://commandcode.ai/settings/usage")
                .get()
                .build()

            val response = try {
                okHttpClient.newCall(request).execute()
            } catch (e: IOException) {
                return@withContext Result.failure(RepositoryError.Network(e))
            }

            response.use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403) {
                        val detail = if (body != null && body.length < 200) "：$body" else ""
                        return@withContext Result.failure(RepositoryError.InvalidCredential(
                            "HTTP ${resp.code}$detail"
                        ))
                    }
                    return@withContext Result.failure(RepositoryError.ServerError(resp.code))
                }

                if (body == null) return@withContext Result.failure(
                    RepositoryError.ParseError(RepositoryError.ParseErrorReason.EMPTY_BODY, "响应体为空")
                )

                val records = parseUsageResponse(body)
                Result.success(records)
            }
        }

    /**
     * 解析 JSON 响应。
     *
     * 翻页游标优先用服务端返回的 `nextCursor`（不透明字符串）；
     * 缺失时回退到按 `records.size >= PAGE_SIZE` 判断到底。
     */
    private fun parseUsageResponse(body: String): Pair<List<UsageRecord>, String?> {
        val root = json.parseToJsonElement(body).jsonObject
        val usages = root["usages"]?.jsonArray ?: return emptyList<UsageRecord>() to null

        val records = usages.mapNotNull { elem ->
            val obj = elem.jsonObject
            parseUsageObject(obj)
        }

        val nextCursor = root["nextCursor"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: if (records.size >= PAGE_SIZE) {
                records.lastOrNull()?.let { encodeCursor(it.id, it.timeCreated) }
            } else {
                null
            }

        return records to nextCursor
    }

    private fun parseUsageObject(obj: JsonObject): UsageRecord? {
        val id = obj["id"]?.jsonPrimitive?.content ?: return null
        val createdAt = obj["createdAt"]?.jsonPrimitive?.content ?: return null
        val timeCreated = parseIsoDate(createdAt) ?: return null

        val tokensIn = obj["tokensIn"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val tokensOut = obj["tokensOut"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

        // 2026-09：creditsTotal 已下线，费用改由 meta.totalCost 给出（单位 USD）
        val meta = obj["meta"]?.jsonObject
        val totalCostUsd = meta?.get("totalCost")?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: obj["creditsTotal"]?.jsonPrimitive?.content?.toDoubleOrNull()   // 兼容旧字段
            ?: 0.0
        val cost = (totalCostUsd * COST_DENOM).toLong()

        val model = meta?.get("model")?.jsonPrimitive?.content ?: ""
        val provider = meta?.get("provider")?.jsonPrimitive?.content ?: ""
        val cacheReadInputTokens = meta?.get("cacheReadInputTokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

        // CCGO 的 tokensIn 是总输入（缓存命中 + 未命中），按 OCGO 惯例拆分为 inputTokens（未命中）和 cacheReadTokens（命中）
        val inputMissTokens = (tokensIn - cacheReadInputTokens).coerceAtLeast(0)

        return UsageRecord(
            id = id,
            workspaceId = CCGO_WORKSPACE_ID,
            timeCreated = timeCreated,
            timeUpdated = timeCreated,
            model = model,
            provider = provider,
            inputTokens = inputMissTokens,
            outputTokens = tokensOut,
            reasoningTokens = 0L,
            cacheReadTokens = cacheReadInputTokens,
            cacheWrite5mTokens = 0L,
            cacheWrite1hTokens = 0L,
            cost = cost,
            keyId = "",
            sessionId = "",
            enrichmentPlan = ""
        )
    }

    private fun parseIsoDate(iso: String): Long? {
        // 处理末尾 Z 和时区偏移
        val normalized = iso
            .replace("Z", "X")
            .replace(Regex("""[+-]\d{2}:\d{2}$"""), "X")
        return try {
            // SimpleDateFormat 非线程安全，每次创建新实例
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'X'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(normalized)?.time
                ?: run {
                    val sdf2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'X'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    sdf2.parse(normalized)?.time
                }
        } catch (_: Exception) { null }
    }

    /** 从记录信息编码为 base64 cursor */
    private fun encodeCursor(id: String, timeCreated: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val createdAt = sdf.format(java.util.Date(timeCreated))
        val cursorJson = """{"createdAt":"$createdAt","id":"$id"}"""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cursorJson.toByteArray())
    }
}