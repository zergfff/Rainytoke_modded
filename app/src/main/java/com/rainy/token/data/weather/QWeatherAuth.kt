package com.rainy.token.data.weather

import com.rainy.token.data.local.SecureStorage
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * 和风天气（QWeather）凭证。
 *
 * 和风天气自 2026 年起强制 JWT（EdDSA / Ed25519）鉴权，API KEY 于 2027-01 停用。
 * 控制台里各处值和 JWT 的对应关系：
 *
 * | 控制台字段 | 用途 |
 * |---|---|
 * | 凭据 ID（如 CMPKVETKD2） | JWT header 的 `kid` |
 * | 开发者 ID（Q + 9 位） | JWT payload 的 `iss` |
 * | 项目 ID（如 3ATN45BUJJ） | JWT payload 的 `sub` |
 * | 私钥（PKCS#8 PEM） | 本机签名，只加密存本机，绝不上传 |
 * | API Host | 请求域名，不属于 token |
 *
 * 老用户只填过「API Key」的，继续走 `?key=` 旧通道（2027-01 前有效）。
 */
@Serializable
data class QWeatherCredential(
    val apiHost: String = "",
    val credentialId: String = "",
    val developerId: String = "",
    val projectId: String = "",
    val privateKey: String = "",
    val apiKey: String = ""
) {

    /** JWT 三件套 + 私钥齐全 */
    val usesJwt: Boolean
        get() = credentialId.isNotBlank() && developerId.isNotBlank() &&
            projectId.isNotBlank() && privateKey.isNotBlank()

    /** 手工粘贴的现成 JWT（三段 eyJ…）：设备不支持 Ed25519 时的兜底 */
    val pastedToken: String
        get() = if (usesJwt) "" else apiKey.trim().takeIf { looksLikeJwt(it) }.orEmpty()

    /** 退回旧版 API KEY（`?key=`，2027-01 停用） */
    val usesLegacyKey: Boolean
        get() = !usesJwt && pastedToken.isEmpty() && apiKey.isNotBlank()

    val hasAuth: Boolean
        get() = usesJwt || pastedToken.isNotEmpty() || usesLegacyKey

    /** 控制台里可能带 `https://`，这里统一成裸域名 */
    fun normalizedHost(): String = apiHost.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

    companion object {
        /** SecureStorage 中存放整个凭证（含私钥）的 key */
        const val SECURE_KEY = "qweather_credential"

        /** 三段式且第一段以 eyJ 开头，即一个 JWT */
        fun looksLikeJwt(value: String): Boolean {
            val parts = value.trim().split('.')
            return parts.size == 3 && parts.all { it.isNotBlank() } &&
                parts[0].startsWith("eyJ")
        }
    }
}

/**
 * 和风天气 JWT 签发（EdDSA / Ed25519）。
 *
 * 只依赖 JDK 自带的 Ed25519（API 33+ 平台内置），不用 BouncyCastle，
 * 也因此能在纯 JVM 单测里跑。
 *
 * token 结构：`base64url(header).base64url(payload).base64url(signature)`，
 * 签名是对前两段的 ASCII 字节做 Ed25519（64 字节裸签名，不是 DER）。
 * iat 回拨 30 秒防时钟漂移；TTL 900 秒（官方上限 86400），
 * 距过期不足 120 秒时自动重签。
 */
object QWeatherJwt {

    const val TTL_SECONDS = 900L
    private const val CLOCK_SKEW_SECONDS = 30L
    private const val REFRESH_AHEAD_SECONDS = 120L

    /** 不同 ROM 暴露 Ed25519 的名字不同，按顺序探测 */
    private val SIGN_ALGORITHMS = listOf("Ed25519", "EdDSA", "1.3.101.112")
    private val KEY_ALGORITHMS = listOf("Ed25519", "EdDSA", "1.3.101.112")
    private val PROVIDERS = listOf<String?>(null, "Conscrypt", "BC")

    private var cachedFingerprint = ""
    private var cachedToken = ""
    private var cachedExpireAt = 0L

