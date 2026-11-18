package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftChoiceModelTest {
    private val json = """{"programmes":[{"programme_id":5,"kind":"astha","name_en":"Astha"},{"programme_id":3,"kind":"diamond_league","name_en":"DL"}],
      "loyalty":[],"gifts":[
        {"gift_id":1,"programme_id":5,"name_en":"Hotpot","name_bn":"হটপট","points_cost":null,"tier_codes":["gold","diamond"],"status":"active"},
        {"gift_id":2,"programme_id":5,"name_en":"Feature Phone","points_cost":null,"tier_codes":[],"status":"active"},
        {"gift_id":3,"programme_id":5,"name_en":"Old","points_cost":null,"tier_codes":[],"status":"inactive"},
        {"gift_id":4,"programme_id":3,"name_en":"Chair","points_cost":200,"status":"active"}],
      "gift_assignments":[{"gift_assignment_id":1,"quarter":"2026-Q4","outlet_id":10,"route_id":7,"gift_id":1,"tier_code":"gold","photo_captured":false,"locked":false},
                          {"gift_assignment_id":2,"quarter":"2026-Q4","outlet_id":11,"route_id":7,"gift_id":2,"photo_captured":true,"locked":true}]}"""
    private val d = GiftChoiceModel.parse(json)!!

    @Test fun onlyActiveAsthaGiftsAndAnOutletSeesTheOnesForItsTier() {
        assertEquals(listOf(1L, 2L, 3L), d.gifts.map { it.id }) // astha programme gifts only (the Diamond League chair is not one)
        assertEquals(listOf(1L, 2L), d.offeredTo("gold").map { it.id }) // inactive hidden
        assertEquals(listOf(2L), d.offeredTo("titanium").map { it.id }) // restricted gift not offered to another tier
        assertEquals(listOf(2L), d.offeredTo(null).map { it.id }) // tier unknown: only unrestricted gifts, never a guess
    }

    @Test fun stateIsLockedThenThisPhoneThenTheServerThenNone() {
        assertEquals(GiftState.Chosen(1), d.stateOf(10, "2026-Q4", emptyList()))
        assertEquals(GiftState.Locked(2), d.stateOf(11, "2026-Q4", listOf(LocalGiftChoice(11, "2026-Q4", 1, "pending")))) // a photo locks it: a later choice is not offered
        assertEquals(GiftState.OnThisPhone(2, false), d.stateOf(10, "2026-Q4", listOf(LocalGiftChoice(10, "2026-Q4", 2, "pending"))))
        assertEquals(GiftState.OnThisPhone(2, true), d.stateOf(10, "2026-Q4", listOf(LocalGiftChoice(10, "2026-Q4", 1, "pending"), LocalGiftChoice(10, "2026-Q4", 2, "rejected"))))
        assertEquals(GiftState.None, d.stateOf(12, "2026-Q4", emptyList()))
        assertEquals(GiftState.None, d.stateOf(10, "2027-Q1", emptyList())) // another quarter
    }

    @Test fun theQuarterOfADhakaBusinessDate() {
        assertEquals("2026-Q4", GiftChoiceModel.quarterOf("2026-11-18"))
        assertEquals("2026-Q1", GiftChoiceModel.quarterOf("2026-01-01"))
        assertEquals("2026-Q4", GiftChoiceModel.quarterOf("2026-02-10", startMonth = 4)) // the server's naming: the year of the quarter's FIRST month (January 2026), Q4 of a series that started in April
        assertEquals("2026-Q1", GiftChoiceModel.quarterOf("2026-04-01", startMonth = 4))
    }

    @Test fun theChoiceRecordIsOwnFamilyRankZeroAndMatchesTheContract() {
        val r = GiftChoiceModel.plan(10, 1, "2026-Q4", "11111111-1111-4111-8111-111111111111")
        assertEquals("gift_choice", r.type); assertEquals(0, r.rank); assertEquals(r.clientUuid, r.familyUuid)
        assertNull(com.aktcl.aron.core.database.record.ProgrammePayloads.problem("gift_choice", r.payload))
        assertTrue(r.payload.keys == setOf("outlet_id", "gift_id", "quarter"))
    }

    @Test fun missingSectionIsNull() { assertNull(GiftChoiceModel.parse(null)); assertNull(GiftChoiceModel.parse("[]")) }

    @Test fun aSentUnconfirmedChoiceNeverHidesTheBundlesAssignmentAndOtherwiseReadsNotConfirmed() {
        val unsure = LocalGiftChoice(10, "2026-Q4", 2, "uncertain", uuid = "u1")
        assertEquals(GiftState.Chosen(1), d.stateOf(10, "2026-Q4", listOf(unsure))) // the server's assignment is shown
        val other = LocalGiftChoice(99, "2026-Q4", 2, "uncertain", uuid = "u2")
        assertEquals(GiftState.Unconfirmed(2), d.stateOf(99, "2026-Q4", listOf(other))) // no assignment in the bundle: "sent, not confirmed"
    }

    @Test fun anAcknowledgedChoiceReadsSentUntilABundleRequestedAfterTheAckHoldsItAndARefusalCarriesTheServersReason() {
        val acked = LocalGiftChoice(10, "2026-Q4", 2, "acked", uuid = "c1")
        assertEquals(GiftState.Sent(2), d.stateOf(10, "2026-Q4", listOf(acked), emptySet())) // the bundle request left before the ack: shows the old gift, the phone says sent
        assertEquals(GiftState.Chosen(1), d.stateOf(10, "2026-Q4", listOf(acked), setOf("c1"))) // a bundle requested after the ack wins over the old local choice
        val refused = LocalGiftChoice(10, "2026-Q4", 2, "rejected", reasonBn = "বদ্ধ", reasonEn = "Locked")
        assertEquals(GiftState.OnThisPhone(2, true, "বদ্ধ", "Locked"), d.stateOf(10, "2026-Q4", listOf(refused)))
    }
}
