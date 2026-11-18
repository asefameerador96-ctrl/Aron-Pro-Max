package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RedemptionModelTest {
    private val chair = LoyaltyGift(1, 3, "Chair", "চেয়ার।", 200, emptyList(), null, true)
    private val fan = LoyaltyGift(2, 3, "Fan", null, 400, emptyList(), null, true)
    private val astha = LoyaltyGift(3, 3, "Phone", null, null, emptyList(), null, true)
    private val gifts = listOf(chair, fan, astha)
    private val b0 = Basket(10, 3, balance = 220)
    private var n = 0
    private fun uuid() = "00000000-0000-4000-8000-%012d".format(++n)

    @Test fun plusAndMinusKeepQuantitiesWithinTheContractBounds() {
        var b = b0
        repeat(60) { b = b.plus(chair) }
        assertEquals(50, b.qtyOf(chair))
        assertEquals(49, b.minus(chair).qtyOf(chair))
        assertTrue(b0.plus(chair).minus(chair).isEmpty()) // down to zero leaves the line
        assertTrue(b0.plus(astha).isEmpty()) // a gift with no cost is not redeemable
    }

    @Test fun atMostTwentyDifferentGifts() {
        val many = (1L..25L).map { LoyaltyGift(it, 3, "G$it", null, 10, emptyList(), null, true) }
        var b = b0
        many.forEach { b = b.plus(it) }
        assertEquals(20, b.qty.size)
    }

    @Test fun remainingIsBalanceMinusBasketAndAnOverdrawIsFlaggedNotBlocked() {
        assertEquals(20L, b0.plus(chair).remaining(gifts)); assertFalse(b0.plus(chair).overdraws(gifts))
        val over = b0.plus(chair).plus(chair)
        assertEquals(-180L, over.remaining(gifts)); assertTrue(over.overdraws(gifts))
        assertEquals(1, over.plan(gifts, uuid(), ::uuid, "2026-11-18T05:00:00.000Z").count { it.type == "redemption" }) // the phone still plans it
        assertEquals(-5L, Basket(10, 3, -5).remaining(gifts)) // a negative starting balance is shown as it is
    }

    @Test fun theConfirmPlanIsOneRedemptionAndOneLinePerGiftWithTheContractTotals() {
        val p = b0.plus(chair).plus(chair).plus(fan).plan(gifts, "11111111-1111-4111-8111-111111111111", ::uuid, "2026-11-18T05:00:00.000Z", cashRateMtkPerPoint = 2_000)
        assertEquals(3, p.size)
        assertEquals("redemption", p[0].type); assertEquals(0, p[0].rank); assertEquals(p[0].clientUuid, p[0].familyUuid)
        assertTrue(p.drop(1).all { it.type == "redemption_line" && it.rank == 1 && it.familyUuid == p[0].clientUuid })
        assertEquals("800", p[0].payload["points_total"].toString()) // 2 x 200 + 400
        assertEquals("2", p[0].payload["line_count"].toString())
        assertEquals("400", p[1].payload["points_total"].toString()); assertEquals("2", p[1].payload["qty"].toString())
        assertNull(com.aktcl.aron.core.database.record.ProgrammePayloads.problem("redemption", p[0].payload))
        p.drop(1).forEach { assertNull(com.aktcl.aron.core.database.record.ProgrammePayloads.problem("redemption_line", it.payload)) }
    }

    @Test fun anEmptyBasketIsNotARedemption() {
        assertThrows(IllegalArgumentException::class.java) { b0.plan(gifts, uuid(), ::uuid, "2026-11-18T05:00:00.000Z") }
    }

    @Test fun aRedemptionCountsUntilABundleBuiltAfterItsAcknowledgementHasArrived() {
        val built = "2026-11-18T01:00:00.000Z"
        val r = listOf(
            LocalRedemption(10, 3, 100, "2026-11-17T09:00:00.000Z", "acked", ackedAt = "2026-11-17T09:05:00.000Z"), // acked before the bundle was built: inside the balance
            LocalRedemption(10, 3, 30, "2026-11-17T09:00:00.000Z", "pending"), // the server may not know it yet
            LocalRedemption(10, 3, 20, "2026-11-18T03:00:00.000Z", "acked", ackedAt = "2026-11-18T03:01:00.000Z"), // acked after the bundle was built: not inside it
            LocalRedemption(10, 3, 50, "2026-11-18T03:00:00.000Z", "rejected"), // refused by the server: gone
            LocalRedemption(11, 3, 70, "2026-11-18T03:00:00.000Z", "pending"), // another outlet
            LocalRedemption(10, 4, 60, "2026-11-18T03:00:00.000Z", "pending"), // another programme
        )
        assertEquals(50L, LocalRedeemed.points(r, 10, 3, built))
        assertEquals(150L, LocalRedeemed.points(r, 10, 3, null)) // build time unknown: every non-refused one counts (never overstate)
        // instants, not text: the same instant written with an offset or fewer digits compares as the instant it is
        assertEquals(0L, LocalRedeemed.points(listOf(LocalRedemption(10, 3, 9, "x", "acked", ackedAt = "2026-11-17T19:00:00Z")), 10, 3, "2026-11-18T01:30:00.000+06:00"))
    }

    @Test fun oneBalanceFunctionForEveryScreen() {
        val local = listOf(LocalRedemption(10, 3, 220, "2026-11-18T03:00:00.000Z", "pending"))
        assertEquals(0L, PointsBalance.of(220, local, 10, 3, "2026-11-18T01:00:00.000Z"))
        val f = PointsBalance.function(local, "2026-11-18T01:00:00.000Z")
        assertEquals(-180L, f(10, 3, 40)) // the points page and the redemption screen call this same function
        assertTrue(PointsBalance.overdrawn(f(10, 3, 40))); assertFalse(PointsBalance.overdrawn(f(10, 3, 220)))
    }
}
