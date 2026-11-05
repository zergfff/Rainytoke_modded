package com.rainy.token.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * [QWeatherJwt] 的纯 JVM 单测（不碰 Android，不联网）。
 *
 * 重点验证「签名输入 == 服务端会验的那两段」：用新生成的 Ed25519 密钥对签一次，
 * 再用公钥验签，通过就说明 header.payload 拼接、Base64URL（无 padding）、
 * 64 字节裸签名这三处都没错——和风 401 里最常见的就是这三处。
 */
class QWeatherJwtTest {

    private fun newCredential(): Pair<QWeatherCredential, PublicKey> {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(keyPair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----"
        val credential = QWeatherCredential(
            apiHost = "test.example.qweatherapi.com",
            credentialId = "CREDID123",
            developerId = "QTEST12345",
            projectId = "PROJ123456",
            privateKey = pem
        )
        return credential to keyPair.public
    }

    private fun decode(part: String): ByteArray = Base64.getUrlDecoder().decode(part)

    @Test
    fun `token has three parts and verifies with the public key`() {
        QWeatherJwt.resetCache()
        val (credential, publicKey) = newCredential()

        val token = QWeatherJwt.token(credential, nowSeconds = 1_800_000_000L)
        val parts = token.split(".")
        assertEquals("JWT 必须是三段", 3, parts.size)

        val signature = decode(parts[2])
        assertEquals("Ed25519 裸签名必须是 64 字节", 64, signature.size)

        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
        assertTrue("签名必须能用对应公钥验证通过", verifier.verify(signature))
    }

    @Test
    fun `header carries kid and payload carries iss sub and 15 minute ttl`() {
        QWeatherJwt.resetCache()
        val (credential, _) = newCredential()
        val now = 1_800_000_000L

        val token = QWeatherJwt.token(credential, nowSeconds = now)
        val parts = token.split(".")
        val header = String(decode(parts[0]), Charsets.UTF_8)
        val payload = String(decode(parts[1]), Charsets.UTF_8)

        assertTrue("header 必须带 EdDSA", header.contains("\"alg\":\"EdDSA\""))
        assertTrue("header 必须带 kid", header.contains("\"kid\":\"CREDID123\""))
        assertTrue("payload 必须带 iss", payload.contains("\"iss\":\"QTEST12345\""))
        assertTrue("payload 必须带 sub", payload.contains("\"sub\":\"PROJ123456\""))
        // iat 回拨 30 秒，exp = iat + 900
        assertTrue("iat 必须回拨 30 秒", payload.contains("\"iat\":" + (now - 30)))
        assertTrue("exp 必须是 iat + 900", payload.contains("\"exp\":" + (now - 30 + QWeatherJwt.TTL_SECONDS)))
        // Base64URL 不能出现 padding 或 + /
        assertEquals("base64url 不能有 padding", -1, parts[0].indexOf('='))
        assertEquals("base64url 不能有 +", -1, parts[0].indexOf('+'))
    }

    @Test
    fun `token is cached until it is about to expire`() {
        QWeatherJwt.resetCache()
        val (credential, _) = newCredential()
        val now = 1_800_000_000L

        val first = QWeatherJwt.token(credential, nowSeconds = now)
        val second = QWeatherJwt.token(credential, nowSeconds = now + 60)
        assertEquals("TTL 内必须复用同一个 token", first, second)

        val renewed = QWeatherJwt.token(
            credential,
            nowSeconds = now + QWeatherJwt.TTL_SECONDS - 30
        )
        assertNotEquals("临近过期必须重签", first, renewed)
    }

    @Test
    fun `pem header and whitespace are tolerated`() {
        QWeatherJwt.resetCache()
        val (credential, _) = newCredential()
        val compact = credential.privateKey
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")

        val withPem = QWeatherJwt.token(credential, nowSeconds = 1_800_000_000L)
        QWeatherJwt.resetCache()
        val bare = QWeatherJwt.token(
            credential.copy(privateKey = "  $compact  "),
            nowSeconds = 1_800_000_000L
        )
        assertEquals("裸 base64 与整段 PEM 签出的 token 必须一致", withPem, bare)
    }
}
