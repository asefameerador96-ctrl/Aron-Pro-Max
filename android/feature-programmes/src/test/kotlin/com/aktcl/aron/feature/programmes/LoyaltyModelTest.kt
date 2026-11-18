package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoyaltyModelTest {
    private val json = """{"programmes":[{"programme_id":3,"kind":"diamond_league","name_en":"Diamond League","name_bn":"ডায়মন্ড লীগ","period_label":"2026-11","active_from":"2026-11-01","active_to":"2026-11-30","status":"active","version":1}],
      "loyalty":[
        {"outlet_id":10,"programme_id":3,"league_label":"Diamond League (November)","balance_points":110,"overdrawn":false,"expiring_points":110,"expiry_date":"2026-12-07","as_of_date":"2026-11-17"},
        {"outlet_id":11,"programme_id":3,"league_label":null,"balance_points":-20,"expiring_points":0,"expiry_date":null,"as_of_date":"2026-11-17","tier_code":"gold"},
        {"outlet_id":12,"balance_points":5}],
      "gifts":[{"gift_id":1,"programme_id":3,"code":"chair","name_en":"Chair","name_bn":"চেয়ার।","points_cost":200,"tier_codes":[],"image_asset_id":null,"status":"active","version":1},
               {"gift_id":2,"programme_id":3,"name_en":"Fan","points_cost":null,"status":"inactive"}]}"""

    @Test fun parsesProgrammesCardsAndGifts() {
        val d = LoyaltyModel.parse(json)!!
        assertEquals(1, d.programmes.size)
        assertEquals(2, d.cards.size) // the entry without a programme id is skipped, never a crash
        assertEquals(listOf(10L, 11L), d.outletIds)
        assertEquals(2, d.gifts.size)
        assertEquals(200L, d.gifts[0].pointsCost); assertNull(d.gifts[1].pointsCost); assertFalse(d.gifts[1].active)
        assertEquals("চেয়ার।", d.gifts[0].name(UiLocale.BN)); assertEquals("Fan", d.gifts[1].name(UiLocale.BN))
    }

    @Test fun aZeroOrNegativeBalanceIsShownAsItIsNeverClamped() {
        val d = LoyaltyModel.parse(json)!!
        val neg = d.cardsOf(11).single()
        assertEquals(-20L, neg.balancePoints); assertTrue(neg.overdrawn) // overdrawn derived when the field is absent
        assertEquals("gold", neg.tierCode)
        assertEquals("-20 Pts", LoyaltyModel.pointsText(-20, UiLocale.EN, "Pts"))
        assertEquals("০ Pts", LoyaltyModel.pointsText(0, UiLocale.BN, "Pts"))
    }

    @Test fun titleIsTheLeagueLabelElseNameAndPeriod() {
        val d = LoyaltyModel.parse(json)!!
        assertEquals("Diamond League (November)", d.title(d.cardsOf(10).single(), UiLocale.EN))
        assertEquals("ডায়মন্ড লীগ (2026-11)", d.title(d.cardsOf(11).single(), UiLocale.BN))
    }

    @Test fun datesAndMissingSections() {
        assertEquals("২০২৬-১২-০৭", LoyaltyModel.dateText("2026-12-07T00:00:00Z", UiLocale.BN))
        assertEquals("—", LoyaltyModel.dateText(null, UiLocale.EN))
        assertNull(LoyaltyModel.parse(null)); assertNull(LoyaltyModel.parse("[]")); assertNull(LoyaltyModel.parse("x"))
        assertTrue(LoyaltyModel.parse("{}")!!.cards.isEmpty())
    }
}
