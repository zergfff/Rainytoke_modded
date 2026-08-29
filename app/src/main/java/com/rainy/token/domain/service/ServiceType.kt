package com.rainy.token.domain.service

import kotlinx.serialization.Serializable

/**
 * 服务类型枚举。集中管理当前支持的服务。
 *
 * 实际服务配置（登录 URL、API 端点、CSS 选择器、降级条件等）见
 * [ServiceConfigProvider]，本枚举只保留身份标识和展示信息。
 */
@Serializable
enum class ServiceType(
    val displayName: String,
    /** 用于在 SecureStorage 中索引的稳定 key（与枚举名解耦，避免重命名丢数据） */
    val storageKey: String
) {
    OPENCODE_GO("OpenCode Go", "opencode_go"),
    COMMANDCODE_GO("CommandCode GOAT", "commandcode_go"),
    DEEPSEEK("DeepSeek", "deepseek"),
    CODEX("Codex / ChatGPT", "codex"),
    OLLAMA("Ollama", "ollama"),

    // ─── 新增：Coding Plan 余量监测 ───
    /** 智谱 GLM Coding Plan（z.ai 国际 / bigmodel.cn 国内） */
    ZAI_GLM("GLM Coding Plan", "zai_glm"),

    /** Moonshot Kimi Code（API Key 模式） */
    KIMI("Kimi Code", "kimi"),

    /** 小米 MiMo Token Plan（浏览器 Cookie 模式） */
    MIMO("Xiaomi MiMo", "mimo"),

    /** MiniMax Coding Plan（API Token 优先，Cookie 兜底） */
    MINIMAX("MiniMax", "minimax"),

    /** 阿里云百炼 Coding Plan（API Key 模式，POST /data/api.json） */
    ALIBABA("阿里云百炼", "alibaba");

    companion object {
        fun fromStorageKey(key: String): ServiceType? =
            entries.firstOrNull { it.storageKey == key }
    }
}