    @Synchronized
    fun token(
        credential: QWeatherCredential,
        nowSeconds: Long = System.currentTimeMillis() / 1000
    ): String {
        val fingerprint = fingerprint(credential)
        if (cachedToken.isNotEmpty() && fingerprint == cachedFingerprint &&
            nowSeconds < cachedExpireAt - REFRESH_AHEAD_SECONDS
        ) {
            return cachedToken
        }

        val issuedAt = nowSeconds - CLOCK_SKEW_SECONDS
        val expireAt = issuedAt + TTL_SECONDS
        val header = "{\"alg\":\"EdDSA\",\"kid\":\"" + escape(credential.credentialId) + "\"}"
        val payload = "{\"iss\":\"" + escape(credential.developerId) + "\"" +
            ",\"sub\":\"" + escape(credential.projectId) + "\"" +
            ",\"iat\":" + issuedAt + ",\"exp\":" + expireAt + "}"

        val signingInput = b64url(header.toByteArray(Charsets.UTF_8)) +
            "." + b64url(payload.toByteArray(Charsets.UTF_8))
        val signature = sign(credential.privateKey, signingInput.toByteArray(Charsets.US_ASCII))
        val token = signingInput + "." + b64url(signature)

        cachedFingerprint = fingerprint
        cachedToken = token
        cachedExpireAt = expireAt
        return token
    }

    /** 清缓存（换凭据/退出时用，主要给测试） */
    @Synchronized
    fun resetCache() {
        cachedFingerprint = ""
        cachedToken = ""
        cachedExpireAt = 0L
    }

    private fun sign(privateKeyPem: String, data: ByteArray): ByteArray {
        val der = decodePkcs8(privateKeyPem)
        val privateKey = newKeyFactory().generatePrivate(PKCS8EncodedKeySpec(der))
        val signer = newSigner()
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    /**
     * 各家 ROM 的 JCA provider 对 Ed25519 的暴露名字不一样（Ed25519 / EdDSA / OID），
     * 有的只在 Conscrypt provider 里注册。逐个试，全失败才报错——错误信息带全部
     * 尝试结果，便于判断是不是这台机器根本没带 EdDSA。
     */
    private fun newKeyFactory(): KeyFactory =
        firstWorking(KEY_ALGORITHMS) { name, provider ->
            if (provider == null) {
                KeyFactory.getInstance(name)
            } else {
                KeyFactory.getInstance(name, provider)
            }
        }

    private fun newSigner(): Signature =
        firstWorking(SIGN_ALGORITHMS) { name, provider ->
            if (provider == null) {
                Signature.getInstance(name)
            } else {
                Signature.getInstance(name, provider)
            }
        }

    private fun <T> firstWorking(algorithms: List<String>, factory: (String, String?) -> T): T {
        val errors = StringBuilder()
        for (name in algorithms) {
            for (provider in PROVIDERS) {
                try {
                    return factory(name, provider)
                } catch (t: Throwable) {
                    errors.append(name)
                    if (provider != null) errors.append('@').append(provider)
                    errors.append('=').append(t.javaClass.simpleName).append(' ')
                }
            }
        }
        throw IllegalStateException("本机 JCA 不支持 Ed25519 签名：" + errors.toString().trim())
    }

    /** 兼容整段 PEM（带 BEGIN/END 行）与裸 base64；空白全部剔除 */
    internal fun decodePkcs8(pem: String): ByteArray {
        val body = pem
            .replace(Regex("-----[^-]*-----"), "")
            .replace(Regex("\\s+"), "")
        require(body.isNotEmpty()) { "私钥内容为空" }
        return Base64.getDecoder().decode(body)
    }

    internal fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun fingerprint(credential: QWeatherCredential): String =
        credential.credentialId + "|" + credential.developerId + "|" +
            credential.projectId + "|" + credential.privateKey.hashCode()
}

// ─── 加密存储读写（SecureStorage 是通用 KV，凭证整体序列化成一个 JSON） ───

suspend fun SecureStorage.loadQWeatherCredential(): QWeatherCredential? =
    get(QWeatherCredential.SECURE_KEY, QWeatherCredential.serializer())

suspend fun SecureStorage.saveQWeatherCredential(credential: QWeatherCredential) {
    put(QWeatherCredential.SECURE_KEY, credential, QWeatherCredential.serializer())
}

/**
 * 读凭证，顺便把「旧版单独存的 API KEY」迁移进新结构。
 *
 * @param legacyHost DataStore 里的 qweatherHost，老用户只填过 Host
 */
suspend fun SecureStorage.loadQWeatherCredentialWithMigration(
    legacyHost: String = ""
): QWeatherCredential {
    loadQWeatherCredential()?.let { saved ->
        return saved.copy(apiHost = saved.apiHost.ifBlank { legacyHost })
    }
    val legacyKey = getQWeatherKey().orEmpty()
    return QWeatherCredential(apiHost = legacyHost, apiKey = legacyKey)
}
