package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteFiguresTest {
    private val route = """{"route_id":7,"targets":[
        {"scope_type":"route","scope_id":7,"month":"2026-11","product_level":"sku","product_id":900,"std_target":"500.000","std_unit":"stick","memo_target":0},
        {"scope_type":"zone","scope_id":2,"month":"2026-11","product_level":"sku","product_id":901,"std_target":"9.000","std_unit":"stick","memo_target":0}],
      "achievement_mtd":[{"scope_type":"route","scope_id":7,"month":"2026-11","product_level":"sku","product_id":900,"achieved_qty_base":120,"memo_count":5,"as_of":"2026-11-17T20:00:00Z","through_date":"2026-11-17"}],
      "calendar":{"month":"2026-11","working_days":26,"elapsed_working_days_before_today":14,"remaining_working_days_after_today":11,"today_is_working":true,"last_month_working_days":26,"through_date":"2026-11-17","as_of":"2026-11-18T01:00:00Z"},
      "kpi":{"month":"2026-11","through_date":"2026-11-17","as_of":"2026-11-18T01:00:00Z","allocated_mtk":5000000,"achieved_mtk":null,"achievement_bp":null,
        "volume":{"target":1000,"achieved":400,"bp":4000},"memo":{"target":50,"achieved":20,"bp":null},
        "brands":[{"brand_id":11,"volume_target":600,"volume_achieved":300,"volume_bp":5000,"memo_target":30,"memo_achieved":15,"memo_bp":5000,"kpi_alloc_mtk":100000,"kpi_today_mtk":null,"by_pack":[{"pack_factor":20,"target":600,"achieved":300}]}],
        "brands_truncated":false,"categories":[{"category_code":"match","volume_target":10,"volume_achieved":4,"volume_bp":4000,"by_pack":[]}],"cpr":{"target_bp":8000,"alloc_mtk":null,"achieved_bp":null}},
      "star":{"programme_id":3,"programme_code":"LS","name_en":"Lifting Star","name_bn":"লিফটিং স্টার","month":"2026-11","through_date":"2026-11-17","as_of":"2026-11-18T01:00:00Z","sr_name":"Rahim","sr_code":"sr1","route_name":"R-7",
        "lmwd":26,"wd":26,"ewd":0,"target":"100.000","std":"40.500","projection":null,"achievement_bp":null,"lms":"80.000","lmads":"3.077","pads":null,"growth_bp":null,"status_en":"Keep Pushing","status_bn":"চালিয়ে যান","achieved_slab":null,"slab_amount_mtk":null,"projected_slab":null,"projected_amount_mtk":null},
      "campaigns":[{"outlet_id":501,"programme_id":4,"label_en":"Super Star Low","label_bn":null,"month":"2026-11","through_date":"2026-11-17","as_of":"2026-11-18T01:00:00Z",
        "std":{"target":200,"achieved":50,"remaining":150},"memo":{"target":10,"achieved":3,"remaining":7},"skus":[{"sku_id":900,"target_qty_base":200,"sold_qty_base":50}]}],
      "campaigns_truncated":true}"""

    @Test fun theSixSectionsAreParsedOnceAsSent() {
        val f = RouteFigures.parse(route)!!
        assertEquals(7L, f.routeId)
        assertEquals(listOf(SkuTarget(900, "2026-11", 500_000, "stick", 0)), f.targets) // only the route SKU rows
        assertEquals(120L, f.achievedOf(900)!!.qtyBase); assertNull(f.achievedOf(1))
        assertEquals(14, f.calendar!!.dayCounts().elapsed); assertEquals(11, f.calendar!!.dayCounts().remaining)
        assertEquals("2026-11", f.month); assertEquals("2026-11-17", f.throughDate)
        assertTrue(f.campaignsTruncated); assertEquals(150L, f.campaigns.single().std.remaining)
    }

    @Test fun figuresTheOwnerHasNotDefinedStayNullNeverZero() {
        val k = RouteFigures.parse(route)!!.kpi!!
        assertNull(k.achievedMtk); assertNull(k.achievementBp); assertNull(k.memo.bp); assertNull(k.brands.single().kpiTodayMtk); assertNull(k.cpr!!.achievedBp)
        assertEquals(5_000_000L, k.allocatedMtk)
        val s = RouteFigures.parse(route)!!.star!!
        assertNull(s.projection()); assertNull(s.achievementBp); assertNull(s.padsMilli); assertNull(s.growthBp)
        assertEquals(0, s.ewd) // ewd 0 is sent as 0: the screen shows a dash for the ratios, never 0 %
        assertEquals(40_500L, s.stdMilli)
    }

    @Test fun anAbsentOrNullSectionIsNullOrEmptyAndABadMemberIsDroppedAlone() {
        val f = RouteFigures.parse("""{"route_id":1,"targets":[{"scope_type":"route","product_level":"sku","product_id":1,"month":"2026-11","std_target":"x","std_unit":"stick"}],"kpi":null,"calendar":null}""")!!
        assertEquals(emptyList<SkuTarget>(), f.targets); assertNull(f.kpi); assertNull(f.calendar); assertNull(f.star); assertEquals(emptyList<RouteCampaign>(), f.campaigns)
        assertNull(RouteFigures.parse("[]")); assertNull(RouteFigures.parse("not json")); assertNotNull(RouteFigures.parse("{}"))
    }

    private fun RouteStar.projection(): Long? = projectionMilli
}
