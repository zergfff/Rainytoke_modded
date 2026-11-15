package com.rainy.token.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CookieFileImport] 的纯 JVM 单测：浏览器导出的 cookies.txt 千奇百怪，
 * 这里锁住「只留目标域 / 处理 #HttpOnly_ / 同名同域后者覆盖 / 垃圾行忽略」四条。
 *
 * 注意：Netscape 格式用 TAB 分隔，这里用普通字符串（非 raw string）才能让 	 生效。
 */
class CookieFileImportTest {

    private val sample: String = listOf(
        "# Netscape HTTP Cookie File",
        "# https://curl.se/docs/http-cookies.html",
        ".open-design.ai	TRUE	/	TRUE	1790000000	session_token	aaa111",
        "#HttpOnly_.amr-api.open-design.ai	TRUE	/	TRUE	1790000000	better-auth.session_token	bbb222",
        ".example.com	TRUE	/	FALSE	1790000000	other	cookie",
        "# a normal comment line",
        "broken line without tabs",
        ".open-design.ai	TRUE	/	TRUE	1790000500	session_token	ccc333"
    ).joinToString("
")

    @Test
    fun `keeps only cookies of the target domain`() {
        val cookies = CookieFileImport.parseNetscape(sample, "open-design.ai")
        val names = cookies.map { it.name }
        assertEquals(2, cookies.size)
        assertTrue("better-auth #HttpOnly_ 行必须被识别", names.contains("better-auth.session_token"))
        assertTrue("其他域必须被过滤", cookies.none { it.domain.contains("example.com") })
    }

    @Test
    fun `later duplicate wins and HttpOnly prefix is stripped`() {
        val cookies = CookieFileImport.parseNetscape(sample, "open-design.ai")
        val session = cookies.first { it.name == "session_token" }
        assertEquals("同名同域应取最后一条", "ccc333", session.value)
        assertEquals("domain 不应带 #HttpOnly_ 前缀", ".open-design.ai", session.domain)

        val httpOnly = cookies.first { it.name == "better-auth.session_token" }
        assertTrue(httpOnly.domain.startsWith(".amr-api"))
    }

    @Test
    fun `garbage input yields empty list`() {
        assertEquals(0, CookieFileImport.parseNetscape("", "open-design.ai").size)
        assertEquals(0, CookieFileImport.parseNetscape("not	a	cookie", "open-design.ai").size)
        assertEquals(0, CookieFileImport.parseNetscape(sample, "nope.example").size)
    }
}
