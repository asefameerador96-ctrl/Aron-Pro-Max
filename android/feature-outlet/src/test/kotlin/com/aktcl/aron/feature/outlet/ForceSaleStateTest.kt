package com.aktcl.aron.feature.outlet

import com.aktcl.aron.rules.GeoAction
import com.aktcl.aron.rules.GeoVerdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device walk 2026-11-13 (force-sale state): after a force sale the same outlet later said "You are within the outlet's
 * range" although the phone had not moved. A force sale must NEVER make a visit count as a clean geo sale (docs/05): each
 * visit decides from its own stored row, and a screen with no fix in memory may not claim the range was passed.
 */
class ForceSaleStateTest {
    private fun visit(verdict: String, action: GeoAction, force: String? = null, validated: Boolean = verdict == "in_range") = OpenVisit(
        visitUuid = "11111111-1111-4111-8111-111111111111", outletId = 50001, routeId = 10231, geoVerdict = verdict,
        geoAction = action.wire, geoValidated = validated, photoValidated = force != null, forceReasonCode = force,
        openedAtIso = "2026-11-13T04:31:07.120Z",
    )

    @Test fun aGeoValidatedVisitIsACleanGeoSale() {
        val v = visit("in_range", GeoAction.SALE_ALLOWED)
        assertTrue(v.cleanGeoSale)
        assertTrue(v.verdict == GeoVerdict.IN_RANGE)
    }

    @Test fun aForceSaleIsNeverACleanGeoSale() {
        val forced = visit("out_of_range", GeoAction.FORCE_SALE, force = "location_change")
        assertFalse(forced.cleanGeoSale)
        assertTrue(forced.verdict == GeoVerdict.OUT_OF_RANGE) // the row still says why, for a restored call with no fix
    }

    /** The trap seen on the phone: a force-sale row that, by whatever path, carries in_range or a validated flag. */
    @Test fun aForceReasonBeatsAnyInRangeFlagOnTheSameRow() {
        assertFalse(visit("in_range", GeoAction.FORCE_SALE, force = "internet_problem", validated = true).cleanGeoSale)
        assertFalse(visit("in_range", GeoAction.SALE_ALLOWED, force = "internet_problem", validated = true).cleanGeoSale)
    }

    /** Nothing is carried over: a later visit to the same outlet is a different row and decides for itself. */
    @Test fun aLaterVisitToTheSameOutletDecidesAgain() {
        val forced = visit("out_of_range", GeoAction.FORCE_SALE, force = "location_change")
        val later = visit("in_range", GeoAction.SALE_ALLOWED).copy(visitUuid = "22222222-2222-4222-8222-222222222222")
        assertFalse(forced.cleanGeoSale)
        assertTrue(later.cleanGeoSale) // and the reverse: a clean call after a forced one is still clean
        val laterForced = visit("out_of_range", GeoAction.FORCE_SALE, force = "location_change").copy(visitUuid = "33333333-3333-4333-8333-333333333333")
        assertFalse(laterForced.cleanGeoSale)
    }
}
