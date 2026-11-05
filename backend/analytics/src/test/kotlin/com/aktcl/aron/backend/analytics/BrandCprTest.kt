package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Brand CPR (D-TSO-MAN-06, screenshot 7, contract 1.10.0 `queryNationalBrandCpr`) on tso_dashboard_day.sql through the SQL: per brand
 * the distinct (route, outlet) pairs of the national route set with an effective sale line of the brand, over
 * `cfg.kpi.brand_cpr_denominator.tso` (successful_calls by default: 2 / 3 = 67 %, 1 / 3 = 33 %); both ratios and both denominators
 * are sent, so the switch is one config change.
 */
class BrandCprTest : TsoDayFixture() {
    private val en = UiLocale.EN

    @Test
    fun theScreenshotFiguresOnTheDefaultDenominator() {
        val b = national().brandCpr(caller(32), all)
        assertEquals("successful_calls", b.denominator)
        assertEquals(119 to 3, b.target_outlets to b.successful_calls, "as the CPR card")
        assertEquals(listOf("B-MAX", "B-BG"), b.brands.map { it.brand_code }, "outlets_bought descending")
        val maxim = b.brands[0]; val bidi = b.brands[1]
        assertEquals("Maxim" to 2, maxim.brand_name to maxim.outlets_bought)
        assertEquals("Bidi Gold" to 1, bidi.brand_name to bidi.outlets_bought, "the excused route's Bidi Gold outlet is outside the route set")
        assertEquals(listOf(6667L, 6667L, 168L), listOf(maxim.cpr_bp, maxim.cpr_of_successful_bp, maxim.cpr_of_target_bp))
        assertEquals(listOf(3333L, 3333L, 84L), listOf(bidi.cpr_bp, bidi.cpr_of_successful_bp, bidi.cpr_of_target_bp))
        // The y axis prints whole percent from the integers (D-TSO-MAN-10), never from the rounded bp.
        assertEquals(listOf("67%", "33%"), listOf(Percent.wholeText(2, 3, en), Percent.wholeText(1, 3, en)))
    }

    @Test
    fun theDenominatorIsOneConfigSwitch() {
        val b = national(mapOf(BRAND_CPR_DENOMINATOR to "target_outlets")).brandCpr(caller(32), all)
        assertEquals("target_outlets", b.denominator)
        assertEquals(listOf(168L, 84L), b.brands.map { it.cpr_bp }, "2 / 119 and 1 / 119")
        assertEquals(listOf(6667L, 3333L), b.brands.map { it.cpr_of_successful_bp }, "the other ratio is still sent")
        assertEquals(listOf("2%", "1%"), listOf(Percent.wholeText(2, 119, en), Percent.wholeText(1, 119, en)))
        assertEquals("successful_calls", national(mapOf(BRAND_CPR_DENOMINATOR to "outlets")).brandCpr(caller(32), all).denominator, "an unknown value is the default")
    }

    @Test
    fun onlyTheNationalRouteSetCountsAndTheProductFilterNarrowsIt() {
        // National: RX (not planned) and the AMO, manual, dummy and excused routes sold Maxim and Bidi Gold too; none of them counts.
        val n = national().brandCpr(caller(31), all)
        assertEquals(listOf(2, 1), n.brands.map { it.outlets_bought })
        assertEquals(3, n.successful_calls)
        val bidiOnly = national().brandCpr(caller(32), all.copy(brand_codes = listOf("B-BG")))
        assertEquals(listOf("B-BG"), bidiOnly.brands.map { it.brand_code })
        assertEquals(1, bidiOnly.successful_calls, "a product filter counts the calls with a line of the product (as the CPR card)")
        assertEquals(10_000L, bidiOnly.brands.single().cpr_bp)
        val routeOnly = national().brandCpr(caller(35), all)
        assertEquals(listOf(2, 1), routeOnly.brands.map { it.outlets_bought }, "a route-only TSO reads its routes R1 and R2")
        val gulshan = national().brandCpr(caller(34), all)
        assertEquals(emptyList(), gulshan.brands); assertEquals(20 to 0, gulshan.target_outlets to gulshan.successful_calls)
    }

    @Test
    fun aBrandUserIsRefusedTheDenominatorsHaveNoBrandDimension() {
        assertEquals(ProblemCode.ERR_FORBIDDEN, assertFailsWith<ApiProblem> { national().brandCpr(caller(38), all) }.code)
    }

    @Test
    fun overHttpTheScopeComesFromTheTokenAndTheClientCannotWidenIt() = http {
        val own = post(34, "/dashboards/national/brand-cpr").obj()
        assertEquals(0, own["brands"]!!.jsonArray.size, "Gulshan - Test bought no brand on the route set")
        val widened = post(34, "/dashboards/national/brand-cpr", """{"date":"2026-10-25","zone_ids":[${id("Z1")},${id("Z2")}]}""").obj()
        assertEquals(0, widened["brands"]!!.jsonArray.size, "naming Banani - Test is cut to the reach")
        assertEquals("20", widened["target_outlets"]!!.jsonPrimitive.content)
        val t = post(32, "/dashboards/national/brand-cpr").obj()
        assertEquals(listOf("B-MAX", "B-BG"), t["brands"]!!.jsonArray.map { it.jsonObject["brand_code"]!!.jsonPrimitive.content })
        for (k in listOf("date", "till_time", "as_of", "last_updated_at", "target_outlets", "successful_calls", "denominator", "brands")) assertTrue(k in t, k)
        val none = post(34, "/dashboards/national/brand-cpr", """{"date":"2026-10-25","brand_codes":["B-MAX"]}""").obj()
        assertEquals(0, none["brands"]!!.jsonArray.size)
        assertEquals(403, post(38, "/dashboards/national/brand-cpr").status.value, "BRAND")
        assertEquals(403, post(39, "/dashboards/national/brand-cpr").status.value, "AMO")
        assertEquals(400, post(32, "/dashboards/national/brand-cpr", """{"date":"2026-10-25","surprise":1}""").status.value)
        assertNull(post(34, "/dashboards/national/brand-cpr").obj()["brands"]!!.jsonArray.firstOrNull())
    }
}
