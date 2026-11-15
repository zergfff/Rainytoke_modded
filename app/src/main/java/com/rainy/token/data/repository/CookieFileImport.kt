package com.rainy.token.data.repository

/**
 * 解析浏览器导出的 `cookies.txt`（Netscape 格式）。
 *
 * Firefox / IceRaven 这类浏览器把 Cookie 存在私有目录的 SQLite 里，**没有 root 权限
 * 其他 APP 读不到**，所以走「浏览器里装导出扩展 → 导出 cookies.txt → 本 APP 导入」
 * 这条不需要 root 的路。
 *
 * 行格式（TAB 分隔 7 列）：
 * ```
 * .open-design.ai	TRUE	/	TRUE	1790000000	__Secure-better-auth.session_token	abc123
 * #HttpOnly_.amr-api.open-design.ai	TRUE	/	TRUE	...	session_token	xyz
 * ```
 *
 * 纯 Kotlin、无 Android 依赖，可直接跑单元测试。
 */
object CookieFileImport {

    data class ImportedCookie(
        val domain: String,
        val name: String,
        val value: String
    )

    /**
     * @param text cookies.txt 全文
     * @param domainSuffix 只保留 domain 里包含该串的条目，如 "open-design.ai"
     */
    fun parseNetscape(text: String, domainSuffix: String): List<ImportedCookie> {
        // 同名同域只保留最后一条（后者覆盖前者）
        val byKey = LinkedHashMap<Pair<String, String>, ImportedCookie>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // 普通注释行跳过；#HttpOnly_ 前缀是有效数据行，不能当注释
            if (line.startsWith("#") && !line.startsWith("#HttpOnly_")) continue

            val fields = line.split('\t')
            if (fields.size < 7) continue

            val domain = fields[0].removePrefix("#HttpOnly_").trim()
            if (domain.isEmpty()) continue
            if (!domain.contains(domainSuffix, ignoreCase = true)) continue

            val name = fields[5].trim()
            val value = fields[6].trim()
            if (name.isEmpty()) continue

            byKey[name to domain] = ImportedCookie(domain = domain, name = name, value = value)
        }
        return byKey.values.toList()
    }
}
