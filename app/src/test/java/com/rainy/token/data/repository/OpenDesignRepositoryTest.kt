package com.rainy.token.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
