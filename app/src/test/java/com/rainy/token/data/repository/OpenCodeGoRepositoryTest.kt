package com.rainy.token.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OpenCodeGoRepository] window parsing.
 *
 * 2026-09 OpenCode 改版后不再有 SSR hydration 数据，改用 `GET /console/api/go/status`
 * 返回的 JSON（金额单位 microCents）。这里用与真实响应同构的样本做纯 JVM 解析测试。
 */
class OpenCodeGoRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 用真实响应结构构造一个 `{meters:{...}}` 片段。
     *
     * @param fiveHourUsedUsd 等 单位 USD，内部换算成 microCents（字符串形式）
     */
    private fun buildMetersJson(
        fiveHourUsedUsd: Double? = 1.2,
        fiveHourLimitUsd: Double? = 12.0,
        weekUsedUsd: Double? = 3.0,
        weekLimitUsd: Double? = 30.0,
        monthUsedUsd: Double? = 6.0,
        monthLimitUsd: Double? = 60.0,
        resetsAt: Long = 1790945494815L
    ): String {
        fun window(used: Double?, limit: Double?, withStarts: Boolean): String {
            // 键名必须保留，只让值变成 null（模拟服务端字段存在但无值）
            val usedPart = used?.let { "\"usedMicroCents\":\"${(it * 100_000_000).toLong()}\"" }
                ?: "\"usedMicroCents\":null"
            val limitPart = limit?.let { "\"limitMicroCents\":\"${(it * 100_000_000).toLong()}\"" }
                ?: "\"limitMicroCents\":null"
            val starts = if (withStarts) "\"startsAt\":1790675391700," else ""
            return """{$starts"resetsAt":$resetsAt,$usedPart,$limitPart}"""
        }
        return """{"meters":{
            "fiveHour":${window(fiveHourUsedUsd, fiveHourLimitUsd, true)},
            "week":${window(weekUsedUsd, weekLimitUsd, true)},
            "month":${window(monthUsedUsd, monthLimitUsd, false)}
        }}"""
    }

    private fun parseWindows(metersJson: String): Map<String, OpenCodeGoRepository.GoWindow> {
        val meters = json.parseToJsonElement(metersJson).jsonObject["meters"]!!.jsonObject
        val out = mutableMapOf<String, OpenCodeGoRepository.GoWindow>()
        meters["fiveHour"]?.jsonObject?.toGoWindow()?.let { out["rolling"] = it }
        meters["week"]?.jsonObject?.toGoWindow()?.let { out["weekly"] = it }
        meters["month"]?.jsonObject?.toGoWindow()?.let { out["monthly"] = it }
        return out
    }

    @Test
    fun `parses all three windows from go status response`() {
        val windows = parseWindows(buildMetersJson())

        assertEquals(3, windows.size)
        assertNotNull(windows["rolling"])
        assertNotNull(windows["weekly"])
        assertNotNull(windows["monthly"])
    }

    @Test
    fun `converts microCents to usd`() {
        val windows = parseWindows(buildMetersJson(fiveHourUsedUsd = 1.2, fiveHourLimitUsd = 12.0))
        val fiveHour = windows["rolling"]!!

        assertEquals(1.2, fiveHour.usedUsd!!, 1e-9)
        assertEquals(12.0, fiveHour.limitUsd!!, 1e-9)
    }

    @Test
    fun `computes usage percent from used over limit`() {
        // 5h: 1.2 / 12 = 10%
        val windows = parseWindows(buildMetersJson(fiveHourUsedUsd = 1.2, fiveHourLimitUsd = 12.0))
        assertEquals(10.0, windows["rolling"]!!.usagePercent(), 0.01)

        // week: 3 / 30 = 10%
        assertEquals(10.0, windows["weekly"]!!.usagePercent(), 0.01)
    }

    @Test
    fun `clamps usage percent to 100 when over limit`() {
        val windows = parseWindows(buildMetersJson(fiveHourUsedUsd = 20.0, fiveHourLimitUsd = 12.0))
        assertEquals(100.0, windows["rolling"]!!.usagePercent(), 0.01)
    }

    @Test
    fun `returns zero percent when limit is missing`() {
        val windows = parseWindows(buildMetersJson(fiveHourLimitUsd = null))
        assertEquals(0.0, windows["rolling"]!!.usagePercent(), 0.01)
    }

    @Test
    fun `returns zero percent when limit is zero`() {
        val windows = parseWindows(buildMetersJson(fiveHourLimitUsd = 0.0))
        assertEquals(0.0, windows["rolling"]!!.usagePercent(), 0.01)
    }

    @Test
    fun `resetInSec is derived from resetsAt`() {
        val resetsAt = System.currentTimeMillis() + 3_600_000L   // 1 小时后
        val meters = """{"meters":{"fiveHour":{"resetsAt":$resetsAt,
            |"usedMicroCents":"0","limitMicroCents":"1200000000"}}}""".trimMargin()
        val windows = parseWindows(meters)

        val sec = windows["rolling"]!!.resetInSec()
        assertTrue("resetInSec 应接近 3600，实际=$sec", sec in 3500..3600)
    }

    @Test
    fun `resetInSec is zero when already past`() {
        val windows = parseWindows(buildMetersJson(resetsAt = 1_000L))
        assertEquals(0L, windows["rolling"]!!.resetInSec())
    }

    @Test
    fun `month window has no startsAt but still parses`() {
        val windows = parseWindows(buildMetersJson())
        assertNull("month 窗口不应有 startsAt", windows["monthly"]!!.startsAt)
        assertNotNull(windows["monthly"]!!.resetsAt)
    }

    @Test
    fun `empty meters object yields no windows`() {
        val meters = json.parseToJsonElement("""{"meters":{}}""").jsonObject["meters"]!!.jsonObject
        assertTrue(meters.isEmpty())
    }

    @Test
    fun `null microCents fields are tolerated`() {
        val meters = """{"meters":{"fiveHour":{"resetsAt":1790945494815,
            |"usedMicroCents":null,"limitMicroCents":null}}}""".trimMargin()
        val fiveHour = parseWindows(meters)["rolling"]!!
        assertNull(fiveHour.usedUsd)
        assertNull(fiveHour.limitUsd)
        assertEquals(0.0, fiveHour.usagePercent(), 0.01)
    }

    @Test
    fun `numeric (non-string) microCents are accepted`() {
        val meters = """{"meters":{"fiveHour":{"resetsAt":1790945494815,
            |"usedMicroCents":600000000,"limitMicroCents":6000000000}}}""".trimMargin()
        val fiveHour = parseWindows(meters)["rolling"]!!
        assertEquals(6.0, fiveHour.usedUsd!!, 1e-9)
        assertEquals(60.0, fiveHour.limitUsd!!, 1e-9)
        assertEquals(10.0, fiveHour.usagePercent(), 0.01)
    }

    @Test
    fun `window missing everything is treated as absent`() {
        val parsed = json.parseToJsonElement("""{"meters":{"fiveHour":{}}}""")
            .jsonObject["meters"]!!.jsonObject
        assertNull("空窗口对象应返回 null", parsed["fiveHour"]!!.jsonObject.toGoWindow())
    }
}
