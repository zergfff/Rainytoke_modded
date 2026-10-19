package com.rainy.token.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseCredits] 的纯 JVM 解析测试。
 *
 * 样本结构取自 Open Design Cloud 控制台 bundle 里 `credits/me` 的消费方：
 * `totalBalance` / `totalAllocated` / `totalRecharged` / `remainingBySource` /
 * `lowBalanceState` / `periodEndAt`，金额单位 1/10000 USD（`Lr = 1e4`）。
 */
class OpenDesignRepositoryTest {

    @Test
    fun `parses credit payload and converts ledger units to usd`() {
        val parsed = parseCredits(
            """
            {
              "workspaceMemberId": "wm_1",
              "totalBalance": 123400,
              "totalAllocated": 1200000,
              "totalRecharged": 50000,
              "remainingBySource": { "subscription": 100000, "recharge": 23400 },
              "lowBalanceState": "low",
              "periodEndAt": "2026-10-26T08:00:00.000Z"
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(12.34, parsed!!.balanceUsd, 1e-9)
        assertEquals(120.0, parsed.allocatedUsd!!, 1e-9)
        assertEquals(5.0, parsed.rechargedUsd!!, 1e-9)
        assertEquals(10.0, parsed.subscriptionUsd!!, 1e-9)
        assertEquals("low", parsed.lowBalanceState)
        assertEquals("2026-10-26T08:00:00.000Z", parsed.periodEndAt)
    }

    @Test
    fun `accepts numbers encoded as strings`() {
        val parsed = parseCredits(
            """{"totalBalance":"25000","totalAllocated":"100000"}"""
        )

        assertNotNull(parsed)
        assertEquals(2.5, parsed!!.balanceUsd, 1e-9)
        assertEquals(10.0, parsed.allocatedUsd!!, 1e-9)
    }

    @Test
    fun `unwraps nested credit object`() {
        val parsed = parseCredits(
            """{"credit":{"totalBalance":40000,"lowBalanceState":"ok"}}"""
        )

        assertNotNull(parsed)
        assertEquals(4.0, parsed!!.balanceUsd, 1e-9)
        assertEquals("ok", parsed.lowBalanceState)
    }

    @Test
    fun `returns null when totalBalance missing`() {
        assertNull(parseCredits("""{"totalAllocated":100000}"""))
        assertNull(parseCredits(""))
        assertNull(parseCredits("not json"))
    }

    @Test
    fun `keeps zero balance instead of treating it as missing`() {
        val parsed = parseCredits(
            """{"totalBalance":0,"totalAllocated":0,"lowBalanceState":"empty"}"""
        )

        assertNotNull(parsed)
        assertEquals(0.0, parsed!!.balanceUsd, 1e-9)
        assertEquals(0.0, parsed.allocatedUsd!!, 1e-9)
        assertEquals("empty", parsed.lowBalanceState)
    }

    @Test
    fun `parses control key me response`() {
        val account = parseControlKeyMe(
            """{"id":"u_1","email":"a@b.c","plan":"go","balanceUsd":"12.34"}"""
        )

        assertNotNull(account)
        assertEquals(12.34, account!!.balanceUsd!!, 1e-9)
        assertEquals("go", account.plan)
        assertEquals("a@b.c", account.email)
    }

    @Test
    fun `parses nested me response and allows missing balance`() {
        val account = parseControlKeyMe(
            """{"user":{"id":"u_1","email":"a@b.c","plan":"pro","balanceUsd":null}}"""
        )

        assertNotNull(account)
        assertNull(account!!.balanceUsd)
        assertEquals("pro", account.plan)
    }

    @Test
    fun `parses wallet balance response`() {
        assertEquals(
            7.5,
            parseWalletBalance("""{"balanceUsd":"7.50","updatedAt":"2026-10-19T00:00:00Z"}""")!!,
            1e-9
        )
        assertNull(parseWalletBalance("""{"updatedAt":"2026-10-19T00:00:00Z"}"""))
    }

    @Test
    fun `billing summary reports go plan and period end`() {
        val summary = parseBillingSummary(
            """
            {
              "balanceUsd": "1.0000",
              "creditsPerUsd": 10000,
              "balances": { "subscriptionCredits": 10000, "rechargeCredits": 0, "totalAvailableCredits": 10000 },
              "totalAvailableCreditsUsd": 1.0,
              "membershipTier": "go",
              "billingInterval": "yearly",
              "subscriptionStatus": "active",
              "subscriptionCurrentPeriodEnd": "2027-10-15T17:14:56.000Z",
              "totalConsumedUsd": 0.0
            }
            """.trimIndent()
        )

        assertNotNull(summary)
        assertEquals("go", summary!!.plan)
        assertEquals(1.0, summary.availableUsd, 1e-9)
        assertEquals(0.0, summary.consumedUsd!!, 1e-9)
        assertEquals("active", summary.status)
        assertEquals(java.time.Instant.parse("2027-10-15T17:14:56.000Z").toEpochMilli(), summary.periodEndMillis)
    }

    @Test
    fun `coding plan usage maps 5h and 7d windows`() {
        val windows = parseCodingPlanUsage(
            """
            {
              "windows": [
                { "policyId": "p5h", "durationSeconds": 18000, "remainingCredits": 8100, "limitCredits": 10000, "resetsAt": "2026-10-19T08:30:00.000Z" },
                { "policyId": "p7d", "durationSeconds": 604800, "remainingCredits": 9100, "limitCredits": 10000, "resetsAt": "2026-10-24T06:00:00.000Z" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, windows.size)
        assertEquals("fiveHour", windows[0].extraPrefix())
        assertEquals(19.0, windows[0].usedPercent(), 1e-9)
        assertEquals("weekly", windows[1].extraPrefix())
        assertEquals(9.0, windows[1].usedPercent(), 1e-9)
    }

    @Test
    fun `coding plan usage tolerates missing windows`() {
        assertTrue(parseCodingPlanUsage("{}").isEmpty())
        assertTrue(parseCodingPlanUsage("not json").isEmpty())
    }
}
