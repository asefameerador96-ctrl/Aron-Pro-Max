package com.aktcl.aron.backend.analytics

import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * By Segment Value Contribution (D-TSO-MAN-11, screenshot 7, contract 1.10.0 `queryNationalSegmentShare`) on tso_dashboard_day.sql
 * through the SQL: value share = the segment's sale-line gross milli-taka / the total (s4.2: 750 / 1000 -> 7500 bp); volume share
 * within the segment's unit family (stick: cigarette and bidi; piece; dozen), never added across units; ordered by value.
 */
class SegmentShareTest : TsoDayFixture() {
    private val en = UiLocale.EN

    @Test
    fun theScreenshotTsosSegmentsByValueThenVolume() {
        val s = national().segmentShare(caller(32), all)
        assertEquals(1_000_000L, s.total_value_mtk, "sale lines only: the free and DRP lines carry no value")
        assertEquals(listOf("SEG-BID", "SEG-MED"), s.segments.map { it.segment_code }, "x axis by value descending (screenshot: Bidi, then Medium)")
        val (bidi, medium) = s.segments
        assertEquals(listOf("Bidi", "bidi", "stick"), listOf(bidi.segment_name, bidi.category_code, bidi.base_unit))
        assertEquals(750_000L to 7500, bidi.value_mtk to bidi.value_share_bp)
        assertEquals(250_000L to 2500, medium.value_mtk to medium.value_share_bp)
        // Volume within the stick family: 2991 + 40 = 3031 sticks (the 30 free sticks never count).
        assertEquals(listOf(SegmentFamily("stick", 3031)), s.families)
        assertEquals(2991L to 9868, bidi.qty_base to bidi.volume_share_bp)
        assertEquals(40L to 132, medium.qty_base to medium.volume_share_bp)
        assertEquals(listOf("75%", "25%"), listOf(Percent.wholeText(750, 1000, en), Percent.wholeText(250, 1000, en)), "the axis prints whole percent")
    }

    @Test
    fun differentUnitsAreNeverAdded() {
        // National: RX adds 1 stick of Maxim, 2 lighters (pieces) and 1 dozen matches. Each family is its own denominator.
        val s = national().segmentShare(caller(31), all)
        assertEquals(listOf(SegmentFamily("stick", 3032), SegmentFamily("piece", 2), SegmentFamily("dozen", 1)), s.families)
        val by = s.segments.associateBy { it.segment_code }
        assertEquals(10_000, by.getValue("SEG-LGT").volume_share_bp, "the only piece segment holds its whole family")
        assertEquals(10_000, by.getValue("SEG-MAT").volume_share_bp)
        assertEquals(1_031_250L, s.total_value_mtk)
        assertEquals(listOf("SEG-BID", "SEG-MED", "SEG-LGT", "SEG-MAT"), s.segments.map { it.segment_code })
        // 750000, 256250, 20000 and 5000 of 1031250 mtk, each rounded once from the integers.
        assertEquals(listOf(7273, 2485, 194, 48), s.segments.map { it.value_share_bp })
    }

    @Test
    fun aBrandUserReadsItsBrandsAndAnEmptyScopeHasNoShare() {
        val b = national().segmentShare(caller(38), all)                  // BRAND user of Maxim: its brand's segment only
        assertEquals(listOf("SEG-MED"), b.segments.map { it.segment_code })
        assertEquals(10_000, b.segments.single().value_share_bp)
        val none = national().segmentShare(caller(32), all.copy(date = "2026-10-24"))
        assertEquals(0L, none.total_value_mtk); assertEquals(emptyList(), none.segments); assertEquals(emptyList(), none.families)
    }

    @Test
    fun overHttpTheScopeComesFromTheTokenAndTheClientCannotWidenIt() = http {
        val own = post(34, "/dashboards/national/segment-share").obj()                // Gulshan - Test: the excused route's one Bidi stick
        assertEquals(listOf("SEG-BID"), own["segments"]!!.jsonArray.map { it.jsonObject["segment_code"]!!.jsonPrimitive.content })
        assertEquals("2500", own["total_value_mtk"]!!.jsonPrimitive.content)
        val widened = post(34, "/dashboards/national/segment-share", """{"date":"2026-10-25","zone_ids":[${id("Z1")},${id("Z2")}]}""").obj()
        assertEquals("2500", widened["total_value_mtk"]!!.jsonPrimitive.content, "naming Banani - Test is cut to the reach")
        val t = post(32, "/dashboards/national/segment-share").obj()
        for (k in listOf("date", "till_time", "as_of", "last_updated_at", "total_value_mtk", "families", "segments")) assertTrue(k in t, k)
        // The service cuts a BRAND user to its brands (above), but the platform's BRAND allow-list (PortalLevels.BRAND_DASHBOARD) does not
        // list this 1.10.0 route yet, so the guard refuses it first (ask in docs/status/tso-manual-B2.md).
        assertEquals(403, post(38, "/dashboards/national/segment-share").status.value, "BRAND: refused by the portal-level guard")
        assertEquals(403, post(39, "/dashboards/national/segment-share").status.value, "AMO")
    }
}
