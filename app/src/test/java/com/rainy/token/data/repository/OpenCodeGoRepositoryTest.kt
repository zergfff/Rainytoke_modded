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

    // ===== 2026-09 实测：时间字段是 ISO-8601 字符串，不是 epoch 毫秒 =====
    // 真实响应片段：
    // "fiveHour":{"startsAt":"2026-09-30T01:21:35.669Z","resetsAt":"2026-09-30T06:21:35.669Z", ...}
    // "week":{"startsAt":"2026-09-28T00:00:00.000Z","resetsAt":"2026-10-05T00:00:00.000Z", ...}
    // "month":{"resetsAt":"2026-10-08T05:13:17.000Z", ...}

    @Test
    fun `parses ISO-8601 resetsAt with milliseconds`() {
        val windows = parseWindows(
            """{"meters":{"fiveHour":{"resetsAt":"2026-09-30T06:21:35.669Z",
                |"usedMicroCents":"0","limitMicroCents":"1200000000"}}}""".trimMargin()
        )
        // 2026-09-30T06:21:35.669Z 的 epoch 毫秒
        assertEquals(1790749295669L, windows["rolling"]!!.resetsAt)
    }

    @Test
    fun `parses ISO-8601 resetsAt at exact midnight`() {
        val windows = parseWindows(
            """{"meters":{"week":{"resetsAt":"2026-10-05T00:00:00.000Z",
                |"usedMicroCents":"306519745","limitMicroCents":"3000000000"}}}""".trimMargin()
        )
        assertEquals(1791158400000L, windows["weekly"]!!.resetsAt)
    }

    @Test
    fun `parses ISO-8601 without milliseconds`() {
        val windows = parseWindows(
            """{"meters":{"month":{"resetsAt":"2026-10-08T05:13:17Z",
                |"usedMicroCents":"2929845041","limitMicroCents":"6000000000"}}}""".trimMargin()
        )
        assertEquals(1791436397000L, windows["monthly"]!!.resetsAt)
    }

    @Test
    fun `real response values produce expected percentages`() {
        // 实测真实数据：周 $3.0652/$30 = 10.22%，月 $29.2985/$60 = 48.83%
        val meters = """{"meters":{
            "fiveHour":{"startsAt":"2026-09-30T01:21:35.669Z","resetsAt":"2026-09-30T06:21:35.669Z",
                        "limitMicroCents":"1200000000","usedMicroCents":"0"},
            "week":{"startsAt":"2026-09-28T00:00:00.000Z","resetsAt":"2026-10-05T00:00:00.000Z",
                    "limitMicroCents":"3000000000","usedMicroCents":"306519745"},
            "month":{"resetsAt":"2026-10-08T05:13:17.000Z",
                     "limitMicroCents":"6000000000","usedMicroCents":"2929845041"}}}"""
        val windows = parseWindows(meters)

        assertEquals(3, windows.size)
        assertEquals(0.0, windows["rolling"]!!.usagePercent(), 0.01)
        assertEquals(10.22, windows["weekly"]!!.usagePercent(), 0.01)
        assertEquals(48.83, windows["monthly"]!!.usagePercent(), 0.01)

        assertEquals(3.06519745, windows["weekly"]!!.usedUsd!!, 1e-6)
        assertEquals(29.29845041, windows["monthly"]!!.usedUsd!!, 1e-6)
        assertEquals(60.0, windows["monthly"]!!.limitUsd!!, 1e-6)

        // month 窗口实测无 startsAt
        assertNull(windows["monthly"]!!.startsAt)
        assertNotNull(windows["monthly"]!!.resetsAt)
    }

    @Test
    fun `epoch millis still accepted for forward compatibility`() {
        val future = System.currentTimeMillis() + 7_200_000L
        val windows = parseWindows(
            """{"meters":{"fiveHour":{"resetsAt":$future,
                |"usedMicroCents":"0","limitMicroCents":"1200000000"}}}""".trimMargin()
        )
        assertEquals(future, windows["rolling"]!!.resetsAt)
    }

    @Test
    fun `rootTagOf reads trpc error tag`() {
        assertEquals("Unauthorized", rootTagOf("""{"_tag":"Unauthorized"}"""))
        assertEquals("Forbidden", rootTagOf("""{"_tag": "Forbidden"}"""))
        assertNull(rootTagOf("""{"product":"go","access":{}}"""))
    }

    @Test
    fun `parseIsoToEpochMillis handles offsets`() {
        // 带 +08:00 偏移应换算成 UTC
        assertEquals(1791158400000L, parseIsoToEpochMillis("2026-10-05T08:00:00.000+08:00"))
    }

    @Test
    fun `parseIsoToEpochMillis returns null for garbage`() {
        assertNull(parseIsoToEpochMillis("not-a-date"))
        assertNull(parseIsoToEpochMillis(""))
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
