package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamPerformanceTest {
    @Test fun bandsAreGreenFrom80AmberFrom40RedBelow() {
        assertEquals(PerfBand.GREEN, TeamPerformance.band(80.0)); assertEquals(PerfBand.AMBER, TeamPerformance.band(79.99)); assertEquals(PerfBand.AMBER, TeamPerformance.band(40.0)); assertEquals(PerfBand.RED, TeamPerformance.band(39.9))
        assertEquals(100.0, TeamPerformance.printed(135.0), 0.0); assertEquals(0.0, TeamPerformance.printed(-3.0), 0.0)
    }

    private val json = """{"rows":[
      {"product_level":"category","product_id":1,"name":"Cigarettes","unit":"stick","target_qty":1000.0,"achieved_qty":850.0,"tilldate_target_qty":500.0,"remaining_qty":150.0},
      {"product_level":"category","product_id":2,"name":"Matches","unit":"dozen","target_qty":0,"achieved_qty":3.0,"tilldate_target_qty":0,"remaining_qty":0},
      {"product_level":"brand","product_id":3,"name":"B","unit":"stick","target_qty":10,"achieved_qty":1,"tilldate_target_qty":5,"remaining_qty":9}]}"""

    @Test fun monthlyAndTillDateBarsOfOneLevel() {
        val m = TeamPerformance.bars(json, "category", false)
        assertEquals(2, m.size); assertEquals(85.0, m[0].pct!!, 0.0); assertEquals(150.0, m[0].remaining, 0.0)
        assertEquals(170.0, TeamPerformance.bars(json, "category", true)[0].pct!!, 0.0)
    }

    @Test fun zeroTargetIsADashAndNoRowsIsEmpty() {
        assertNull(TeamPerformance.bars(json, "category", false)[1].pct)
        assertTrue(TeamPerformance.bars("""{"rows":[]}""", "category", false).isEmpty()); assertTrue(TeamPerformance.bars(null, "category", false).isEmpty())
    }
}
